package app.navmaster.truck.limits

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.vehicle.VehicleProfile
import app.navmaster.truck.vehicle.VehicleType
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
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
          else -> "Divieto a orario"
        }

  /** The value shown inside the round sign: "3,8m", "7,5t", or nothing for a ban. */
  val signValue: String?
    get() =
        when (kind) {
          "maxheight", "maxwidth", "maxlength" -> fmt(value) + "m"
          "maxweight", "maxaxleload" -> fmt(value) + "t"
          else -> null
        }

  private fun fmt(v: Double): String =
      if (v == floor(v)) v.toInt().toString() else String.format(java.util.Locale.ITALY, "%.1f", v)
}

/**
 * Reads limiti.sqlite (built from OSM by data/build_limits.py) and finds every limit lying on a
 * route. The routing engine already avoids what the vehicle cannot pass; this is what lets the
 * driver see and hear about it in advance, and what catches data the engine cannot use.
 */
class LimitsIndex(private val regions: RegionManager) {
  private var db: SQLiteDatabase? = null
  private var dbPath: String? = null

  @Synchronized
  private fun open(): SQLiteDatabase? {
    val file: File = regions.active()?.limits ?: return null
    if (!file.exists()) return null
    if (file.absolutePath != dbPath) {
      db?.close()
      db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
      dbPath = file.absolutePath
    }
    return db
  }

  fun scan(route: List<GeographicCoordinate>, vehicle: VehicleProfile, weightT: Double): List<RouteLimit> {
    val db = open() ?: return emptyList()
    if (route.size < 2) return emptyList()
    val started = System.currentTimeMillis()

    // cumulative distance of every route vertex, and the route segments falling in each cell
    val cum = DoubleArray(route.size)
    for (i in 1 until route.size) cum[i] = cum[i - 1] + dist(route[i - 1], route[i])
    val segsByCell = HashMap<Long, MutableList<Int>>()
    for (i in 0 until route.size - 1) {
      val a = route[i]
      val b = route[i + 1]
      val steps = (dist(a, b) / 60.0).toInt() + 1
      for (s in 0..steps) {
        val t = s.toDouble() / steps
        val lat = a.lat + (b.lat - a.lat) * t
        val lon = a.lng + (b.lng - a.lng) * t
        for (dy in -1..1) for (dx in -1..1) {
          segsByCell.getOrPut(cell(lat + dy * CELL, lon + dx * CELL)) { mutableListOf() }.let {
            if (it.isEmpty() || it.last() != i) it += i
          }
        }
      }
    }

    // candidate limits from the cells the route crosses
    val found = HashMap<Long, RouteLimit>()
    segsByCell.keys.chunked(400).forEach { chunk ->
      val inList = chunk.joinToString(",")
      db.rawQuery(
              "SELECT DISTINCT l.id, l.kind, l.value, l.raw, l.cond, l.name, l.pts FROM cells c " +
                  "JOIN limits l ON l.id = c.lid WHERE c.cell IN ($inList)",
              null,
          )
          .use { c ->
            while (c.moveToNext()) {
              val id = c.getLong(0)
              if (found.containsKey(id)) continue
              val pts = parsePts(c.getString(6))
              val hit = matchOnRoute(pts, route, cum, segsByCell) ?: continue
              val kind = c.getString(1)
              val value = c.getDouble(2)
              found[id] =
                  RouteLimit(
                      kind = kind,
                      value = value,
                      raw = c.getString(3),
                      name = c.getString(5),
                      conditional = c.getString(4),
                      alongM = hit.first,
                      lat = hit.second.lat,
                      lon = hit.second.lng,
                      blocking = blocks(kind, value, vehicle, weightT),
                  )
            }
          }
    }
    // the same bridge is often mapped on both carriageways: keep one per kind every 60 m
    val out = mutableListOf<RouteLimit>()
    for (l in found.values.sortedBy { it.alongM }) {
      if (out.any { it.kind == l.kind && abs(it.alongM - l.alongM) < 60 }) continue
      if (!relevant(l, vehicle)) continue
      out += l
    }
    Log.i("NavMasterLimits", "scan: ${route.size} pts, ${segsByCell.size} cells, ${out.size} limits " +
        "(${out.count { it.blocking }} blocking) in ${System.currentTimeMillis() - started} ms")
    return out
  }

