package app.navmaster.truck.limits

import android.util.Log
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.routing.RouteAnalysis
import app.navmaster.truck.vehicle.AdrTunnel
import app.navmaster.truck.vehicle.VehicleProfile
import app.navmaster.truck.vehicle.VehicleType
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import uniffi.ferrostar.GeographicCoordinate

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
    /** OpenStreetMap object ("w123" / "n456"). */
    val osm: String? = null,
    /**
     * How sure the data is (data/build_limits.py): "doppio" two sources agree (two tags, the sign,
     * an official list), "ufficiale" from an official list only, "osm" one OpenStreetMap tag,
     * "dubbio" not plausible or contradictory; null for packages made before the checks.
     */
    val ver: String? = null,
    /** Where it ends along the route: a section under average speed control (Tutor); else [alongM]. */
    val endM: Double = alongM,
) {
  /** A section under average speed control (Tutor, trajectcontrole, ETVM sections). */
  val isSection: Boolean
    get() = kind == "average_speed"

  /**
   * What a speed camera of an official list checks when it is not the speed ("semaforo rosso",
   * "passaggio a livello", "controllo della velocità media"), for the voice; else null.
   */
  val cameraWhat: String?
    get() = name?.takeIf { kind == "speed_camera" && (it.startsWith("semaforo") || it.startsWith("passaggio") || it.startsWith("controllo")) }
        ?.substringBefore(" (")

  /** What the driver is told about the data, or null when there is nothing to say. */
  val check: String?
    get() = when (ver) {
      "dubbio" -> "dato da verificare"
      "doppio" -> if (kind == "speed_camera") "confermato (elenco ufficiale o relazione)" else "confermato da due fonti"
      "ufficiale" -> "elenco ufficiale"
      CameraImport.VER -> "dal tuo elenco"
      else -> null
    }

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
          "speed_camera" -> "Autovelox"
          "average_speed" -> "Tutor"
          else -> "Divieto a orario"
        }

  /** The value shown inside the round sign: "3,8m", "7,5t", or nothing for a ban. */
  val signValue: String?
    get() =
        when (kind) {
          "maxheight", "maxwidth", "maxlength" -> fmt(value) + "m"
          "maxweight", "maxaxleload" -> fmt(value) + "t"
          "adr_tunnel" -> raw
          "speed_camera", "average_speed" -> if (value > 0) value.toInt().toString() else null
          else -> null
        }

  private fun fmt(v: Double): String =
      if (v == floor(v)) v.toInt().toString() else String.format(java.util.Locale.ITALY, "%.1f", v)
}

/**
 * The limits on a route, from the engine's graph (the roads of the route, RouteAnalysis): the
 * engine already avoids what the vehicle cannot pass; this is what lets the driver see and hear
 * about every signed limit in advance, and what warns of a limit on a motorway (told, never a
 * block: the route stays on the motorway).
 */
class LimitsIndex(@Suppress("unused") private val regions: RegionManager) {

  /** The limits signed on the roads of the route [a], in route order. Nothing without the roads. */
  fun scan(m: RouteMatcher, vehicle: VehicleProfile, weightT: Double, departure: LocalDateTime = LocalDateTime.now(),
           avgSpeedMs: Double = 16.0, a: RouteAnalysis? = null): List<RouteLimit> {
    if (a == null || a.edges.isEmpty() || m.route.size < 2) return emptyList()
    val out = mutableListOf<RouteLimit>()
    val seen = HashSet<String>()
    for (e in a.edges) {
      val items = e.limits.entries.map { it.key to it.value } + (if (e.hazmat) listOf("hazmat" to 0.0) else emptyList())
      for ((kind, value) in items) {
        // widths and heights no road vehicle fits: bollards and barriers of paths
        if ((kind == "maxwidth" || kind == "maxheight") && value < 1.6) continue
        // the same way signed with the same limit over several edges: said once, where it starts
        if (!seen.add("${e.wayId}:$kind:$value")) continue
        val lo = e.startM
        val p = a.pointAt(lo)
        // on a motorway a height, width or length limit is told as a warning to check on the spot, never a block
        val sizeOnMotorway = e.roadClass == "motorway" && (kind == "maxheight" || kind == "maxwidth" || kind == "maxlength")
        out += RouteLimit(kind, value, null, null, null, lo, p.lat, p.lng, blocks(kind, value, vehicle, weightT) && !sizeOnMotorway,
            if (e.wayId > 0) "w${e.wayId}" else null, "osm")
      }
    }
    Log.i("NavMasterLimits", "limits on the route: ${out.size} (${out.count { it.blocking }} blocking)")
    return out.sortedBy { it.alongM }
  }

  /** The direction signs of the route (destination tags and motorway junctions of its roads, from the graph). */
  fun signs(m: RouteMatcher, a: RouteAnalysis): List<app.navmaster.truck.routing.RouteSign> {
    val out = mutableListOf<app.navmaster.truck.routing.RouteSign>()
    for (e in a.edges) {
      val sign = e.sign ?: continue
      if (sign.isEmpty) continue
      val last = out.lastOrNull()
      if (last != null && e.startM - last.atM < 40) continue
      out += app.navmaster.truck.routing.RouteSign(e.startM, sign, emptyList())
    }
    return out
  }

  /** The signs over the lanes: not in the graph yet. */
  fun laneSigns(m: RouteMatcher, a: RouteAnalysis): List<app.navmaster.truck.routing.LaneSigns> = emptyList()

  fun cameras(all: List<RouteLimit>): List<RouteLimit> = all.filter { it.kind == "speed_camera" }

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
    /** Countries where traffic keeps left. */
    private val LHT = setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")
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
