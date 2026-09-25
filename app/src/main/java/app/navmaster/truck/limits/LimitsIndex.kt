package app.navmaster.truck.limits

import android.util.Log
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.vehicle.AdrTunnel
import app.navmaster.truck.vehicle.VehicleProfile
import app.navmaster.truck.vehicle.VehicleType
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor

/** A limit found on the route, with where it is along the route and whether this vehicle passes. */
data class RouteLimit(
    val kind: String,
    val value: Double,
    val raw: String?,
    val name: String?,
    val conditional: String?,
    val alongM: Double,
    val lat: Double,
    val lon: Double,
    val blocking: Boolean,
) {
  /** Short text for the banner: "Altezza max", "Divieto mezzi pesanti" ... */
  val label: String
    get() =
        when (kind) {
          "maxheight" -> "Altezza max"
          "maxwidth" -> "Larghezza max"
          "maxlength" -> "Lunghezza max"
          "maxweight" -> "Peso max"
          "maxaxleload" -> "Peso per asse max"
          "hgv" -> "Divieto mezzi pesanti"
          "bus" -> "Divieto autobus"
          "motorhome" -> "Divieto camper"
          "hazmat" -> "Divieto merci pericolose"
          "adr_tunnel" -> "Galleria ADR cat. ${raw ?: ""}"
          else -> "Divieto a orario"
        }

  /** The value shown inside the round sign: "3,8m", "7,5t", or nothing for a ban. */
  val signValue: String?
    get() =
        when (kind) {
          "maxheight", "maxwidth", "maxlength" -> fmt(value) + "m"
          "maxweight", "maxaxleload" -> fmt(value) + "t"
          "adr_tunnel" -> raw
          else -> null
        }

  private fun fmt(v: Double): String =
      if (v == floor(v)) v.toInt().toString() else String.format(java.util.Locale.ITALY, "%.1f", v)
}

/**
 * Reads limiti.sqlite of every installed country (built from OSM by data/build_limits.py) and finds
 * every limit lying on a route. The routing engine already avoids what the vehicle cannot pass;
 * this is what lets the driver see and hear about it in advance, and what catches data the engine
 * cannot use (ADR tunnel categories, restrictions valid only at some hours).
 */
class LimitsIndex(regions: RegionManager) {
  private val dbs = RegionDbs(regions) { it.limits }

  fun scan(m: RouteMatcher, vehicle: VehicleProfile, weightT: Double, departure: LocalDateTime = LocalDateTime.now(),
           avgSpeedMs: Double = 16.0): List<RouteLimit> {
    if (m.route.size < 2) return emptyList()
    val started = System.currentTimeMillis()
    val found = HashMap<String, RouteLimit>()
    dbs.perCells(m.cells) { db, inList ->
      val out = mutableListOf<RouteLimit>()
      db.rawQuery(
              "SELECT DISTINCT l.id, l.kind, l.value, l.raw, l.cond, l.name, l.pts FROM cells c " +
                  "JOIN limits l ON l.id = c.lid WHERE c.cell IN ($inList)",
              null,
          )
          .use { c ->
            while (c.moveToNext()) {
              val pts = RouteMatcher.parsePts(c.getString(6))
              val (lo, _) = m.span(pts, 9.0) ?: continue
              val p = pts.minByOrNull { pt -> m.nearest(pt)?.first ?: 1e9 } ?: continue
              val kind = c.getString(1)
              val value = c.getDouble(2)
              val cond = c.getString(4)
              val at = departure.plusSeconds((lo / avgSpeedMs).toLong())
              out += RouteLimit(kind, value, c.getString(3), c.getString(5), cond, lo, p.lat, p.lng,
                  blocks(kind, value, vehicle, weightT) && (cond == null || conditionalActive(cond, at)))
            }
          }
      out
    }.forEach { l -> found.putIfAbsent("${l.kind}:${(l.alongM / 30).toLong()}", l) }
    val out = mutableListOf<RouteLimit>()
    for (l in found.values.sortedBy { it.alongM }) {
      if (out.any { it.kind == l.kind && abs(it.alongM - l.alongM) < 60 }) continue
      if (!relevant(l, vehicle)) continue
      out += l
    }
    Log.i("NavMasterLimits", "scan: ${m.route.size} pts, ${m.cells.size} cells, ${out.size} limits " +
        "(${out.count { it.blocking }} blocking) in ${System.currentTimeMillis() - started} ms")
    return out
  }