  private fun relevant(l: RouteLimit, v: VehicleProfile): Boolean =
      when (l.kind) {
        "hgv" -> v.type == VehicleType.CAMION
        "bus" -> v.type == VehicleType.AUTOBUS
        "motorhome" -> v.type == VehicleType.CAMPER
        "hazmat" -> v.hazmat
        else -> true
      }

  private fun blocks(kind: String, value: Double, v: VehicleProfile, weightT: Double): Boolean =
      when (kind) {
        "maxheight" -> v.heightM > value
        "maxwidth" -> v.widthM > value
        "maxlength" -> v.lengthM > value
        "maxweight" -> weightT > value
        "maxaxleload" -> v.axleLoadT > value
        "hgv" -> v.type == VehicleType.CAMION
        "bus" -> v.type == VehicleType.AUTOBUS
        "motorhome" -> v.type == VehicleType.CAMPER
        "hazmat" -> v.hazmat
        else -> false
      }

  /**
   * Distance along the route of the first point of a limit that lies on it. A way counts when two of
   * its points (or its only point) are within a few metres of the route, so a road merely crossing
   * or running close to the route is not taken for it.
   */
  private fun matchOnRoute(
      pts: List<GeographicCoordinate>,
      route: List<GeographicCoordinate>,
      cum: DoubleArray,
      segsByCell: Map<Long, List<Int>>,
  ): Pair<Double, GeographicCoordinate>? {
    val need = if (pts.size <= 1) 1 else 2
    var hits = 0
    var first: Pair<Double, GeographicCoordinate>? = null
    for (p in pts) {
      val segs = segsByCell[cell(p.lat, p.lng)] ?: continue
      var best = Double.MAX_VALUE
      var bestAlong = 0.0
      for (i in segs) {
        val (d, t) = pointToSegment(p, route[i], route[i + 1])
        if (d < best) {
          best = d
          bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t
        }
      }
      if (best <= MATCH_M) {
        hits++
        if (first == null || bestAlong < first.first) first = bestAlong to p
      }
    }
    return if (hits >= need) first else null
  }

  private fun parsePts(s: String): List<GeographicCoordinate> =
      s.split(';').mapNotNull { pair ->
        val k = pair.indexOf(',')
        if (k <= 0) null else GeographicCoordinate(pair.substring(0, k).toDouble(), pair.substring(k + 1).toDouble())
      }

  companion object {
    private const val CELL = 0.01
    private const val MATCH_M = 9.0

    fun cell(lat: Double, lon: Double): Long =
        floor(lat / CELL).toLong() * 100000L + floor(lon / CELL).toLong() + 50000L

    fun dist(a: GeographicCoordinate, b: GeographicCoordinate): Double {
      val k = cos(Math.toRadians((a.lat + b.lat) / 2))
      return hypot((b.lng - a.lng) * 111320.0 * k, (b.lat - a.lat) * 110540.0)
    }

    /** Distance in metres from p to the segment ab, and where along ab (0..1) the closest point is. */
    fun pointToSegment(p: GeographicCoordinate, a: GeographicCoordinate, b: GeographicCoordinate): Pair<Double, Double> {
      val k = cos(Math.toRadians(a.lat))
      val ax = 0.0
      val ay = 0.0
      val bx = (b.lng - a.lng) * 111320.0 * k
      val by = (b.lat - a.lat) * 110540.0
      val px = (p.lng - a.lng) * 111320.0 * k
      val py = (p.lat - a.lat) * 110540.0
      val len2 = bx * bx + by * by
      val t = if (len2 <= 0.0) 0.0 else (((px - ax) * bx + (py - ay) * by) / len2).coerceIn(0.0, 1.0)
      return hypot(px - bx * t, py - by * t) to t
    }
  }
}
