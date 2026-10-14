package app.navmaster.truck.ui.jv

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The geometry of the junction view, in metres on the ground around the junction: the route's
 * carriageway with its real number of lanes along the way (lanes that open before an exit, lanes
 * that close, a ramp that widens at the toll plaza), the roads of the map around it with their
 * real lanes, the gore where the two roads part, the arrows painted on the lanes, the crash
 * barriers and the violet arrows on the lanes to take. Pure Kotlin: tested on the computer with the
 * graph of the test area, drawn on the tablet by JvProject + RealJunction.
 */
class Pt(val x: Double, val y: Double)

/** A shape on the ground (or a barrier band) in metres, drawn in order. */
class Shape(val kind: Int, val pts: List<Pt>, val z0: Double = 0.0, val z1: Double = 0.0, val layer: Int = 0) {
  /** Centre and radius of the shape (x, y, r): a shape far from the camera is not even looked at. */
  val bound: DoubleArray by lazy {
    var x0 = Double.MAX_VALUE
    var y0 = Double.MAX_VALUE
    var x1 = -Double.MAX_VALUE
    var y1 = -Double.MAX_VALUE
    for (p in pts) {
      if (p.x < x0) x0 = p.x
      if (p.x > x1) x1 = p.x
      if (p.y < y0) y0 = p.y
      if (p.y > y1) y1 = p.y
    }
    doubleArrayOf((x0 + x1) / 2, (y0 + y1) / 2, hypot(x1 - x0, y1 - y0) / 2)
  }
}

/** A road of the map near the junction (the graph, the light package or the map tiles). */
class JvRoad(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cls: String,
    val link: Boolean,
    val oneway: Boolean,
    /** Lanes in the direction of the points and the other way (0: closed that way; 0/0: not known). */
    val lanesFwd: Int,
    val lanesBack: Int,
    val turnLanes: String = "",
    val roundabout: Boolean = false,
    val bridge: Boolean = false,
    val tunnel: Boolean = false,
    val way: Long = 0,
)

/** One road of the route (RouteAnalysis.EdgeInfo), as the junction view needs it. */
class JvEdge(val startM: Double, val endM: Double, val lanes: Int, val turnLanes: String, val ramp: Boolean, val cls: String, val way: Long = 0)

/** What the junction view is asked to draw. */
class JvInput(
    /** The route every 3 m from [startM] metres (lat, lon). */
    val routeLL: List<DoubleArray>,
    val startM: Double,
    val maneuverAtM: Double,
    val edges: List<JvEdge>,
    val roads: List<JvRoad>,
    /** Where the route leaves: -1 left, 1 right (0: straight on at a split). */
    val side: Int,
    /** The route takes the road that leaves (an exit), else it keeps the main road. */
    val takeBranch: Boolean,
    val fork: Boolean,
    val motorway: Boolean,
    val exit: Boolean,
    /** The lanes to take at the gore (0 = leftmost), when the guidance says; empty: worked out here. */
    val active: List<Int>,
    val lht: Boolean = false,
)

/** The lane profile of a carriageway along a line: lanes, and the two edges' offsets (metres, left positive). */
class Profile(val n: IntArray, val left: DoubleArray, val right: DoubleArray) {
  fun half(i: Int) = (left[i] - right[i]) / 2
  fun mid(i: Int) = (left[i] + right[i]) / 2
}

/** A lane arrow painted on the asphalt: where (metres) and its OSM arrow. */
class LaneArrow(val at: Pt, val dir: Pt, val arrow: String, val lit: Boolean)

