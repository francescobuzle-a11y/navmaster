package app.navmaster.truck.nav

import app.navmaster.truck.core.Geo
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import uniffi.ferrostar.GeographicCoordinate

/**
 * What two routes are compared on to tell the same route offered twice: length, time, the engine's
 * roads ([signature], "A1 · A21 · A1", null for a route that is not one of its variants) and the line.
 */
internal class Track(val distanceM: Double, val durationS: Double, val signature: String?, val geometry: List<GeographicCoordinate>)

/**
 * The same route offered twice. The engine's variants are as good as the best route on purpose
 * (rule B: within 1.10× in time and km), so the totals alone say nothing: a variant on another
 * motorway may differ by 2 km and 3 minutes on 1000 km. And "90 % on the same roads" is not enough
 * either: a variant leaves the best route for at least a few km (40 km on a long trip), the rest is
 * the same road. A route is the same only when its totals are the same, its roads are the same (when
 * the engine says them for both) and its line is the same both ways (off the other one for less than
 * 2 % of its length, at most 1 km).
 */
internal object RouteSameness {
  /** Within this distance of the other line a point is on it. */
  private const val NEAR_M = 30.0
  /** The lines are sampled every so many metres. */
  private const val STEP_M = 50.0

  /** Length within 0.4 %, time within 1 %. */
  fun sameTotals(a: Track, b: Track): Boolean =
      Geo.almostSame(a.distanceM, b.distanceM, 0.004) && Geo.almostSame(a.durationS, b.durationS, 0.01)

  fun duplicate(a: Track, b: Track): Boolean {
    if (!sameTotals(a, b)) return false
    val sa = a.signature?.takeIf { it.isNotBlank() }
    val sb = b.signature?.takeIf { it.isNotBlank() }
    if (sa != null && sb != null && sa != sb) return false
    return offTrackM(b.geometry, a.geometry) <= tolerance(b) && offTrackM(a.geometry, b.geometry) <= tolerance(a)
  }

  /** How much of a route may be off the other one and still be the same route (snapping, ends). */
  private fun tolerance(t: Track): Double = min(1_000.0, max(60.0, 0.02 * lengthOf(t)))

  private fun lengthOf(t: Track): Double = if (t.distanceM > 0) t.distanceM else Geo.cumulative(t.geometry).lastOrNull() ?: 0.0

  /** The metres of [line] farther than 30 m from [other] (sampled every 50 m). */
  fun offTrackM(line: List<GeographicCoordinate>, other: List<GeographicCoordinate>): Double {
    if (line.size < 2) return 0.0
    if (other.size < 2) return Geo.cumulative(line).lastOrNull() ?: 0.0
    val grid = Grid(other)
    var off = 0.0
    for (k in 0 until line.size - 1) {
      val a = line[k]
      val b = line[k + 1]
      val len = Geo.dist(a, b)
      if (len <= 0.0) continue
      val n = max(1, kotlin.math.ceil(len / STEP_M).toInt())
      val piece = len / n
      for (i in 0 until n) {
        val t = (i + 0.5) / n
        val p = GeographicCoordinate(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        if (!grid.near(p, NEAR_M)) off += piece
      }
    }
    return off
  }

  /** The segments of a line by cells of about 55 m, to find quickly the ones near a point. */
  private class Grid(private val line: List<GeographicCoordinate>) {
    private val cellLat = 0.0005
    private val cellLon = cellLat / cos(Math.toRadians(line.first().lat)).coerceAtLeast(0.2)
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
      for (k in 0 until line.size - 1) {
        val a = line[k]
        val b = line[k + 1]
        val n = max(1, kotlin.math.ceil(Geo.dist(a, b) / 25.0).toInt())
        var last = Long.MIN_VALUE
        for (i in 0..n) {
          val t = i.toDouble() / n
          val c = key(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
          if (c == last) continue
          last = c
          val list = cells.getOrPut(c) { ArrayList(2) }
          if (list.isEmpty() || list[list.size - 1] != k) list += k
        }
      }
    }

    private fun cx(lat: Double) = floor(lat / cellLat).toLong()
    private fun cy(lon: Double) = floor(lon / cellLon).toLong()
    private fun key(x: Long, y: Long) = (x shl 32) xor (y and 0xffffffffL)
    private fun key(lat: Double, lon: Double) = key(cx(lat), cy(lon))

    fun near(p: GeographicCoordinate, within: Double): Boolean {
      val x = cx(p.lat)
      val y = cy(p.lng)
      for (dx in -1L..1L) for (dy in -1L..1L) {
        val list = cells[key(x + dx, y + dy)] ?: continue
        for (k in list) if (Geo.pointToSegment(p, line[k], line[k + 1]).first <= within) return true
      }
      return false
    }
  }
}
