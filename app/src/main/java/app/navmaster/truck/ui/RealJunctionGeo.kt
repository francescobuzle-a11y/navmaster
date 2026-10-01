package app.navmaster.truck.ui

import android.util.Log
import app.navmaster.truck.data.RoadLine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The junction view drawn from the real junction: the roads of the offline map around it (the
 * carriageway, the ramp with its real bend, the other carriageway, the bridges and the roads
 * nearby) seen in perspective from the cab a little before the gore, with the lanes of the route,
 * the gore where the ramp really leaves, crash barriers and the arrow on the lanes to take. A mild
 * telephoto (depth compressed) keeps the junction readable at a glance.
 */
internal class Pt(val x: Double, val y: Double)

/** A shape on the ground (or a barrier band) in metres around the junction, drawn in order. */
internal class Shape(val kind: Int, val pts: List<Pt>, val z0: Double = 0.0, val z1: Double = 0.0, val layer: Int = 0)

internal class RealJv(
    val route: List<Pt>,
    val rc: DoubleArray,
    /** Metres of the whole route at [route]'s first point. */
    val start: Double,
    val nodeS: Double,
    val node: Pt,
    val branch: List<Pt>,
    val bc: DoubleArray,
    /** Metres after the node where the two roads are apart (the nose of the gore). */
    val split: Double,
    val side: Int,
    val halfIn: Double,
    val actOff: Double,
    val takeBranch: Boolean,
    val shapes: List<Shape>,
) {
  var cacheKey = ""
  var cache: Any? = null
  var hills: androidx.compose.ui.graphics.Path? = null
  /** The shapes layer by layer, kind by kind (the order they are drawn in). */
  val drawOrder: List<Shape> by lazy { shapes.sortedWith(compareBy({ it.layer }, { it.kind })) }
  var hillsKey = ""

  fun along(s: Double): Pt = if (s <= nodeS) at(route, rc, s) else at(branch, bc, s - nodeS)

  companion object {
    private const val TAG = "NavMasterJV"
    const val LANE = 3.5
    const val SHOULDER = 0
    const val ASPHALT = 1
    const val MARK = 2
    const val RAIL = 3
    const val ARROW_SHADOW = 4
    const val ARROW = 5
    private val LHT = setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")

    /**
     * [routeLL]: the route every 3 m from [start] metres (lat, lon); the manoeuvre at [maneuverAtM];
     * [lines]: the roads of the map around it. The lane facts come from the lane guidance.
     */
    fun assemble(
        routeLL: List<DoubleArray>,
        start: Double,
        maneuverAtM: Double,
        lines: List<RoadLine>,
        md: JvModel,
        motorway: Boolean,
        exit: Boolean,
        country: String?,
    ): RealJv? {
      val m = maneuverAtM
      if (routeLL.size < 30 || lines.isEmpty()) return null
      val t0 = System.currentTimeMillis()
      val idx = ((m - start) / 3.0).toInt().coerceIn(0, routeLL.size - 1)
      val o = routeLL[idx]
      val k = cos(Math.toRadians(o[0]))
      fun loc(lat: Double, lon: Double) = Pt((lon - o[1]) * 111_320.0 * k, (lat - o[0]) * 110_540.0)
      val route = routeLL.map { loc(it[0], it[1]) }
      val rc = cum(route)
      val nodeS = m - start
      val node = at(route, rc, nodeS)
      val lanesIn = md.n.coerceIn(1, 8)
      val lanesOut = (if (md.takeBranch) md.branchLanes else md.n - md.branchLanes).coerceIn(1, 6)
      val halfIn = lanesIn * LANE / 2
      val halfOut = lanesOut * LANE / 2
      val near = route.filterIndexed { i, _ -> abs(rc[i] - nodeS) < 700 }

      class TRoad(var pts: List<Pt>, val half: Double, val lanes: Int, val oneway: Boolean, val motor: Boolean, val bridge: Boolean) {
        var dash = 0.0
        var dashSide = 0
      }
      val roads = ArrayList<TRoad>()
      var approachOneway = motorway
      var approachBest = Double.MAX_VALUE
      val probe = at(route, rc, (nodeS - 30).coerceAtLeast(0.0))
      for (l in lines) {
        if (l.tunnel) continue
        val raw = l.lat.indices.map { loc(l.lat[it], l.lon[it]) }
        if (raw.none { abs(it.x) < 800 && abs(it.y) < 800 }) continue
        val pts = densify(raw, 4.0)
        val motor = l.cls == "motorway" || l.cls == "trunk"
        val dProbe = lineDist(probe, pts)
        if (dProbe < approachBest) {
          approachBest = dProbe
          approachOneway = l.oneway || motor
        }
        // the roads of the route itself are drawn from the route, with its lanes
        val on = pts.count { lineDist(it, near) < 3.5 }
        if (on > pts.size * 0.6) continue
        val lanes: Int
        val half: Double
        when {
          l.ramp -> { lanes = 1; half = LANE / 2 }
          l.oneway || motor -> { lanes = if (motor) 3 else 1; half = lanes * LANE / 2 }
          else -> {
            lanes = 2
            half = when (l.cls) { "primary" -> 3.8; "secondary" -> 3.5; "tertiary" -> 3.2; "service" -> 2.0; else -> 2.8 }
          }
        }
        roads += TRoad(pts, half, lanes, l.oneway || motor, motor, l.bridge)
      }
      val twoWay = !approachOneway
      // the direction of arrival at the junction
      val hd = at(route, rc, (nodeS - 20).coerceAtLeast(0.0))
      val fx0 = node.x - hd.x
      val fy0 = node.y - hd.y
      fun sideOf(q: Pt) = if (fx0 * (q.y - node.y) - fy0 * (q.x - node.x) > 0) 1 else -1
      // roads of the map that leave from (or arrive at) the junction: they start at the edge of the
      // carriageway, not in its middle, until they really move away from it
      val fullRoute = near
      for (t in roads) {
        val rev = dist(t.pts.last(), node) < 6 && dist(t.pts.first(), node) >= 6
        var p = if (rev) t.pts.reversed() else t.pts
        if (dist(p.first(), node) >= 6) continue
        val c = cum(p)
        val sd = sideOf(at(p, c, 40.0))
        val tgt = halfIn - t.half
        val lat = p.map { lineDist(it, fullRoute) }
        p = offset(p) { i -> sd * max(0.0, tgt - lat[i]) }
        t.dash = lat.indices.firstOrNull { lat[it] >= halfIn + t.half + 1.0 }?.let { c[it] } ?: 40.0
        t.dashSide = -sd
        t.pts = if (rev) p.reversed() else p
      }
      val others = roads.filter { lineDist(node, it.pts) < 8 }
      // the route after the junction
      val after = (0..((rc.last() - nodeS) / 3.0).toInt()).map { i -> at(route, rc, nodeS + i * 3.0) }
      val sideRoute = sideOf(at(route, rc, nodeS + 60))
      val side = if (md.takeBranch) sideRoute else -md.side
      var split = 40.0
      val branch: List<Pt>
      if (md.takeBranch) {
        val lat = after.map { q -> others.minOfOrNull { lineDist(q, it.pts) } ?: 99.0 }
        branch = offset(after) { i -> side * max(0.0, (halfIn - halfOut) - lat[i]) }
        split = lat.indices.firstOrNull { lat[it] >= halfIn + halfOut + 1.0 }?.let { it * 3.0 } ?: 40.0
      } else {
        branch = after
        roads.filter { it.dash > 0 }.minOfOrNull { it.dash }?.let { split = it }
      }
      val bc = cum(branch)
      val lht = country?.uppercase() in LHT
      val first = md.active.firstOrNull() ?: 0
      fun laneOff(i: Int) = when {
        !twoWay -> ((lanesIn - 1) / 2.0 - i) * LANE
        lht -> (lanesIn - i - 0.5) * LANE
        else -> -(i + 0.5) * LANE
      }
      val roadHalfIn = if (twoWay) lanesIn * LANE else halfIn

      // ---- the shapes, in drawing order
      val shapes = ArrayList<Shape>()
      // layers drawn one over the other (roads at ground level, bridges, the route, the arrows);
      // inside a layer all the shapes of a kind go in one drawing (see drawRealJunction)
      var layer = 0
      fun ribbon(kind: Int, c: List<Pt>, half: Double) {
        if (c.size >= 2) shapes += Shape(kind, offset(c) { half } + offset(c) { -half }.reversed(), layer = layer)
      }
      fun stripe(c: List<Pt>, off: Double, wid: Double, on: Double = 0.0, per: Double = 0.0) {
        val l = if (off != 0.0) offset(c) { off } else c
        if (on <= 0) {
          ribbon(MARK, l, wid / 2)
          return
        }
        val lc = cum(l)
        var s = 0.0
        while (s < lc.last()) {
          val e = min(lc.last(), s + on)
          ribbon(MARK, listOf(at(l, lc, s), at(l, lc, (s + e) / 2), at(l, lc, e)), wid / 2)
          s += per
        }
      }
      fun rail(c: List<Pt>, off: Double) {
        if (c.size >= 2) shapes += Shape(RAIL, offset(c) { off }, 0.5, 0.8, layer)
      }
      val ordered = roads.sortedWith(compareBy({ it.bridge }, { -(it.pts.minOf { p -> dist(p, node) }) }))
      for (t in ordered) {
        layer = if (t.bridge) 1 else 0
        ribbon(SHOULDER, t.pts, t.half + if (t.motor) 1.2 else 0.3)
        ribbon(ASPHALT, t.pts, t.half)
        if (t.pts.minOf { dist(it, node) } > 350) continue
        if (t.oneway) {
          if (t.dash > 0 && t.dashSide != 0) {
            // the side towards the route: thick dashes until the gore
            val c = cum(t.pts)
            val fromNode = dist(t.pts.first(), node) < dist(t.pts.last(), node)
            val ptsN = if (fromNode) t.pts else t.pts.reversed()
            val cN = if (fromNode) c else cum(ptsN)
            val kk = cN.indexOfFirst { it >= t.dash }.let { if (it < 0) ptsN.size - 1 else it }
            stripe(ptsN.subList(0, kk + 1), t.dashSide * (t.half - 0.2), 0.45, 3.0, 6.0)
            if (kk < ptsN.size - 1) stripe(ptsN.subList(kk, ptsN.size), t.dashSide * (t.half - 0.35), 0.2)
            stripe(ptsN, -t.dashSide * (t.half - 0.35), 0.2)
            rail(ptsN, -t.dashSide * (t.half + 1.1))
          } else {
            stripe(t.pts, t.half - 0.35, 0.2)
            stripe(t.pts, -t.half + 0.35, 0.2)
            if (t.motor) rail(t.pts, t.half + 1.3)
          }
          for (kl in 1 until t.lanes) stripe(t.pts, t.half - kl * LANE, 0.15, 4.5, 12.0)
        } else stripe(t.pts, 0.0, 0.12, 3.0, 9.0)
      }
      // the route: the carriageway of arrival, then the road taken
      val before = route.filterIndexed { i, _ -> rc[i] <= nodeS } + node
      layer = 2
      ribbon(SHOULDER, before, roadHalfIn + if (motorway) 1.2 else 0.3)
      ribbon(ASPHALT, before, roadHalfIn)
      ribbon(SHOULDER, branch, halfOut + if (motorway) 1.0 else 0.3)
      ribbon(ASPHALT, branch, halfOut)
      if (twoWay) {
        stripe(before, 0.0, 0.14, 3.0, 9.0)
        stripe(before, roadHalfIn - 0.3, 0.15)
        stripe(before, -roadHalfIn + 0.3, 0.15)
        for (kl in 1 until lanesIn) stripe(before, (if (lht) 1 else -1) * kl * LANE, 0.12, 3.0, 9.0)
      } else {
        stripe(before, halfIn - 0.35, 0.2)
        stripe(before, -halfIn + 0.35, 0.2)
        for (kl in 1 until lanesIn) stripe(before, halfIn - kl * LANE, 0.15, 4.5, 12.0)
        if (motorway) rail(before, -side * (halfIn + 1.3))
      }
      val kSplit = bc.indexOfFirst { it >= split }.let { if (it < 0) branch.size - 1 else it }
      if (md.takeBranch) {
        stripe(branch, side * (halfOut - 0.35), 0.2)
        if (kSplit > 1) stripe(branch.subList(0, kSplit + 1), -side * (halfOut - 0.2), 0.45, 3.0, 6.0)
        if (kSplit < branch.size - 1) stripe(branch.subList(kSplit, branch.size), -side * (halfOut - 0.35), 0.2)
        if (motorway || exit) {
          rail(branch, side * (halfOut + 1.1))
          if (kSplit < branch.size - 1) rail(branch.subList(kSplit, branch.size), -side * (halfOut + 1.1))
        }
      } else {
        stripe(branch, halfOut - 0.35, 0.2)
        stripe(branch, -halfOut + 0.35, 0.2)
      }
      for (kl in 1 until lanesOut) stripe(branch, halfOut - kl * LANE, 0.15, 4.5, 12.0)

      // the arrows: on each lane to take, into the road taken, up to 45 m after the gore
      val rj0 = RealJv(route, rc, start, nodeS, node, branch, bc, split, side, halfIn, laneOff(first), md.takeBranch, emptyList())
      val gS = nodeS + split
      val s0 = (gS - 520).coerceAtLeast(0.0)
      val s1 = min(nodeS + (bc.lastOrNull() ?: 0.0), gS + 45)
      for (lane in md.active.ifEmpty { listOf(first) }.take(3)) {
        val lo = laneOff(lane)
        // the body up to the head, then a head of 11 m ending at the tip
        val sh = s1 - 11.0
        val n = 120
        val ss = (0 until n).map { s0 + it * (sh - s0) / (n - 1) } + s1
        val body = ss.map { rj0.along(it) }
        // before the junction in its lane, then gliding into the middle of the road taken
        val offs = ss.map { s -> if (s <= nodeS) lo else lo * max(0.0, 1 - (s - nodeS) / 25.0) }
        val mid = offset(body) { i -> offs[i] }
        val half = LANE * 0.33
        val hh = LANE * 0.72
        val left = offset(mid) { half }
        val right = offset(mid) { -half }
        val hl = offset(mid) { hh }[n - 1]
        val hr = offset(mid) { -hh }[n - 1]
        val outline = left.subList(0, n) + listOf(hl, mid.last(), hr) + right.subList(0, n).reversed()
        shapes += Shape(ARROW_SHADOW, outline, layer = 3)
        shapes += Shape(ARROW, outline, layer = 3)
      }
      Log.i(TAG, "real junction: ${lines.size} roads, ${roads.size} drawn, lanes $lanesIn>$lanesOut, split ${split.toInt()} m, " +
          "${if (twoWay) "two-way" else "one-way"}, ${shapes.size} shapes in ${System.currentTimeMillis() - t0} ms")
      return RealJv(route, rc, start, nodeS, node, branch, bc, split, side, halfIn, laneOff(first), md.takeBranch, shapes)
    }

    // ---- geometry
    fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    fun cum(p: List<Pt>): DoubleArray {
      val c = DoubleArray(p.size)
      for (i in 1 until p.size) c[i] = c[i - 1] + dist(p[i - 1], p[i])
      return c
    }

    fun at(p: List<Pt>, c: DoubleArray, s: Double): Pt {
      if (p.isEmpty()) return Pt(0.0, 0.0)
      if (s <= 0 || p.size == 1) return p.first()
      var i = 1
      while (i < p.size && c[i] < s) i++
      if (i >= p.size) return p.last()
      val f = (s - c[i - 1]) / max(1e-9, c[i] - c[i - 1])
      return Pt(p[i - 1].x + (p[i].x - p[i - 1].x) * f, p[i - 1].y + (p[i].y - p[i - 1].y) * f)
    }

    fun densify(p: List<Pt>, step: Double): List<Pt> {
      val out = ArrayList<Pt>()
      out += p.first()
      for (i in 1 until p.size) {
        val a = p[i - 1]
        val b = p[i]
        val n = max(1, (dist(a, b) / step).toInt())
        for (k in 1..n) out += Pt(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n)
      }
      return out
    }

    /** The line moved sideways by [off] metres (positive: to the left of its direction). */
    fun offset(p: List<Pt>, off: (Int) -> Double): List<Pt> =
        p.indices.map { i ->
          val a = p[max(0, i - 1)]
          val b = p[min(p.size - 1, i + 1)]
          val dx = b.x - a.x
          val dy = b.y - a.y
          val l = hypot(dx, dy).takeIf { it > 1e-9 } ?: 1.0
          val o = off(i)
          Pt(p[i].x - dy / l * o, p[i].y + dx / l * o)
        }

    private fun segDist(p: Pt, a: Pt, b: Pt): Double {
      val dx = b.x - a.x
      val dy = b.y - a.y
      val l2 = dx * dx + dy * dy
      val t = if (l2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
      return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }

    fun lineDist(p: Pt, l: List<Pt>): Double {
      if (l.size < 2) return l.firstOrNull()?.let { dist(p, it) } ?: Double.MAX_VALUE
      var best = Double.MAX_VALUE
      for (i in 0 until l.size - 1) {
        // cheap rejection: the segment is farther than the best found so far
        val a = l[i]
        if (abs(a.x - p.x) > best + 50 && abs(a.y - p.y) > best + 50) continue
        val d = segDist(p, a, l[i + 1])
        if (d < best) best = d
      }
      return best
    }
  }
}

