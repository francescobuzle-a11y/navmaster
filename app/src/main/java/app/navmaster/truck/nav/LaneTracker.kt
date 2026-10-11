package app.navmaster.truck.nav

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which lane the vehicle is really in, from the satellites: how far to the right of the route's
 * line each fix falls (the line of the map is the middle of the carriageway on a one-way road, the
 * middle of the road on a two-way one), averaged over the last fixes so the lane is read through
 * the noise of the positions (a few metres). The lane is given only when it is steady: with poor
 * fixes, or while changing lane, it is not known (null) and the guidance falls back to the lane the
 * route says to take. Lanes are 3.5 m. Pure Kotlin, tested on the computer.
 */
class LaneTracker {
  private val lat = ArrayDeque<Double>()
  private val wts = ArrayDeque<Double>()
  private var lastAt = 0L

  /** A fix: [lateralM] metres to the right of the route (negative: left), its [accuracyM]. */
  fun feed(lateralM: Double, accuracyM: Double, atNanos: Long) {
    if (accuracyM > 12.0 || abs(lateralM) > 30.0) return
    // a gap of several seconds (a tunnel, fixes left out): the memory starts again
    if (lastAt != 0L && atNanos - lastAt > 6_000_000_000L) reset()
    lastAt = atNanos
    lat.addLast(lateralM)
    wts.addLast(1.0 / (accuracyM * accuracyM).coerceAtLeast(4.0))
    while (lat.size > 10) { lat.removeFirst(); wts.removeFirst() }
  }

  fun reset() {
    lat.clear()
    wts.clear()
    lastAt = 0L
  }

  /** The weighted mean of the lateral offsets (metres to the right), null when too few fixes. */
  fun lateral(): Double? {
    if (lat.size < 5) return null
    var s = 0.0
    var w = 0.0
    for (i in lat.indices) { s += lat.elementAt(i) * wts.elementAt(i); w += wts.elementAt(i) }
    return if (w <= 0) null else s / w
  }

  /** How much the offsets scatter (metres): large while changing lane or with poor fixes. */
  fun spread(): Double? {
    val m = lateral() ?: return null
    var s = 0.0
    for (v in lat) s += (v - m) * (v - m)
    return sqrt(s / lat.size)
  }

  /**
   * The lane the vehicle is in, 0 = the leftmost of the [n] lanes going its way; null when not
   * known well enough. [twoWay]: the route's line is the middle of the road (the vehicle's lanes
   * are on its right, or its left where traffic keeps left, [lht]); else the line is the middle of
   * the carriageway.
   */
  fun lane(n: Int, twoWay: Boolean, lht: Boolean = false): Int? {
    if (n < 1) return null
    val r = lateral() ?: return null
    val sp = spread() ?: return null
    if (sp > 1.6) return null
    val lane = if (!twoWay) {
      // lane i is centred at ((n-1)/2 - i) lanes to the left of the middle
      ((n - 1) / 2.0 + r / LANE_M).roundToInt()
    } else if (!lht) {
      // the lanes of our direction start at the middle line, to the right
      kotlin.math.floor(r / LANE_M).toInt()
    } else {
      kotlin.math.floor(-r / LANE_M).toInt().let { n - 1 - it }
    }
    // clearly off the carriageway (a parallel road, a wide shoulder): not a lane
    if (lane < -1 || lane > n) return null
    return lane.coerceIn(0, n - 1)
  }

  companion object {
    const val LANE_M = 3.5
  }
}