  private fun relevant(l: RouteLimit, v: VehicleProfile): Boolean =
      when (l.kind) {
        "hgv" -> v.isHgv
        "bus" -> v.type == VehicleType.AUTOBUS
        "motorhome" -> v.type == VehicleType.CAMPER
        "hazmat" -> v.hazmat
        "adr_tunnel" -> v.adr != AdrTunnel.NONE
        else -> true
      }

  private fun blocks(kind: String, value: Double, v: VehicleProfile, weightT: Double): Boolean =
      when (kind) {
        "maxheight" -> v.heightM > value
        "maxwidth" -> v.widthM > value
        "maxlength" -> v.lengthM > value
        "maxweight" -> weightT > value
        "maxaxleload" -> v.axleLoadT > value
        "hgv" -> v.isHgv
        "bus" -> v.type == VehicleType.AUTOBUS
        "motorhome" -> v.type == VehicleType.CAMPER
        "hazmat" -> v.hazmat
        // a tunnel of category X is closed to loads with tunnel code X or stricter (B < C < D < E)
        "adr_tunnel" -> v.adr != AdrTunnel.NONE && adrRank(v.adr) <= value.toInt()
        else -> false
      }

  private fun adrRank(a: AdrTunnel): Int =
      when (a) {
        AdrTunnel.B -> 2
        AdrTunnel.C -> 3
        AdrTunnel.D -> 4
        AdrTunnel.E -> 5
        AdrTunnel.NONE -> 99
      }

  companion object {
    private val DAYS = mapOf("Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "We" to DayOfWeek.WEDNESDAY,
        "Th" to DayOfWeek.THURSDAY, "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY)

    /**
     * Whether a restriction written as "hgv=no @ (Mo-Fr 07:00-19:00)" applies at [at]. Only the
     * common day/hour forms are understood; anything else counts as always valid (safe side).
     */
    fun conditionalActive(cond: String, at: LocalDateTime): Boolean {
      val m = Regex("@\\s*\\(?([^)]*)\\)?").find(cond) ?: return true
      val spec = m.groupValues[1].trim()
      if (spec.isEmpty()) return true
      for (part in spec.split(';').map { it.trim() }) {
        var days: Set<DayOfWeek>? = null
        var rest = part
        val dm = Regex("^((?:Mo|Tu|We|Th|Fr|Sa|Su)(?:\\s*[-,]\\s*(?:Mo|Tu|We|Th|Fr|Sa|Su))*)\\s*").find(part)
        if (dm != null) {
          days = parseDays(dm.groupValues[1])
          rest = part.substring(dm.range.last + 1).trim()
        }
        if (days != null && at.dayOfWeek !in days) continue
        if (rest.isEmpty()) return true
        val hm = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").findAll(rest).toList()
        if (hm.isEmpty()) return true
        val minute = at.hour * 60 + at.minute
        for (h in hm) {
          val from = h.groupValues[1].toInt() * 60 + h.groupValues[2].toInt()
          val to = h.groupValues[3].toInt() * 60 + h.groupValues[4].toInt()
          if (if (from <= to) minute in from until to else minute >= from || minute < to) return true
        }
      }
      return false
    }

    private fun parseDays(s: String): Set<DayOfWeek> {
      val out = mutableSetOf<DayOfWeek>()
      for (piece in s.split(',')) {
        val r = piece.split('-').map { it.trim() }
        val a = DAYS[r[0]] ?: continue
        val b = if (r.size > 1) DAYS[r[1]] ?: a else a
        var d = a
        while (true) {
          out += d
          if (d == b) break
          d = d.plus(1)
        }
      }
      return out
    }
  }
}