class JvGeo(
    val route: List<Pt>,
    val rc: DoubleArray,
    /** Metres of the whole route at [route]'s first point. */
    val start: Double,
    val nodeS: Double,
    val node: Pt,
    /** The route after the node (moved beside the road left, until the two part). */
    val branch: List<Pt>,
    val bc: DoubleArray,
    /** Metres after the node where the two roads are apart (the nose of the gore). */
    val split: Double,
    val side: Int,
    val takeBranch: Boolean,
    /** The lanes of the carriageway of arrival, sample by sample of [route] (before the node). */
    val profile: Profile,
    /** The lanes of the road taken, sample by sample of [branch]. */
    val outProfile: Profile,
    /** How far [branch] was moved sideways from the route's own line, sample by sample (left positive). */
    val branchShift: DoubleArray,
    val shapes: List<Shape>,
    val laneArrows: List<LaneArrow>,
    /** Lanes at the gore, whether the route's line is the middle of a two-way road, left-hand traffic. */
    val lanesAtNode: Int,
    val twoWay: Boolean,
    val lht: Boolean,
    /** The lanes to take at the gore (0 = leftmost). */
    val active: List<Int>,
    /** The arrows of each lane at the gore (OSM words, "through"), empty when not mapped. */
    val arrowsAtNode: List<String>,
) {
  /** The shapes layer by layer, kind by kind (the order they are drawn in). */
  val drawOrder: List<Shape> by lazy { shapes.sortedWith(compareBy({ it.layer }, { it.kind })) }

  fun along(s: Double): Pt = if (s <= nodeS) JvMath.at(route, rc, s) else JvMath.at(branch, bc, s - nodeS)

  /** Sideways offset (metres, positive to the left of the route's line) of the middle of lane [i] at the gore. */
  fun laneOff(i: Int): Double = laneOffAt(nodeS - 3, i)

  /**
   * The middle of lane [i] (0 = leftmost) at [s] metres of [route], before the node: where the lane
   * does not exist yet (it opens farther on) the nearest lane that does.
   */
  fun laneOffAt(s: Double, i: Int): Double {
    val k = profileIndex(s.coerceAtMost(nodeS))
    val n = profile.n[k]
    val li = i.coerceIn(0, n - 1)
    return when {
      !twoWay -> profile.left[k] - (li + 0.5) * LANE
      lht -> (n - li - 0.5) * LANE
      else -> -(li + 0.5) * LANE
    }
  }

  /** The sample of [route] at [s] metres from its start. */
  fun profileIndex(s: Double): Int {
    var i = 0
    while (i < rc.size - 1 && rc[i + 1] < s) i++
    return i.coerceIn(0, profile.n.size - 1)
  }

  /** The middle of the lanes to take at the gore (metres, left positive), where the camera looks. */
  val actOff: Double
    get() = if (active.isEmpty()) 0.0 else active.map { laneOff(it) }.average()

  /**
   * The sideways offset to add to [along] ([s]) for lane [lane] (null: the lanes to take): the lane
   * itself before the node, then gliding to the middle of the road taken.
   */
  fun laneExtra(s: Double, lane: Int?): Double {
    val lo = if (lane == null) actOff else laneOff(lane)
    if (s <= nodeS) return if (lane == null) actOff else laneOffAt(s, lane)
    val g = JvMath.glide(s - nodeS)
    var i = 0
    while (i < bc.size - 1 && bc[i + 1] < s - nodeS) i++
    return (lo - branchShift.getOrElse(i) { 0.0 }) * g
  }

  companion object {
    const val LANE = 3.5
    const val SHOULDER = 0
    const val ASPHALT = 1
    const val MARK = 2
    const val RAIL = 3
    const val ARROW_SHADOW = 4
    const val ARROW = 5
    const val GORE = 6
    const val LANE_ARROW = 7
    const val LANE_ARROW_LIT = 8
    private val LEFTISH = setOf("left", "slight_left", "sharp_left", "merge_to_right")
    private val RIGHTISH = setOf("right", "slight_right", "sharp_right", "merge_to_left")

    /** The arrows of a turn:lanes value, one list per lane. */
    fun arrows(turnLanes: String): List<List<String>> =
        if (turnLanes.isBlank()) emptyList()
        else turnLanes.split("|").map { l -> l.split(";").map { it.trim() }.filter { it.isNotEmpty() && it != "none" } }

    /**
     * The lane profile of the route from [s0] to [s1] (metres of the route): the lanes of every
     * road of the route, a lane that opens before an exit (the deceleration lane, 240 m when the
     * map has it not), the side each change happens on (the arrows say it, else the side of the
     * exit, else the right), the edges tapering over 40-60 m.
     */
    fun laneProfile(rcAbs: DoubleArray, edges: List<JvEdge>, nodeAbs: Double, side: Int, exit: Boolean, takeBranch: Boolean,
                    motorway: Boolean, lht: Boolean, afterNode: Boolean = false): Profile {
      val m = rcAbs.size
      val n = IntArray(m)
      val tl = arrayOfNulls<String>(m)
      var last = 1
      for (i in 0 until m) {
        // the samples at the node belong to the road before it (or after it, past the node)
        val s = rcAbs[i] + if (afterNode) 0.5 else -0.5
        val e = edges.firstOrNull { s >= it.startM && s < it.endM } ?: edges.lastOrNull { s >= it.startM }
        if (e != null && e.lanes >= 1) last = e.lanes.coerceIn(1, 8)
        n[i] = last
        tl[i] = e?.turnLanes
      }
      // the deceleration lane of an exit the map has not mapped as a lane: 240 m before the gore
      val decel = BooleanArray(m)
      if (!afterNode && exit && takeBranch && motorway) {
        val k0 = rcAbs.indexOfFirst { it >= nodeAbs - 450 }.coerceAtLeast(0)
        val kn = m - 1
        var opens = false
        for (i in k0 + 1..kn) if (n[i] > n[i - 1]) opens = true
        // the arrows of the last road already show a lane leaving on the side: the map has the exit lane
        val sideArrow = arrows(tl.getOrNull(kn) ?: "").any { l -> l.any { it in (if (side > 0) RIGHTISH else LEFTISH) } }
        if (!opens && !sideArrow) {
          for (i in 0..kn) if (rcAbs[i] >= nodeAbs - 240) decel[i] = true
        }
      }
      val nn = IntArray(m) { n[it] + if (decel[it]) 1 else 0 }
      val left = DoubleArray(m)
      val right = DoubleArray(m)
      val n0 = nn[0]
      for (i in 0 until m) { left[i] = n0 * LANE / 2; right[i] = -n0 * LANE / 2 }
      // every change of the count: on which side, tapered
      var i = 1
      while (i < m) {
        if (nn[i] != nn[i - 1]) {
          val d = nn[i] - nn[i - 1]
          val a = arrows(tl[i] ?: "")
          val defaultRight = if (lht) 1 else -1
          val chg = when {
            d > 0 && a.size == nn[i] && a.last().any { it in RIGHTISH } -> -1
            d > 0 && a.size == nn[i] && a.first().any { it in LEFTISH } -> 1
            d > 0 && decel[i] && !decel[i - 1] -> if (side != 0) -side else defaultRight
            abs(rcAbs[i] - nodeAbs) < 450 && side != 0 -> -side
            else -> defaultRight
          }
          val taper = if (d > 0) 60.0 else 40.0
          // a lane that opens widens before the point; one that closes narrows after it
          val s0 = if (d > 0) rcAbs[i] - taper else rcAbs[i]
          val s1 = if (d > 0) rcAbs[i] else rcAbs[i] + taper
          for (k in 0 until m) {
            val t = ((rcAbs[k] - s0) / (s1 - s0)).coerceIn(0.0, 1.0)
            val tt = t * t * (3 - 2 * t)
            if (chg < 0) right[k] -= d * LANE * tt else left[k] += d * LANE * tt
          }
        }
        i++
      }
      return Profile(nn, left, right)
    }

    /** Builds the scene. Null when the route around the junction is too short. */
    /**
     * Where the roads really part: the guidance puts the manoeuvre some metres past the gore (on
     * the ramp), the drawing wants the point where the route leaves the road of arrival, i.e. the
     * start of the first ramp (for an exit), else the nearest change of road.
     */
    fun nodeOf(inp: JvInput): Double {
      val m = inp.maneuverAtM
      val cands = inp.edges.filter { it.startM > m - 150 && it.startM < m + 80 }
      if (inp.takeBranch && inp.exit) {
        val before = inp.edges.lastOrNull { it.startM <= m - 150 || (it.startM <= m && !it.ramp) }
        val ramp = cands.firstOrNull { it.ramp && (before == null || !before.ramp || it.startM >= m - 150) }
        if (ramp != null && (before == null || !before.ramp)) return ramp.startM
      }
      return cands.minByOrNull { abs(it.startM - m) }?.startM ?: m
    }

    fun build(inp: JvInput): JvGeo? {
      val m = nodeOf(inp)
      val routeLL = inp.routeLL
      if (routeLL.size < 30) return null
      val idx = ((m - inp.startM) / 3.0).toInt().coerceIn(0, routeLL.size - 1)
      val o = routeLL[idx]
      val k = cos(Math.toRadians(o[0]))
      fun loc(lat: Double, lon: Double) = Pt((lon - o[1]) * 111_320.0 * k, (lat - o[0]) * 110_540.0)
      val route = routeLL.map { loc(it[0], it[1]) }
      val rc = JvMath.cum(route)
      val nodeS = m - inp.startM
      val node = JvMath.at(route, rc, nodeS)
      val lht = inp.lht
      val near = route.filterIndexed { i, _ -> abs(rc[i] - nodeS) < 700 }

      // ---- the lanes of the route before and after the node
      val before = route.filterIndexed { i, _ -> rc[i] <= nodeS } + node
      val bcum = JvMath.cum(before)
      val beforeAbs = DoubleArray(before.size) { inp.startM + bcum[it] }
      val profile = laneProfile(beforeAbs, inp.edges, m, inp.side, inp.exit, inp.takeBranch, inp.motorway, lht)
      val kNode = before.size - 1
      val lanesAtNode = profile.n[kNode]
      val after = (0..((rc.last() - nodeS) / 3.0).toInt()).map { i -> JvMath.at(route, rc, nodeS + i * 3.0) }
      val afterAbs = DoubleArray(after.size) { m + it * 3.0 }
      val outRaw = laneProfile(afterAbs, inp.edges, m, inp.side, false, false, false, lht, afterNode = true)
      // the lanes taken at the gore: the arrows of the last road, else the outer lanes on the side
      val lastTl = inp.edges.lastOrNull { it.startM < m - 2 && it.turnLanes.isNotBlank() && it.endM > m - 160 }?.turnLanes ?: ""
      val rawAtNode = inp.edges.lastOrNull { it.startM < m - 0.5 }?.lanes ?: lanesAtNode
      val arrowsAtNode = arrows(lastTl).let { a ->
        when {
          a.size == lanesAtNode -> a.map { it.firstOrNull() ?: "through" }
          a.size == lanesAtNode - 1 && inp.takeBranch -> if (inp.side > 0) a.map { it.firstOrNull() ?: "through" } + "slight_right"
              else listOf("slight_left") + a.map { it.firstOrNull() ?: "through" }
          // the deceleration lane the map has not: its arrow, the others straight on
          a.isEmpty() && inp.takeBranch && lanesAtNode > rawAtNode -> if (inp.side > 0) List(lanesAtNode - 1) { "through" } + "slight_right"
              else listOf("slight_left") + List(lanesAtNode - 1) { "through" }
          else -> emptyList()
        }
      }
      val outLanesFirst = outRaw.n.firstOrNull() ?: 1
      val active: List<Int> = when {
        inp.active.isNotEmpty() && inp.active.all { it in 0 until lanesAtNode } -> inp.active
        arrowsAtNode.isNotEmpty() && inp.takeBranch -> arrowsAtNode.indices.filter { i ->
          arrowsAtNode[i].let { if (inp.side > 0) it in RIGHTISH else it in LEFTISH }
        }.ifEmpty { if (inp.side > 0) listOf(lanesAtNode - 1) else listOf(0) }
        inp.takeBranch -> if (inp.side > 0) (lanesAtNode - outLanesFirst.coerceIn(1, lanesAtNode) until lanesAtNode).toList()
            else (0 until outLanesFirst.coerceIn(1, lanesAtNode)).toList()
        arrowsAtNode.isNotEmpty() -> arrowsAtNode.indices.filter { i ->
          arrowsAtNode[i].let { if (inp.side > 0) it !in RIGHTISH else it !in LEFTISH }
        }.ifEmpty { listOf(lanesAtNode / 2) }
        else -> {
          // the main road goes on: its lanes are the ones not leaving on the side
          val keep = (lanesAtNode - 1).coerceAtLeast(1)
          if (inp.side > 0) (0 until keep).toList() else ((lanesAtNode - keep) until lanesAtNode).toList()
        }
      }.take(4)

      // ---- the roads of the map
      class TRoad(var pts: List<Pt>, val half: Double, val lanesF: Int, val lanesB: Int, val oneway: Boolean, val motor: Boolean,
                  val bridge: Boolean, val link: Boolean, val turnLanes: String) {
        var dash = 0.0
        var dashSide = 0
      }
      val roads = ArrayList<TRoad>()
      var approachOneway = inp.motorway
      var approachBest = Double.MAX_VALUE
      val probe = JvMath.at(route, rc, (nodeS - 30).coerceAtLeast(0.0))
      for (l in inp.roads) {
        if (l.tunnel) continue
        val raw = l.lat.indices.map { loc(l.lat[it], l.lon[it]) }
        if (raw.none { abs(it.x) < 800 && abs(it.y) < 800 }) continue
        val pts = JvMath.densify(raw, 4.0)
        val motor = l.cls == "motorway" || l.cls == "trunk"
        val dProbe = JvMath.lineDist(probe, pts)
        if (dProbe < approachBest) {
          approachBest = dProbe
          approachOneway = l.oneway || motor
        }
        // the roads of the route itself are drawn from the route, with its lanes
        val on = pts.count { JvMath.lineDist(it, near) < 3.5 }
        if (on > pts.size * 0.6) continue
        val known = l.lanesFwd + l.lanesBack > 0
        val lf: Int
        val lb: Int
        when {
          known -> { lf = l.lanesFwd; lb = l.lanesBack }
          l.link -> { lf = 1; lb = 0 }
          l.oneway || motor -> { lf = if (motor) 2 else 1; lb = 0 }
          else -> { lf = 1; lb = 1 }
        }
        val oneway = l.oneway || (lf == 0 || lb == 0)
        val laneW = when { motor || l.link -> LANE; l.cls == "primary" -> 3.4; l.cls == "secondary" -> 3.2; l.cls == "tertiary" -> 3.0; l.cls == "service" -> 2.6; else -> 2.9 }
        val half = (lf + lb).coerceAtLeast(1) * laneW / 2
        roads += TRoad(pts, half, lf, lb, oneway, motor, l.bridge, l.link, l.turnLanes)
      }
      val twoWay = !approachOneway
      // the direction of arrival at the junction
      val hd = JvMath.at(route, rc, (nodeS - 20).coerceAtLeast(0.0))
      val fx0 = node.x - hd.x
      val fy0 = node.y - hd.y
      fun sideOf(q: Pt) = if (fx0 * (q.y - node.y) - fy0 * (q.x - node.x) > 0) 1 else -1
      // the carriageway's edges at the node (metres from the route's line, left positive)
      val leftNode = if (twoWay) profile.left[kNode] * 2 else profile.left[kNode]
      val rightNode = if (twoWay) 0.0 else profile.right[kNode]
      // roads of the map that leave from (or arrive at) the junction: they start at the edge of the
      // carriageway, not in its middle, until they really move away from it
      for (t in roads) {
        val rev = JvMath.dist(t.pts.last(), node) < 6 && JvMath.dist(t.pts.first(), node) >= 6
        var p = if (rev) t.pts.reversed() else t.pts
        if (JvMath.dist(p.first(), node) >= 6) continue
        val c = JvMath.cum(p)
        val sd = sideOf(JvMath.at(p, c, 40.0))
        // a road leaving on the left sits against the left edge, on the right against the right edge
        val edge = if (sd > 0) leftNode else -rightNode
        val tgt = edge - t.half
        val lat = p.map { JvMath.lineDist(it, near) }
        p = JvMath.offset(p) { i -> sd * max(0.0, tgt - lat[i]) }
        t.dash = lat.indices.firstOrNull { lat[it] >= edge + t.half + 1.0 }?.let { c[it] } ?: 40.0
        t.dashSide = -sd
        t.pts = if (rev) p.reversed() else p
      }
      val others = roads.filter { JvMath.lineDist(node, it.pts) < 8 }
      // the route after the junction: beside the road left, until the two part
      val sideRoute = sideOf(JvMath.at(route, rc, nodeS + 60))
      val side = if (inp.takeBranch) sideRoute else (if (inp.side != 0) -inp.side else -sideRoute)
      var split = 40.0
      val branch: List<Pt>
      var shift = DoubleArray(after.size)
      // the road the route leaves: the one going on from the node (the widest, when several)
      val left = others.filter { it.pts.minOf { p -> JvMath.dist(p, node) } < 8 }.maxByOrNull { it.half }
      if (inp.takeBranch) {
        val latRaw = after.map { q -> left?.let { JvMath.lineDist(q, it.pts) } ?: 99.0 }
        // smoothed over 30 m, so the road taken bends gently away
        val lat = latRaw.indices.map { i -> (max(0, i - 5)..min(latRaw.size - 1, i + 5)).map { latRaw[it] }.average() }
        val otherHalf = left?.half ?: (lanesAtNode - 1).coerceAtLeast(1) * LANE / 2
        shift = DoubleArray(after.size) { i -> side * max(0.0, (otherHalf + outRaw.half(i)) - lat[i]) }
        branch = JvMath.offset(after) { i -> shift[i] }
        split = latRaw.indices.firstOrNull { latRaw[it] >= otherHalf + outRaw.half(it) + 1.0 }?.let { it * 3.0 } ?: 40.0
      } else {
        branch = after
        roads.filter { it.dash > 0 }.minOfOrNull { it.dash }?.let { split = it }
      }
      val bc = JvMath.cum(branch)
      // the lanes of the road taken are the lanes to take, as one carriageway with the ones before
      val outProfile = outRaw

      // ---- the shapes, in drawing order
      val shapes = ArrayList<Shape>()
      var layer = 0
      fun ribbonVar(kind: Int, c: List<Pt>, left: (Int) -> Double, right: (Int) -> Double) {
        if (c.size >= 2) shapes += Shape(kind, JvMath.offset(c, left) + JvMath.offset(c, right).reversed(), layer = layer)
      }
      fun ribbon(kind: Int, c: List<Pt>, half: Double) = ribbonVar(kind, c, { half }, { -half })
      fun stripeVar(c: List<Pt>, off: (Int) -> Double, wid: Double, on: Double = 0.0, per: Double = 0.0) {
        val l = JvMath.offset(c, off)
        if (on <= 0) {
          ribbon(MARK, l, wid / 2)
          return
        }
        val lc = JvMath.cum(l)
        var s = 0.0
        while (s < lc.last()) {
          val e = min(lc.last(), s + on)
          ribbon(MARK, listOf(JvMath.at(l, lc, s), JvMath.at(l, lc, (s + e) / 2), JvMath.at(l, lc, e)), wid / 2)
          s += per
        }
      }
      fun stripe(c: List<Pt>, off: Double, wid: Double, on: Double = 0.0, per: Double = 0.0) = stripeVar(c, { off }, wid, on, per)
      fun rail(c: List<Pt>, off: (Int) -> Double) {
        if (c.size >= 2) shapes += Shape(RAIL, JvMath.offset(c, off), 0.5, 0.8, layer)
      }
      val ordered = roads.sortedWith(compareBy({ it.bridge }, { -(it.pts.minOf { p -> JvMath.dist(p, node) }) }))
      for (t in ordered) {
        // a bridge passes over the junction (and over the route): drawn last
        layer = if (t.bridge) 4 else 0
        ribbon(SHOULDER, t.pts, t.half + if (t.motor) 1.2 else 0.3)
        ribbon(ASPHALT, t.pts, t.half)
        if (t.pts.minOf { JvMath.dist(it, node) } > 350) continue
        val laneW = t.half * 2 / (t.lanesF + t.lanesB).coerceAtLeast(1)
        if (t.oneway) {
          if (t.dash > 0 && t.dashSide != 0) {
            // the side towards the route: thick dashes until the gore
            val c = JvMath.cum(t.pts)
            val fromNode = JvMath.dist(t.pts.first(), node) < JvMath.dist(t.pts.last(), node)
            val ptsN = if (fromNode) t.pts else t.pts.reversed()
            val cN = if (fromNode) c else JvMath.cum(ptsN)
            val kk = cN.indexOfFirst { it >= t.dash }.let { if (it < 0) ptsN.size - 1 else it }
            stripe(ptsN.subList(0, kk + 1), t.dashSide * (t.half - 0.2), 0.45, 3.0, 6.0)
            if (kk < ptsN.size - 1) stripe(ptsN.subList(kk, ptsN.size), t.dashSide * (t.half - 0.35), 0.2)
            stripe(ptsN, -t.dashSide * (t.half - 0.35), 0.2)
            rail(ptsN) { -t.dashSide * (t.half + 1.1) }
          } else {
            stripe(t.pts, t.half - 0.35, 0.2)
            stripe(t.pts, -t.half + 0.35, 0.2)
            if (t.motor) rail(t.pts) { t.half + 1.3 }
          }
          val ln = (t.lanesF + t.lanesB).coerceAtLeast(1)
          for (kl in 1 until ln) stripe(t.pts, t.half - kl * laneW, 0.15, 4.5, 12.0)
        } else {
          // two-way: the middle line, the lanes of each direction
          val lf = t.lanesF.coerceAtLeast(1)
          val lb = t.lanesB.coerceAtLeast(1)
          val midOff = t.half - lf * laneW
          stripe(t.pts, midOff, 0.12, 3.0, 9.0)
          stripe(t.pts, t.half - 0.3, 0.12)
          stripe(t.pts, -t.half + 0.3, 0.12)
          for (kl in 1 until lf) stripe(t.pts, t.half - kl * laneW, 0.12, 3.0, 9.0)
          for (kl in 1 until lb) stripe(t.pts, -t.half + kl * laneW, 0.12, 3.0, 9.0)
        }
      }
      // ---- the route: the carriageway of arrival with its lane profile, then the road taken
      layer = 2
      val pl = profile
      val shoulderW = if (inp.motorway) 1.2 else 0.3
      if (twoWay) {
        // the route's line is the middle of the road: our lanes on the right (on the left where
        // traffic keeps left), as many on the other side
        val wOurs = { i: Int -> pl.left[i] - pl.right[i] }
        ribbonVar(SHOULDER, before, { i -> wOurs(i) + shoulderW }, { i -> -wOurs(i) - shoulderW })
        ribbonVar(ASPHALT, before, { i -> wOurs(i) }, { i -> -wOurs(i) })
        stripe(before, 0.0, 0.14, 3.0, 9.0)
        stripeVar(before, { i -> wOurs(i) - 0.3 }, 0.15)
        stripeVar(before, { i -> -wOurs(i) + 0.3 }, 0.15)
        val sgn = if (lht) 1 else -1
        val maxN = pl.n.max()
        for (kl in 1 until maxN) {
          var i = 0
          while (i < before.size) {
            if (pl.n[i] > kl) {
              var j = i
              while (j < before.size && pl.n[j] > kl) j++
              stripeVar(before.subList(i, j), { _ -> sgn * kl * LANE }, 0.12, 3.0, 9.0)
              i = j
            } else i++
          }
        }
      } else {
        ribbonVar(SHOULDER, before, { i -> pl.left[i] + shoulderW }, { i -> pl.right[i] - shoulderW })
        ribbonVar(ASPHALT, before, { i -> pl.left[i] }, { i -> pl.right[i] })
        stripeVar(before, { i -> pl.left[i] - 0.35 }, 0.2)
        stripeVar(before, { i -> pl.right[i] + 0.35 }, 0.2)
        // the lane lines: from the left edge, one every lane, as many as the lanes there
        val maxN = pl.n.max()
        for (kl in 1 until maxN) {
          // the line exists only where the carriageway has more than kl lanes
          var i = 0
          while (i < before.size) {
            if (pl.n[i] > kl) {
              var j = i
              while (j < before.size && pl.n[j] > kl) j++
              val seg = before.subList(i, j)
              val base = i
              // the lines move with the left edge (lanes added on the right) or with the right edge
              stripeVar(seg, { q -> pl.left[base + q] - kl * LANE }, 0.15, 4.5, 12.0)
              i = j
            } else i++
          }
        }
        // the barrier on the side the road left keeps (the other side opens at the gore)
        if (inp.motorway) rail(before) { i -> if (side > 0) pl.right[i] - 1.3 else pl.left[i] + 1.3 }
      }
      // the road taken
      val op = outProfile
      val mo = if (inp.motorway || inp.exit) 1.0 else 0.3
      ribbonVar(SHOULDER, branch, { i -> op.left[i] + mo }, { i -> op.right[i] - mo })
      ribbonVar(ASPHALT, branch, { i -> op.left[i] }, { i -> op.right[i] })
      val kSplit = bc.indexOfFirst { it >= split }.let { if (it < 0) branch.size - 1 else it }
      if (inp.takeBranch) {
        // the outer edge solid; the inner one dashed until the gore, then solid
        // [side] > 0: the route leaves to the left, its outer edge is the left one
        stripeVar(branch, { i -> if (side > 0) op.left[i] - 0.35 else op.right[i] + 0.35 }, 0.2)
        if (kSplit > 1) stripeVar(branch.subList(0, kSplit + 1), { i -> if (side > 0) op.right[i] + 0.2 else op.left[i] - 0.2 }, 0.45, 3.0, 6.0)
        if (kSplit < branch.size - 1) stripeVar(branch.subList(kSplit, branch.size), { i -> if (side > 0) op.right[kSplit + i] + 0.35 else op.left[kSplit + i] - 0.35 }, 0.2)
        if (inp.motorway || inp.exit) {
          rail(branch) { i -> if (side > 0) op.left[i] + 1.1 else op.right[i] - 1.1 }
          if (kSplit < branch.size - 1) rail(branch.subList(kSplit, branch.size)) { i -> if (side > 0) op.right[kSplit + i] - 1.1 else op.left[kSplit + i] + 1.1 }
        }
      } else {
        stripeVar(branch, { i -> op.left[i] - 0.35 }, 0.2)
        stripeVar(branch, { i -> op.right[i] + 0.35 }, 0.2)
      }
      val maxOut = op.n.max()
      for (kl in 1 until maxOut) {
        var i = 0
        while (i < branch.size) {
          if (op.n[i] > kl) {
            var j = i
            while (j < branch.size && op.n[j] > kl) j++
            val base = i
            stripeVar(branch.subList(i, j), { q -> op.left[base + q] - kl * LANE }, 0.15, 4.5, 12.0)
            i = j
          } else i++
        }
      }
      // the gore: the white nose between the two roads, from the node to where they part
      val goreOther = left?.takeIf { JvMath.dist(it.pts.first(), node) < 8 || JvMath.dist(it.pts.last(), node) < 8 }
      if (goreOther != null && inp.takeBranch) {
        val o = if (JvMath.dist(goreOther.pts.first(), node) < 8) goreOther.pts else goreOther.pts.reversed()
        val oc = JvMath.cum(o)
        // the painted nose: from the node to where the two edges are 4 m apart
        val bInnerAll = JvMath.offset(branch) { i -> if (side > 0) op.right[i] else op.left[i] }
        val oInnerAll = JvMath.offset(o) { side * goreOther.half }
        val kG = bInnerAll.indices.firstOrNull { it > kSplit && JvMath.lineDist(bInnerAll[it], oInnerAll) >= 4.0 } ?: (kSplit * 2).coerceAtMost(branch.size - 1)
        val kO = oc.indexOfFirst { it >= bc[kG] }.let { if (it < 0) o.size - 1 else it }
        if (kO > 1 && kG > 1) shapes += Shape(GORE, oInnerAll.subList(0, kO + 1) + bInnerAll.subList(0, kG + 1).reversed(), layer = 2)
      }
      // ---- the arrows painted on the lanes before the gore (OSM turn:lanes, or the exit lane)
      val laneArrows = ArrayList<LaneArrow>()
      if (!twoWay && arrowsAtNode.isNotEmpty()) {
        for (back in listOf(45.0, 110.0)) {
          val sA = nodeS - back
          if (sA < 0) continue
          val kA = (0 until before.size).minByOrNull { abs(bcum[it] - sA) } ?: continue
          val p = JvMath.at(before, bcum, sA)
          val q = JvMath.at(before, bcum, sA + 4)
          val d = Pt(q.x - p.x, q.y - p.y)
          val dl = hypot(d.x, d.y).takeIf { it > 1e-6 } ?: 1.0
          val dir = Pt(d.x / dl, d.y / dl)
          for (li in arrowsAtNode.indices) {
            if (li >= pl.n[kA]) continue
            val off = pl.left[kA] - (li + 0.5) * LANE
            val at = Pt(p.x - dir.y * off, p.y + dir.x * off)
            laneArrows += LaneArrow(at, dir, arrowsAtNode[li], li in active)
          }
        }
      }
      for (la in laneArrows) shapes += Shape(if (la.lit) LANE_ARROW_LIT else LANE_ARROW, JvMath.arrowShape(la), layer = 2)

      // ---- the violet arrows: on each lane to take, into the road taken, up to 45 m after the gore
      val geo0 = JvGeo(route, rc, inp.startM, nodeS, node, branch, bc, split, side, inp.takeBranch, profile, outProfile, shift, emptyList(), laneArrows,
          lanesAtNode, twoWay, lht, active, arrowsAtNode)
      val gS = nodeS + split
      val s0 = (gS - 520).coerceAtLeast(0.0)
      // the arrow goes on along the road taken, up to where the manoeuvre is done (250 m past the gore)
      val s1 = min(nodeS + (bc.lastOrNull() ?: 0.0) - 8, gS + 250)
      // where the lanes to take end up in the road taken: spread over its lanes
      val nOut = outLanesFirst.coerceAtLeast(1)
      for ((ai, lane) in active.take(3).withIndex()) {
        val outLane = if (active.size <= nOut) (ai + (nOut - active.size) / 2).coerceIn(0, nOut - 1) else (ai * nOut / active.size).coerceIn(0, nOut - 1)
        val sh = s1 - 11.0
        val n = 120
        val ss = (0 until n).map { s0 + it * (sh - s0) / (n - 1) } + s1
        val body = ss.map { geo0.along(it) }
        // in its lane before the node, then gliding into its lane of the road taken
        val lo = geo0.laneOff(lane)
        val offs = ss.map { s ->
          val g = JvMath.glide(s - nodeS)
          val ii = bc.indexOfFirst { it >= s - nodeS }.coerceIn(0, op.n.size - 1)
          val outOff = op.left[ii] - (outLane + 0.5) * LANE
          if (s <= nodeS) geo0.laneOffAt(s, lane) else (lo - shift.getOrElse(ii) { 0.0 }) * g + outOff * (1 - g)
        }
        val mid = JvMath.offset(body) { i -> offs[i] }
        val half = LANE * 0.33
        val hh = LANE * 0.72
        val left = JvMath.offset(mid) { half }
        val right = JvMath.offset(mid) { -half }
        val hl = JvMath.offset(mid) { hh }[n - 1]
        val hr = JvMath.offset(mid) { -hh }[n - 1]
        val outline = left.subList(0, n) + listOf(hl, mid.last(), hr) + right.subList(0, n).reversed()
        shapes += Shape(ARROW_SHADOW, outline, layer = 3)
        shapes += Shape(ARROW, outline, layer = 3)
      }
      return JvGeo(route, rc, inp.startM, nodeS, node, branch, bc, split, side, inp.takeBranch, profile, outProfile, shift, shapes, laneArrows,
          lanesAtNode, twoWay, lht, active, arrowsAtNode)
    }
  }
}

