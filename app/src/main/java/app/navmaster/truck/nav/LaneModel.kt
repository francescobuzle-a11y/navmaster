package app.navmaster.truck.nav

import app.navmaster.truck.core.Geo
import app.navmaster.truck.routing.EdgeInfo
import app.navmaster.truck.routing.EdgeSign
import app.navmaster.truck.routing.LaneDest
import app.navmaster.truck.routing.RouteAnalysis
import kotlin.math.abs

/**
 * The lanes of the next junction, worked out once from the engine's graph and used by everything
 * that shows or says them: the lane strip, the voice and the junction view (the same lanes, the
 * same ones lit, everywhere).
 *
 * Only what the map says, never lanes made up:
 *  1. the arrows painted on the lanes (OpenStreetMap turn:lanes) of the last stretch before the
 *     junction: each arrow is matched with the road it points to among the roads that really
 *     leave the junction, and the lanes whose arrow points to the road the route takes are lit;
 *  2. at a motorway exit or fork without turn:lanes, the lane counts, when they add up exactly
 *     (the road before has as many lanes as the two roads after it together): the lanes on the
 *     side of the road taken; no arrows are drawn then (none are painted on the map);
 *  3. otherwise no lanes, only the side to keep.
 */
object LaneModel {
  /** One of the roads leaving the junction: its direction against the road arriving (−180 … 180, negative = left). */
  data class Road(val angle: Double, val taken: Boolean, val lanes: Int, val roadClass: String, val ramp: Boolean, val sign: EdgeSign?, val wayId: Long = 0)

  /** The junction and its lanes, left to right in the direction of travel. */
  data class Junction(
      /** Metres along the route. */
      val atM: Double,
      val kind: LanePlan.Kind,
      /** Side of the road taken: −1 left, 1 right, 0 straight on. */
      val side: Int,
      /** The lanes before the junction (empty when not known). */
      val lanes: List<LanePlan.Lane>,
      /** The lanes come from the painted arrows (turn:lanes), not from the lane counts. */
      val painted: Boolean,
      val roads: List<Road>,
      /** The direction sign of the road taken and of the other main road, when mapped. */
      val sign: EdgeSign?,
      val otherSign: EdgeSign?,
      /** The signs over the lanes (destination:lanes), left to right, when mapped and as many as the lanes. */
      val laneSigns: List<LaneDest>?,
      /** The lanes of the road taken and of the other main road after the junction (0 = not known). */
      val takenLanes: Int,
      val otherLanes: Int,
      /** The stretch of road arriving at the junction (its way, lanes, class). */
      val approachWay: Long = 0,
      val approachClass: String = "",
      val approachRamp: Boolean = false,
  ) {
    val taken: Road get() = roads.first { it.taken }
    /** The other main road: the one leaving closest in direction to the road taken. */
    val other: Road? get() = roads.filter { !it.taken }.minByOrNull { abs(it.angle - taken.angle) }
    val active: List<Int> get() = lanes.indices.filter { lanes[it].take }
    val plan: LanePlan.Plan
      get() = LanePlan.Plan(lanes, kind, side, painted, if (lanes.isEmpty()) sideHint(side) else LanePlan.hint(lanes), LanePlan.say(lanes, kind))
  }

  private val FAST = setOf("motorway", "trunk")

  /** The nominal direction of a painted arrow, degrees (left negative). */
  private fun arrowAngle(a: String): Double? = when (a) {
    "through", "none", "", "merge_to_left", "merge_to_right" -> 0.0
    "slight_left" -> -40.0
    "left" -> -90.0
    "sharp_left" -> -140.0
    "slight_right" -> 40.0
    "right" -> 90.0
    "sharp_right" -> 140.0
    "reverse" -> -179.0
    else -> null
  }

