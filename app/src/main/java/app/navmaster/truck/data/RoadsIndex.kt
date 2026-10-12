package app.navmaster.truck.data

import android.util.Log
import app.navmaster.truck.core.Geo
import kotlin.math.abs
import uniffi.ferrostar.GeographicCoordinate

/**
 * The roads of limiti.sqlite (table «roads», data/build_roads.py) along a route computed online:
 * for a route of openrouteservice guided without the country's graph on the tablet (OrsGuide),
 * which road each piece of the route runs on and in which direction, so the guidance has the speed
 * limits, the lanes and their arrows, the road class and the way ids as offline.
 */
class RoadsIndex(regions: RegionManager) {
  private val dbs = RegionDbs(regions) { it.limits }

  data class Road(
      val osm: Long, val hw: String, val ms: Double?, val msf: Double?, val msb: Double?, val msHgv: Double?,
      val oneway: Int, val lanes: Int?, val lanesF: Int?, val lanesB: Int?,
      val turnLanes: String?, val turnLanesF: String?, val turnLanesB: String?,
      val roundabout: Boolean, val toll: Boolean, val tunnel: Boolean, val bridge: Boolean,
      val ref: String?, val name: String?, val pts: List<GeographicCoordinate>,
  ) {
    /** The speed limit going [forward] along the way (km/h; 0 = none), null when not known. */
    fun limit(forward: Boolean, heavy: Boolean): Double? {
      val base = (if (forward) msf else msb) ?: ms
      if (heavy && msHgv != null && msHgv > 0) return if (base == null || base == 0.0) msHgv else minOf(base, msHgv)
      return base
    }

    /** The turn lanes going [forward] ("left|through|right"), null when not mapped. */
    fun turns(forward: Boolean): String? = when {
      oneway != 0 -> if (oneway < 0) turnLanes ?: turnLanesB else turnLanes ?: turnLanesF
      forward -> turnLanesF
      else -> turnLanesB
    }

    /** The lanes going [forward]. */
    fun laneCount(forward: Boolean): Int = when {
      oneway != 0 -> lanes ?: 1
      forward -> lanesF ?: lanes?.let { (it + 1) / 2 } ?: 1
      else -> lanesB ?: lanes?.let { it / 2 } ?: 1
    }

    val isLink: Boolean get() = hw.endsWith("_link")
    val roadClass: String get() = hw.removeSuffix("_link").let { if (it == "living_street") "residential" else if (it == "service" || it == "road") "service_other" else it }
  }

  /** The road of one piece of the route and the direction it is driven. */
  data class On(val road: Road, val forward: Boolean)