/** Plane geometry in metres. */
object JvMath {
  fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

  fun cum(p: List<Pt>): DoubleArray {
    val c = DoubleArray(p.size)
    for (i in 1 until p.size) c[i] = c[i - 1] + dist(p[i - 1], p[i])
    return c
  }

  /** 1 before the node, 0 from 70 m after it, smoothly in between. */
  fun glide(afterNode: Double): Double {
    if (afterNode <= 0) return 1.0
    val t = (afterNode / 70.0).coerceIn(0.0, 1.0)
    return 1 - t * t * (3 - 2 * t)
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
      val a = l[i]
      if (abs(a.x - p.x) > best + 50 && abs(a.y - p.y) > best + 50) continue
      val d = segDist(p, a, l[i + 1])
      if (d < best) best = d
    }
    return best
  }

  /** The outline of a lane arrow painted on the asphalt (5 m long, as the real ones), from its OSM word. */
  fun arrowShape(la: LaneArrow): List<Pt> {
    val d = la.dir
    val n = Pt(-d.y, d.x) // left
    fun p(f: Double, l: Double) = Pt(la.at.x + d.x * f + n.x * l, la.at.y + d.y * f + n.y * l)
    val w = 0.3
    val bend = when (la.arrow) {
      "slight_right", "merge_to_left" -> -0.9
      "right" -> -1.5
      "sharp_right" -> -2.0
      "slight_left", "merge_to_right" -> 0.9
      "left" -> 1.5
      "sharp_left" -> 2.0
      else -> 0.0
    }
    val straight = abs(bend) < 0.01
    // shaft 0..3 m, head 3..5 m, the head leaning the arrow's way
    return if (straight) listOf(p(0.0, -w), p(3.0, -w), p(3.0, -1.0), p(5.0, 0.0), p(3.0, 1.0), p(3.0, w), p(0.0, w))
    else listOf(p(0.0, -w), p(2.2, -w), p(3.6, bend - w * 1.2), p(3.2, bend - 1.1), p(5.2, bend), p(3.9, bend + 1.0), p(4.0, bend + w * 1.2),
        p(2.4, w * 1.4), p(0.0, w))
  }
}
