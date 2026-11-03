package app.navmaster.truck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.RoadsIndex
import uniffi.ferrostar.GeographicCoordinate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The roundabout of a manoeuvre as it really is, for the glyph of the green bar: where the route
 * enters it and leaves it, every other road that leaves it (from the graph), and the direction of
 * circulation (from the route itself). Bearings are compass degrees seen from the centre of the
 * ring (0 = north, 90 = east); the arms' directions are the compass headings of the road going
 * away from the ring.
 */
data class RoundaboutShape(
    val entryAt: Double,
    val entryDir: Double,
    val exitAt: Double,
    val exitDir: Double,
    /** The other roads: where they leave the ring, and their direction going away. */
    val arms: List<Pair<Double, Double>>,
    /** Counter-clockwise seen from above (traffic on the right). */
    val ccw: Boolean,
    /** Degrees driven on the ring, from the entry to the exit (0 … 360). */
    val sweep: Double,
) {
  companion object {
    private fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0
    private fun diff(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    /**
     * The roundabout the route enters at about [entryM] metres, or null when the route does not
     * drive on a ring there (the roads of the ring are marked roundabout in the graph).
     */
    fun of(r: JvRoute, entryM: Double, roadsNear: (Double, Double, Double) -> List<RoadsIndex.Road> = jvRoadsNear): RoundaboutShape? {
      val first = r.edges.indexOfFirst { it.roundabout && it.endM > entryM - 20 && it.startM < entryM + 40 }
      if (first < 0) return null
      var last = first
      while (last + 1 < r.edges.size && r.edges[last + 1].roundabout && r.edges[last + 1].startM - r.edges[last].endM < 1.0) last++
      val ringStart = r.edges[first].startM
      val ringEnd = r.edges[last].endM
      if (ringEnd - ringStart < 3 || ringEnd - ringStart > 2_000) return null

      // the ring as driven, every 3 m: the turn it makes says the direction of circulation
      val driven = ArrayList<GeographicCoordinate>()
      var m = ringStart
      while (m < ringEnd) { driven += r.pointAt(m); m += 3.0 }
      driven += r.pointAt(ringEnd)
      var turn = 0.0
      for (i in 1 until driven.size - 1) {
        val h0 = Geo.bearing(driven[i - 1], driven[i]); val h1 = Geo.bearing(driven[i], driven[i + 1])
        turn += ((h1 - h0 + 540.0) % 360.0) - 180.0
      }
      val inH = Geo.bearing(r.pointAt((ringStart - 25).coerceAtLeast(0.0)), r.pointAt(ringStart))
      val outH = Geo.bearing(r.pointAt(ringEnd), r.pointAt((ringEnd + 25).coerceAtMost(r.length)))
      // a short ring piece: the turn of the route from the road before to the road after
      if (driven.size < 4) turn = ((outH - inH + 540.0) % 360.0) - 180.0
      val ccw = turn < 0
      val entryP = r.pointAt(ringStart)
      val exitP = r.pointAt(ringEnd)
      val entryDir = norm(inH + 180.0)

      // the whole ring from the graph: the roads marked roundabout joined to the ones driven
      val mid = driven[driven.size / 2]
      val near = runCatching { roadsNear(mid.lat, mid.lng, 250.0) }.getOrDefault(emptyList())
      val ringIds = (first..last).map { r.edges[it].wayId }.filter { it != 0L }.toMutableSet()
      val ringRoads = near.filter { it.roundabout && it.osm in ringIds }.toMutableList()
      var grew = ringRoads.isNotEmpty()
      while (grew) {
        grew = false
        val ends = ringRoads.flatMap { listOf(it.pts.first(), it.pts.last()) }
        for (rd in near) if (rd.roundabout && rd !in ringRoads &&
            (ends.any { e -> Geo.dist(e, rd.pts.first()) < 2.0 || Geo.dist(e, rd.pts.last()) < 2.0 })) { ringRoads += rd; grew = true }
      }
      val ringPts = ringRoads.flatMap { it.pts }
      if (ringRoads.isEmpty() || ringPts.size < 5) {
        // no ring in the graph's index: the entry and the exit from the route's turn only
        // the roads arrive and leave across the ring: the entry is behind the road arriving
        val sweep = abs(turn).coerceIn(0.0, 360.0)
        val entryAt = norm(inH + 180.0)
        val exitAt = norm(entryAt + if (ccw) -sweep else sweep)
        return RoundaboutShape(entryAt, entryDir, exitAt, norm(outH), emptyList(), ccw, sweep)
      }
      // the centre: the middle of the ring's line (points every 2 m, not the vertices)
      val lat0 = mid.lat
      val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 110_540.0
      fun xy(p: GeographicCoordinate) = doubleArrayOf((p.lng - mid.lng) * kx, (p.lat - mid.lat) * ky)
      var sx = 0.0; var sy = 0.0; var sw = 0.0
      for (rd in ringRoads) for (i in 1 until rd.pts.size) {
        val a = xy(rd.pts[i - 1]); val b = xy(rd.pts[i])
        val len = hypot(b[0] - a[0], b[1] - a[1])
        sx += (a[0] + b[0]) / 2 * len; sy += (a[1] + b[1]) / 2 * len; sw += len
      }
      if (sw <= 0) return null
      val cx = sx / sw; val cy = sy / sw
      fun bearingFromCentre(p: GeographicCoordinate): Double { val v = xy(p); return norm(Math.toDegrees(atan2(v[0] - cx, v[1] - cy))) }
      fun radiusOf(p: GeographicCoordinate): Double { val v = xy(p); return hypot(v[0] - cx, v[1] - cy) }
      val radius = ringPts.map { radiusOf(it) }.average()
      val entryAt = bearingFromCentre(entryP)
      val exitAt = bearingFromCentre(exitP)
      val sweep = if (abs(turn) > 300 && diff(entryAt, exitAt) < 25) abs(turn).coerceAtMost(360.0)
          else if (ccw) norm(entryAt - exitAt) else norm(exitAt - entryAt)

      // the other roads leaving the ring: their vertex on the ring and the direction away from it
      val ringVerts = ringRoads.flatMap { it.pts }
      val arms = ArrayList<Pair<Double, Double>>()
      for (rd in near) {
        if (rd.roundabout || rd.pts.size < 2) continue
        for (k in rd.pts.indices) {
          val p = rd.pts[k]
          if (ringVerts.none { Geo.dist(it, p) < 2.5 }) continue
          for (dir in listOf(-1, 1)) {
            if (k + dir !in rd.pts.indices) continue
            val q = walk(rd.pts, k, dir, 20.0)
            // going away from the ring, not across its island
            if (radiusOf(q) < radius + 3) continue
            arms += bearingFromCentre(p) to Geo.bearing(p, q)
          }
        }
      }
      // one arm per road: the entry and exit roads and the two carriageways of a dual road are one arm
      val merged = ArrayList<Pair<Double, Double>>()
      for (arm in arms.sortedBy { it.first }) {
        if (diff(arm.first, entryAt) < 22 || diff(arm.first, exitAt) < 22) continue
        if (merged.any { diff(it.first, arm.first) < 22 }) continue
        merged += arm
      }
      return RoundaboutShape(entryAt, entryDir, exitAt, norm(outH), merged, ccw, sweep)
    }

    /** The point [dist] metres along [pts] from vertex [k], going by [dir] (−1 / 1). */
    private fun walk(pts: List<GeographicCoordinate>, k: Int, dir: Int, dist: Double): GeographicCoordinate {
      var left = dist
      var i = k
      while (i + dir in pts.indices) {
        val a = pts[i]; val b = pts[i + dir]
        val d = Geo.dist(a, b)
        if (d >= left && d > 0) {
          val t = left / d
          return GeographicCoordinate(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        }
        left -= d
        i += dir
      }
      return pts[i]
    }
  }
}

/**
 * The glyph of a roundabout as it is: the ring, its roads at their real places and directions,
 * the entry at the bottom, the way round in the direction of circulation and the exit taken bright
 * with its arrow; the other roads faint.
 */
fun DrawScope.drawRoundabout(s: RoundaboutShape, color: Color) {
  val side = size.minDimension
  val c = Offset(size.width / 2f, size.height * 0.46f)
  val r = side * 0.22f
  val armLen = side * 0.26f
  val rot = 180.0 - s.entryAt
  fun unit(bearing: Double): Offset {
    val b = Math.toRadians(bearing + rot)
    return Offset(sin(b).toFloat(), -cos(b).toFloat())
  }
  fun onRing(bearing: Double) = c + unit(bearing) * r
  val faint = color.copy(alpha = 0.42f)
  val thin = Stroke(width = side * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
  val bold = Stroke(width = side * 0.125f, cap = StrokeCap.Round, join = StrokeJoin.Round)
  // the ring and the other roads
  drawCircle(faint, r, c, style = thin)
  for ((at, dir) in s.arms) {
    val p = onRing(at)
    drawLine(faint, p, p + unit(dir) * armLen, strokeWidth = thin.width, cap = StrokeCap.Round)
  }
  // the way of the route: in, round, out
  val inP = onRing(s.entryAt)
  val inStart = inP + unit(s.entryDir) * (armLen * 1.15f)
  val route = Path().apply {
    moveTo(inStart.x, inStart.y)
    lineTo(inP.x, inP.y)
    val start = (s.entryAt + rot - 90.0).toFloat()
    val sweep = (if (s.ccw) -s.sweep else s.sweep).toFloat()
    arcTo(Rect(c, r), start, sweep, false)
  }
  drawPath(route, color, style = bold)
  val outP = onRing(s.exitAt)
  val u = unit(s.exitDir)
  val tip = outP + u * (armLen * 1.05f)
  val shaftEnd = outP + u * (armLen * 0.55f)
  drawLine(color, outP, shaftEnd, strokeWidth = bold.width, cap = StrokeCap.Round)
  val n = Offset(-u.y, u.x)
  val hw = side * 0.14f
  drawPath(Path().apply {
    moveTo(tip.x, tip.y)
    lineTo(shaftEnd.x + n.x * hw - u.x * side * 0.02f, shaftEnd.y + n.y * hw - u.y * side * 0.02f)
    lineTo(shaftEnd.x - n.x * hw - u.x * side * 0.02f, shaftEnd.y - n.y * hw - u.y * side * 0.02f)
    close()
  }, color)
}

/** The roundabout glyph on its own. */
@Composable
fun RoundaboutGlyph(shape: RoundaboutShape, color: Color, mod: Modifier) {
  Canvas(mod) { drawRoundabout(shape, color) }
}