  /** The arrow in the words the drawing uses ("slight right"). */
  private fun arrowWord(a: String): String = when (a) {
    "through", "none", "" -> "straight"
    "slight_left" -> "slight left"
    "slight_right" -> "slight right"
    "sharp_left" -> "sharp left"
    "sharp_right" -> "sharp right"
    "reverse" -> "uturn"
    "merge_to_left" -> "merge to left"
    "merge_to_right" -> "merge to right"
    else -> a.replace('_', ' ')
  }

  /** The road an arrow points to: the nearest in direction, on the arrow's side when it is a turn. */
  private fun roadOf(angle: Double, roads: List<Road>, lht: Boolean): Road? {
    val a = if (angle <= -179.0 && lht) 179.0 else angle
    val sideRoads = if (abs(a) >= 30) roads.filter { it.angle * a > 0 } else roads
    return (sideRoads.ifEmpty { roads }).minByOrNull { abs(it.angle - a) }
  }

  private fun rel(h: Double, from: Double): Double = ((h - from + 540.0) % 360.0) - 180.0

  /**
   * The junction of the manoeuvre at [maneuverAtM] metres along the route of [a], or null when the
   * graph does not describe it (no other road leaves there). [lht]: traffic keeps left.
   */
  /** The last junctions worked out (the screen, the voice and the junction view ask for the same one at every fix). */
  private val cache = object : LinkedHashMap<String, Any>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?) = size > 6
  }
  private val NONE = Any()

  fun at(a: RouteAnalysis, maneuverAtM: Double, lht: Boolean = false): Junction? {
    val key = "${System.identityHashCode(a)}:${Math.round(maneuverAtM / 5.0)}:$lht"
    synchronized(cache) { cache[key]?.let { return if (it === NONE) null else it as Junction } }
    val j = compute(a, maneuverAtM, lht)
    synchronized(cache) { cache[key] = j ?: NONE }
    return j
  }

  private fun compute(a: RouteAnalysis, maneuverAtM: Double, lht: Boolean): Junction? {
    if (a.edges.isEmpty() || a.length <= 0) return null
    val js = a.junctionShapes.filter { abs(it.atM - maneuverAtM) <= 35.0 }.minByOrNull { abs(it.atM - maneuverAtM) } ?: return null
    val node = js.atM
    // directions from the route's line: 30 m before and after the node (a short last piece of a way
    // gives a crooked heading)
    val inH = Geo.bearing(a.pointAt((node - 30).coerceAtLeast(0.0)), a.pointAt(node))
    val outH = Geo.bearing(a.pointAt(node), a.pointAt((node + 30).coerceAtMost(a.length)))
    val approach = a.edgeAt((node - 3).coerceAtLeast(0.0)) ?: return null
    val takenEdge = a.edgeAt((node + 15).coerceAtMost(a.length)) ?: return null
    val roads = ArrayList<Road>()
    roads += Road(rel(outH, inH), true, takenEdge.lanes, takenEdge.roadClass, takenEdge.isRamp, takenEdge.sign, takenEdge.wayId)
    for (b in js.branches) {
      val ang = rel(b.heading, inH)
      if (abs(ang) > 160) continue // the other carriageway, the way back
      roads += Road(ang, false, b.lanes, b.roadClass, b.use == "ramp", b.sign, b.wayId)
    }
    if (roads.size < 2) return null
    val taken = roads[0]
    // the other main road: the one leaving closest in direction to the road taken (the split partner)
    val other = roads.drop(1).minByOrNull { abs(it.angle - taken.angle) }!!
    val side = when {
      abs(taken.angle - other.angle) < 4 -> if (taken.angle >= 0) 1 else -1
      taken.angle > other.angle -> 1
      else -> -1
    }.let { s -> if (abs(taken.angle) < 12 && abs(other.angle) > 30) 0 else s }
    val kind = when {
      approach.roadClass in FAST && !approach.isRamp && takenEdge.isRamp -> LanePlan.Kind.EXIT
      approach.isRamp && takenEdge.roadClass in FAST && !takenEdge.isRamp -> LanePlan.Kind.MERGE
      (approach.roadClass in FAST && takenEdge.roadClass in FAST) || (approach.isRamp && takenEdge.isRamp) -> LanePlan.Kind.FORK
      else -> LanePlan.Kind.JUNCTION
    }
    // joining a motorway: no lanes to choose
    if (kind == LanePlan.Kind.MERGE) return null

    // 1. the painted arrows of the last stretch before the junction (the way may be split just
    // before it: the stretch with the arrows, unless another junction is in between)
    val arrowsEdge = turnLanesEdge(a, approach, node)
    val parts = arrowsEdge?.turnLanes?.split('|')?.map { it.trim() }
    val laneSigns = laneSignsFor(approach, parts?.size ?: approach.lanes)
    if (parts != null && parts.size >= 2) {
      val lanes = parts.map { p ->
        val arrows = p.split(';').map { it.trim().lowercase() }.ifEmpty { listOf("") }
        val toTaken = arrows.filter { ar -> arrowAngle(ar)?.let { roadOf(it, roads, lht)?.taken } == true }
        val main = toTaken.firstOrNull() ?: arrows.first()
        LanePlan.Lane(arrowWord(main), toTaken.isNotEmpty(), arrows = arrows.map { arrowWord(it) }, painted = true)
      }
      val lit = lanes.count { it.take }
      if (lit >= 1 && (lit < lanes.size || kind != LanePlan.Kind.JUNCTION)) {
        return Junction(node, kind, side, lanes, true, roads, taken.sign, other.sign, laneSigns, taken.lanes, other.lanes, approach.wayId, approach.roadClass, approach.isRamp)
      }
    }
    if (kind == LanePlan.Kind.JUNCTION) return null
    // 2. exits and forks: the lane counts, only when they add up exactly
    val n = approach.lanes
    val t = taken.lanes
    val c = other.lanes
    if (n >= 2 && t >= 1 && c >= 1 && n == t + c && side != 0) {
      val lanes = (0 until n).map { i -> LanePlan.Lane("straight", if (side > 0) i >= n - t else i < t, painted = false) }
      return Junction(node, kind, side, lanes, false, roads, taken.sign, other.sign, laneSigns, t, c, approach.wayId, approach.roadClass, approach.isRamp)
    }
    // 3. only the side
    return Junction(node, kind, side, emptyList(), false, roads, taken.sign, other.sign, null, t, c, approach.wayId, approach.roadClass, approach.isRamp)
  }

  /** The stretch whose turn:lanes describe the lanes at the junction: the last one before it, or the ones just before when the way is split. */
  private fun turnLanesEdge(a: RouteAnalysis, approach: EdgeInfo, node: Double): EdgeInfo? {
    if (approach.turnLanes != null) return approach
    val i = a.edges.indexOf(approach)
    var k = i - 1
    while (k >= 0) {
      val e = a.edges[k]
      if (e.endM < node - 150) break
      // another junction between this stretch and the node: its arrows are for that one
      if (a.junctionShapes.any { it.atM > e.endM + 1 && it.atM < node - 3 }) break
      if (e.turnLanes != null) return e.takeIf { approach.lanes == 0 || e.lanes == 0 || e.lanes == approach.lanes }
      k--
    }
    return null
  }

  private fun laneSignsFor(e: EdgeInfo, n: Int): List<LaneDest>? {
    val ls = e.laneSigns ?: return null
    val dest = ls.destination.split('|'); val ref = ls.ref.split('|'); val col = ls.colour.split('|')
    val k = maxOf(dest.size, ref.size)
    if (k < 2 || (n > 0 && k != n)) return null
    fun vals(l: List<String>, i: Int) = l.getOrNull(i)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    return (0 until k).map { i -> LaneDest(vals(dest, i), vals(ref, i), col.getOrNull(i)?.takeIf { it.isNotBlank() }) }.takeIf { l -> l.any { !it.isEmpty } }
  }

  private fun sideHint(side: Int): String = when {
    side > 0 -> "Tieni la destra"
    side < 0 -> "Tieni la sinistra"
    else -> "Prosegui dritto"
  }
}
