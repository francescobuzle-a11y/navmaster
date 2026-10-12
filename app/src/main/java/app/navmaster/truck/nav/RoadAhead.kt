package app.navmaster.truck.nav

import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.RoadsIndex
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import uniffi.ferrostar.GeographicCoordinate

/**
 * A point ahead along the road being driven, when the country's graph is not on the tablet
 * (the roads of the light package, data/build_roads.py, or nothing at all): where the driver who
 * left the planned route is sent through when he chooses to go on along this road.
 */
object RoadAhead {
  /**
   * [lat, lon, bearing] about [metres] ahead of ([lat], [lon]) along the road of [roads] under
   * the vehicle (direction [heading], NaN = unknown), going straight on from road to road where
   * one ends at the start of another; null when no road is within 60 m.
   */
  fun along(roads: List<RoadsIndex.Road>, lat: Double, lon: Double, heading: Double, metres: Double): DoubleArray? {
    // the road under the vehicle, driven its way
    var best = 60.0
    var pick: RoadsIndex.Road? = null
    var pickK = 0
    var pickFwd = true
    var pickT = 0.0
    for (r in roads) {
      for (k in 0 until r.pts.size - 1) {
        val a = r.pts[k]
        val b = r.pts[k + 1]
        if (abs(a.lat - lat) > 0.002 || abs(a.lng - lon) > 0.003) continue
        val (d, t) = Geo.pointToSegment(GeographicCoordinate(lat, lon), a, b)
        if (d >= best) continue
        val rb = Geo.bearing(a, b)
        val fwd = heading.isNaN() || abs(Geo.angleDiff(heading, rb)) <= 90
        if (r.oneway > 0 && !fwd || r.oneway < 0 && fwd) continue
        best = d
        pick = r
        pickK = k
        pickFwd = fwd
        pickT = t
      }
    }
    val road = pick ?: return null
    // walk along it (and on, through the roads that start where it ends)
    var pts = if (pickFwd) road.pts else road.pts.asReversed()
    var k = if (pickFwd) pickK else road.pts.size - 2 - pickK
    val a0 = pts[k]
    val b0 = pts[k + 1]
    var pLat = a0.lat + (b0.lat - a0.lat) * (if (pickFwd) pickT else 1 - pickT)
    var pLon = a0.lng + (b0.lng - a0.lng) * (if (pickFwd) pickT else 1 - pickT)
    var left = metres
    var lastBearing = Geo.bearing(a0, b0)
    var cur = road
    var hops = 0
    while (hops++ < 40) {
      for (i in k + 1 until pts.size) {
        val d = Geo.dist(pLat, pLon, pts[i].lat, pts[i].lng)
        if (d >= left) return doubleArrayOf(pLat + (pts[i].lat - pLat) * left / d, pLon + (pts[i].lng - pLon) * left / d, Geo.bearing(pLat, pLon, pts[i].lat, pts[i].lng))
        if (d > 1) lastBearing = Geo.bearing(pLat, pLon, pts[i].lat, pts[i].lng)
        left -= d
        pLat = pts[i].lat
        pLon = pts[i].lng
      }
      // the next road: one that starts (or, two-way, ends) here and turns least; the same name first
      var next: RoadsIndex.Road? = null
      var nextFwd = true
      var bestTurn = 1e9
      for (r in roads) {
        if (r === cur || r.pts.size < 2) continue
        for (fwd in listOf(true, false)) {
          if (r.oneway > 0 && !fwd || r.oneway < 0 && fwd) continue
          val q = if (fwd) r.pts else r.pts.asReversed()
          if (Geo.dist(pLat, pLon, q[0].lat, q[0].lng) > 3) continue
          var j = 1
          while (j < q.size - 1 && Geo.dist(q[0], q[j]) < 8) j++
          var turn = abs(Geo.angleDiff(lastBearing, Geo.bearing(q[0], q[j])))
          if (!cur.name.isNullOrEmpty() && cur.name == r.name) turn -= 25
          if (turn < bestTurn) {
            bestTurn = turn
            next = r
            nextFwd = fwd
          }
        }
      }
      val n = next ?: return doubleArrayOf(pLat, pLon, lastBearing)
      if (bestTurn > 70) return doubleArrayOf(pLat, pLon, lastBearing)
      cur = n
      pts = if (nextFwd) n.pts else n.pts.asReversed()
      k = 0
    }
    return doubleArrayOf(pLat, pLon, lastBearing)
  }

  private fun Geo.bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
      bearing(GeographicCoordinate(lat1, lon1), GeographicCoordinate(lat2, lon2))

  /** [lat, lon, bearing] [metres] straight ahead in direction [heading] (degrees). */
  fun straight(lat: Double, lon: Double, heading: Double, metres: Double): DoubleArray {
    val h = Math.toRadians(heading)
    val dLat = metres * cos(h) / 111_195.0
    val dLon = metres * sin(h) / (111_195.0 * cos(Math.toRadians(lat)))
    return doubleArrayOf(lat + dLat, lon + dLon, heading)
  }
}