  /** The table is there in some downloaded package (light or full). */
  fun available(): Boolean = dbs.all().any { (_, db) ->
    runCatching { db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='roads'", null).use { it.moveToFirst() } }.getOrDefault(false)
  }

  /** The roads within [radiusM] of a point (the junction view of a route guided online). */
  fun near(lat: Double, lon: Double, radiusM: Double): List<Road> {
    val dLat = radiusM / 110_540.0
    val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)))
    val cells = HashSet<Long>()
    var la = lat - dLat
    while (la <= lat + dLat + Geo.CELL) {
      var lo = lon - dLon
      while (lo <= lon + dLon + Geo.CELL) { cells += Geo.cell(la, lo); lo += Geo.CELL }
      la += Geo.CELL
    }
    return dbs.perCells(cells) { db, inList -> readRoads(db, inList) }.distinctBy { it.osm to it.pts.size }
  }

  private fun readRoads(db: android.database.sqlite.SQLiteDatabase, inList: String): List<Road> {
    val list = mutableListOf<Road>()
    runCatching {
      db.rawQuery(
          "SELECT DISTINCT r.osm, r.hw, r.ms, r.msf, r.msb, r.mshgv, r.ow, r.ln, r.lnf, r.lnb, r.tl, r.tlf, r.tlb, r.rb, r.toll, r.tun, r.br, " +
              "r.ref, r.name, r.pts FROM road_cells c JOIN roads r ON r.id = c.rid WHERE c.cell IN ($inList)", null).use { c ->
        fun d(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
        fun n(i: Int) = if (c.isNull(i)) null else c.getInt(i)
        fun s(i: Int) = if (c.isNull(i)) null else c.getString(i)
        while (c.moveToNext()) {
          list += Road(c.getLong(0), c.getString(1) ?: "", d(2), d(3), d(4), d(5), c.getInt(6), n(7), n(8), n(9), s(10), s(11), s(12),
              c.getInt(13) == 1, c.getInt(14) == 1, c.getInt(15) == 1, c.getInt(16) == 1, s(17), s(18),
              RouteMatcher.decodePolyline(c.getString(19) ?: ""))
        }
      }
    }
    return list
  }

  /**
   * For every piece i → i+1 of [line], the road it runs on (null where none is known): the road
   * whose line is within 12 m of most of the piece's points, going the same way (a oneway road the
   * other way and the other carriageway are not taken).
   */
  fun along(line: List<GeographicCoordinate>): Array<On?> {
    val out = arrayOfNulls<On>(maxOf(0, line.size - 1))
    if (line.size < 2) return out
    val started = System.currentTimeMillis()
    val m = RouteMatcher(line)
    val roads = dbs.perCells(m.cells) { db, inList -> readRoads(db, inList) }.distinctBy { it.osm to it.pts.size }
    if (roads.isEmpty()) return out
    // the road segments by cell
    val byCell = HashMap<Long, MutableList<Pair<Int, Int>>>()
    for ((ri, r) in roads.withIndex()) {
      for (k in 0 until r.pts.size - 1) {
        val a = r.pts[k]
        val b = r.pts[k + 1]
        val steps = (Geo.dist(a, b) / 200.0).toInt() + 1
        var last = Long.MIN_VALUE
        for (s in 0..steps) {
          val t = s.toDouble() / steps
          val cell = Geo.cell(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
          if (cell == last) continue
          last = cell
          val list = byCell.getOrPut(cell) { mutableListOf() }
          if (list.isEmpty() || list.last() != ri to k) list += ri to k
        }
      }
    }
    for (i in 0 until line.size - 1) {
      val a = line[i]
      val b = line[i + 1]
      val len = Geo.dist(a, b)
      if (len < 0.5) continue
      val h = Geo.bearing(a, b)
      val n = (len / 40.0).toInt().coerceIn(1, 60)
      val votes = HashMap<Int, Pair<Int, Boolean>>()
      for (s in 0 until n) {
        val t = (s + 0.5) / n
        val p = GeographicCoordinate(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        var best = 12.0
        var pick = -1
        var fwd = true
        for (dy in -1..1) for (dx in -1..1) {
          val cands = byCell[Geo.cell(p.lat + dy * Geo.CELL, p.lng + dx * Geo.CELL)] ?: continue
          for ((ri, k) in cands) {
            val r = roads[ri]
            val (d, _) = Geo.pointToSegment(p, r.pts[k], r.pts[k + 1])
            if (d >= best) continue
            val rh = Geo.bearing(r.pts[k], r.pts[k + 1])
            val diff = abs(Geo.angleDiff(h, rh))
            val forward = diff < 50
            val backward = diff > 130
            if (!forward && !backward) continue
            if (r.oneway > 0 && !forward || r.oneway < 0 && !backward) continue
            best = d
            pick = ri
            fwd = forward
          }
        }
        if (pick >= 0) votes[pick] = ((votes[pick]?.first ?: 0) + 1) to fwd
      }
      val win = votes.maxByOrNull { it.value.first } ?: continue
      if (win.value.first * 2 < n) continue
      out[i] = On(roads[win.key], win.value.second)
    }
    Log.i(TAG, "roads along the route: ${roads.size} near, ${out.count { it != null }}/${out.size} pieces matched in ${System.currentTimeMillis() - started} ms")
    return out
  }

  companion object {
    private const val TAG = "NavMasterRoads"
  }
}
