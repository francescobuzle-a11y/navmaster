package app.navmaster.truck.data

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.navmaster.truck.core.Geo
import java.io.File
import uniffi.ferrostar.GeographicCoordinate

/**
 * A route prepared for fast "is this object on the route, and where?" questions: the route
 * segments are put in the 0.01 degree grid cells the offline databases use.
 */
class RouteMatcher(val route: List<GeographicCoordinate>, reachCells: Int = 1) {
  val cum: DoubleArray = Geo.cumulative(route)
  private val segsByCell = HashMap<Long, MutableList<Int>>()

  init {
    for (i in 0 until route.size - 1) {
      val a = route[i]
      val b = route[i + 1]
      val steps = (Geo.dist(a, b) / 60.0).toInt() + 1
      for (s in 0..steps) {
        val t = s.toDouble() / steps
        val lat = a.lat + (b.lat - a.lat) * t
        val lon = a.lng + (b.lng - a.lng) * t
        for (dy in -reachCells..reachCells) for (dx in -reachCells..reachCells) {
          val list = segsByCell.getOrPut(Geo.cell(lat + dy * Geo.CELL, lon + dx * Geo.CELL)) { mutableListOf() }
          if (list.isEmpty() || list.last() != i) list += i
        }
      }
    }
  }

  val cells: Set<Long>
    get() = segsByCell.keys

  /** Closest point of the route to p: distance from the route and position along it. */
  fun nearest(p: GeographicCoordinate): Pair<Double, Double>? {
    val segs = segsByCell[Geo.cell(p.lat, p.lng)] ?: return null
    var best = Double.MAX_VALUE
    var along = 0.0
    for (i in segs) {
      val (d, t) = Geo.pointToSegment(p, route[i], route[i + 1])
      if (d < best) {
        best = d
        along = cum[i] + (cum[i + 1] - cum[i]) * t
      }
    }
    return best to along
  }

  /**
   * Where a mapped object (point or line) lies on the route: first and last position along the
   * route of its points within [tolM]. A line needs two points on the route, so a road merely
   * crossing the route is not taken for it.
   */
  fun span(pts: List<GeographicCoordinate>, tolM: Double = 10.0): Pair<Double, Double>? {
    val need = if (pts.size <= 1) 1 else 2
    var hits = 0
    var lo = Double.MAX_VALUE
    var hi = -1.0
    for (p in pts) {
      val (d, along) = nearest(p) ?: continue
      if (d <= tolM) {
        hits++
        if (along < lo) lo = along
        if (along > hi) hi = along
      }
    }
    return if (hits >= need) lo to hi else null
  }

  companion object {
    fun parsePts(s: String): List<GeographicCoordinate> =
        s.split(';').mapNotNull { pair ->
          val k = pair.indexOf(',')
          if (k <= 0) null else GeographicCoordinate(pair.substring(0, k).toDouble(), pair.substring(k + 1).toDouble())
        }
  }
}

/** Opens the same database of every installed country once, read only. */
class RegionDbs(private val regions: RegionManager, private val file: (InstalledRegion) -> File) {
  private val open = HashMap<String, SQLiteDatabase>()

  @Synchronized
  fun all(): List<Pair<InstalledRegion, SQLiteDatabase>> {
    val installed = regions.installed.value
    val wanted = installed.map { file(it).absolutePath }.toSet()
    for (k in open.keys.toList()) if (k !in wanted) open.remove(k)?.close()
    return installed.mapNotNull { r ->
      val f = file(r)
      if (!f.exists()) return@mapNotNull null
      val db = open.getOrPut(f.absolutePath) {
        try {
          SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
          Log.w("NavMasterData", "cannot open $f: $e")
          return@mapNotNull null
        }
      }
      r to db
    }
  }

  /** Cells in chunks for "IN (...)" queries. */
  fun <T> perCells(cells: Collection<Long>, block: (SQLiteDatabase, String) -> List<T>): List<T> {
    val out = mutableListOf<T>()
    val dbs = all()
    for (chunk in cells.chunked(400)) {
      val inList = chunk.joinToString(",")
      for ((_, db) in dbs) out += block(db, inList)
    }
    return out
  }
}
