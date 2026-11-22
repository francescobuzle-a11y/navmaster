package app.navmaster.truck.nav

import app.navmaster.truck.core.Geo
import app.navmaster.truck.routing.RouteAnalysis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.ferrostar.BoundingBox
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

/**
 * The quick route after a wrong turn on a long trip: a short route from where the vehicle is to a
 * point R of the old route some kilometres ahead (one country, a fraction of a second), joined to
 * the old route from R to the destination, which is already computed. A full new route of 1 100 km
 * across four countries took 4-9 s on the tablet (most of it choosing the border crossings again);
 * the joined one is ready in the time of the short one. The full route is still computed meanwhile
 * and takes its place when it is really better (NavViewModel).
 *
 * Joined here: the guidance's [Route] (Ferrostar) and the roads of the route (OrsGuide's attributes,
 * which RouteAnalysis reads), so the joined route is analysed as any other one.
 */
object RouteSplice {
  /** Where the short route rejoins the old one: the vertex [index] of the old line, its metres [alongM], the old route's direction there. */
  data class Rejoin(val index: Int, val alongM: Double, val bearing: Double, val point: GeographicCoordinate)

  /** Below this much old route still to drive the full route is quick anyway (one country, a few hundred km at most). */
  const val MIN_REMAINING_M = 40_000.0

  /**
   * The point R of the old route [a] to rejoin, past [fromM] (the metres driven on it when the
   * vehicle left it): 5 km on (2 km off the motorways), up to 30 km, on a plain stretch: not within
   * 300 m of a manoeuvre, 150 m of a crossing, 400 m of a toll booth or border, not on a ramp, a
   * roundabout or in a tunnel, before the next stop still to make ([stopsAlongM], 500 m before it),
   * in the country the vehicle is in. Null: the full route instead (a short trip, a stop just ahead,
   * no plain stretch, a border just ahead).
   */
  fun pickRejoin(a: RouteAnalysis, fromM: Double, stopsAlongM: List<Double>): Rejoin? {
    val g = a.route.geometry
    val cum = a.cum
    if (g.size < 3 || a.edges.isEmpty() || a.length - fromM < MIN_REMAINING_M) return null
    val here = a.edgeAt(fromM.coerceAtLeast(0.0))
    val country = here?.country
    val major = here?.roadClass in setOf("motorway", "trunk")
    val firstM = fromM + if (major) 5_000.0 else 2_000.0
    val lastM = minOf(fromM + 30_000.0, (stopsAlongM.filter { it > fromM }.minOrNull() ?: Double.MAX_VALUE) - 500.0, a.length - 20_000.0)
    if (lastM <= firstM) return null
    val manoeuvres = a.maneuvers.map { it.first }
    val nodes = (a.tollBooths + a.borders).map { it.alongM }
    var i = java.util.Arrays.binarySearch(cum, firstM).let { if (it < 0) -it - 1 else it }
    while (i < g.size - 2 && cum[i] <= lastM) {
      val m = cum[i]
      val e = a.edgeAt(m + 1.0)
      val ok = e != null && !e.isRamp && !e.roundabout && !e.tunnel && e.use != "ferry" &&
          (country == null || e.country == null || e.country == country) &&
          manoeuvres.none { kotlin.math.abs(it - m) < 300.0 } &&
          nodes.none { kotlin.math.abs(it - m) < 400.0 } &&
          !a.junctionNear(m, 150.0) &&
          // a vertex with room on both sides (the engine puts R on this road, not on the next one)
          cum[i] - cum[i - 1] > 0.5 && cum[i + 1] - cum[i] > 0.5
      if (ok) return Rejoin(i, m, Geo.bearing(g[i], g[i + 1]), g[i])
      i++
    }
    return null
  }

  /** The vertex of the route line each step starts at (steps share their end and start points). */
  private fun stepStarts(r: Route): IntArray {
    val out = IntArray(r.steps.size)
    var at = 0
    for ((k, s) in r.steps.withIndex()) {
      out[k] = at
      // a step of one point repeated (the arrival of each leg, a step of no length): the next one
      // starts at the same vertex
      val g = s.geometry
      if (g.size >= 2 && g.any { it != g[0] }) at += g.size - 1
    }
    return out
  }

  /**
   * The joined guidance: [head] (the short route from [start] to R, its arrival dropped) and [old]
   * from its vertex [r].index on. The head's last step goes on along the rest of the old step R is
   * in, with that old step's banners and voice (their distances count back from the step's end,
   * which has not moved); the old steps after it as they are. The waypoints: the vehicle, the old
   * route's stops still ahead of R ([oldCum] its metres), the destination. Null when the two do not
   * fit (a step layout not as expected).
   */
  fun route(old: Route, oldCum: DoubleArray, r: Rejoin, head: Route, start: GeographicCoordinate): Route? {
    if (head.steps.size < 2 || old.steps.isEmpty() || r.index !in 1 until old.geometry.size - 1) return null
    val starts = stepStarts(old)
    // the old step R is in: it starts at or before R and ends after it
    var k = -1
    for (j in old.steps.indices) {
      val len = old.steps[j].geometry.size
      if (len >= 2 && starts[j] <= r.index && r.index < starts[j] + len - 1) { k = j; break }
    }
    if (k < 0) return null
    val sk = old.steps[k]
    val li = r.index - starts[k]
    if (sk.geometry.getOrNull(li) != old.geometry[r.index]) return null
    val skCum = Geo.cumulative(sk.geometry)
    val skLen = skCum.lastOrNull() ?: 0.0
    val share = if (skLen > 0) ((skLen - skCum[li]) / skLen).coerceIn(0.0, 1.0) else 0.0
    // the head without its arrival (the steps that move; the arrival is a step of one point repeated)
    val headSteps = head.steps.dropLastWhile { it.geometry.size < 2 || (Geo.cumulative(it.geometry).lastOrNull() ?: 0.0) < 0.5 }
        .let { if (it.size == head.steps.size) it.dropLast(1) else it }
    if (headSteps.isEmpty()) return null
    val last = headSteps.last()
    val mergedGeometry = last.geometry + sk.geometry.drop(li + 1)
    val mergedLen = Geo.cumulative(mergedGeometry).lastOrNull() ?: 0.0
    // the old step's first banner shown from the start of the joined step (its distance counts back
    // from the step's end: a joined step longer than the old one had no banner over its first part)
    val firstTrigger = sk.visualInstructions.maxOfOrNull { it.triggerDistanceBeforeManeuver }
    val merged = last.copy(
        geometry = mergedGeometry,
        distance = last.distance + sk.distance * share,
        duration = last.duration + sk.duration * share,
        visualInstructions = sk.visualInstructions.map {
          if (it.triggerDistanceBeforeManeuver == firstTrigger && mergedLen > it.triggerDistanceBeforeManeuver) it.copy(triggerDistanceBeforeManeuver = mergedLen) else it
        },
        spokenInstructions = sk.spokenInstructions,
        annotations = null,
    )
    val steps: List<RouteStep> = headSteps.dropLast(1) + merged + old.steps.drop(k + 1)
    // the head's line and the old one after R, nothing else (the attributes are joined the same way;
    // the arrival steps repeat their last point, so the line is not rebuilt from the steps)
    if (head.geometry.lastOrNull() != last.geometry.lastOrNull()) return null
    val geometry = ArrayList<GeographicCoordinate>(head.geometry.size + old.geometry.size - r.index)
    geometry += head.geometry
    geometry += old.geometry.subList(r.index + 1, old.geometry.size)
    // the stops still ahead of R on the old route, then the destination (R is not a stop)
    val wps = old.waypoints
    fun alongOf(c: GeographicCoordinate): Double {
      var best = Double.MAX_VALUE
      var at = 0.0
      for (n in old.geometry.indices) {
        val d = Geo.dist(old.geometry[n], c)
        if (d < best) { best = d; at = oldCum[n] }
      }
      return at
    }
    val ahead = wps.drop(1).dropLast(1).filter { alongOf(it.coordinate) > r.alongM + 50 } + listOfNotNull(wps.lastOrNull())
    val first = wps.firstOrNull()?.copy(coordinate = start) ?: Waypoint(coordinate = start, kind = WaypointKind.BREAK)
    var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
    for (p in geometry) { s = minOf(s, p.lat); n = maxOf(n, p.lat); w = minOf(w, p.lng); e = maxOf(e, p.lng) }
    return old.copy(geometry = geometry, bbox = BoundingBox(GeographicCoordinate(s, w), GeographicCoordinate(n, e)),
        distance = steps.sumOf { it.distance }, waypoints = listOf(first) + ahead, steps = steps)
  }

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * The roads of the joined route (OrsGuide's attributes, as RouteAnalysis reads them): the head's,
   * then the old route's from its vertex [r].index on, their vertex numbers and metres moved to the
   * joined line ([joined] its geometry); the road R is on keeps its facts but not the sign at its
   * start (that sign is behind). Null when a line does not match its route (then no join).
   */
  fun attributes(oldJson: String, headJson: String, r: Rejoin, oldGeometrySize: Int, headGeometry: List<GeographicCoordinate>,
                 joined: List<GeographicCoordinate>): String? {
    val old = json.parseToJsonElement(oldJson).jsonObject
    val head = json.parseToJsonElement(headJson).jsonObject
    val oldShape = old["shape"]?.jsonPrimitive?.contentOrNull?.let { Geo.decodePolyline6(it) } ?: return null
    val headShape = head["shape"]?.jsonPrimitive?.contentOrNull?.let { Geo.decodePolyline6(it) } ?: return null
    if (oldShape.size != oldGeometrySize || headShape.size != headGeometry.size) return null
    val headN = headShape.size
    val shift = headN - 1 - r.index
    fun map(j: Int): Int = if (j <= r.index) headN - 1 else j + shift
    val headLen = Geo.cumulative(headGeometry).lastOrNull() ?: 0.0
    // the countries: the head's first (its edges keep their numbers), the old ones added
    val admins = ArrayList<JsonElement>(head["admins"]?.jsonArray ?: JsonArray(emptyList()))
    val codes = admins.map { (it as? JsonObject)?.get("country_code")?.jsonPrimitive?.contentOrNull }.toMutableList()
    val oldAdmins = old["admins"]?.jsonArray ?: JsonArray(emptyList())
    val adminMap = IntArray(oldAdmins.size) { idx ->
      val c = (oldAdmins[idx] as? JsonObject)?.get("country_code")?.jsonPrimitive?.contentOrNull
      val at = codes.indexOf(c)
      if (at >= 0) at else { admins += oldAdmins[idx]; codes += c; admins.size - 1 }
    }
    val edges = ArrayList<JsonElement>(head["edges"]?.jsonArray ?: JsonArray(emptyList()))
    for (el in old["edges"]?.jsonArray ?: JsonArray(emptyList())) {
      val e = el as? JsonObject ?: continue
      val b = e["begin_shape_index"]?.jsonPrimitive?.intOrNull ?: continue
      val en = e["end_shape_index"]?.jsonPrimitive?.intOrNull ?: continue
      if (en <= r.index) continue
      val m = LinkedHashMap<String, JsonElement>(e)
      m["begin_shape_index"] = JsonPrimitive(map(maxOf(b, r.index)))
      m["end_shape_index"] = JsonPrimitive(map(en))
      // the road R is on: its start (and the sign there) is behind
      if (b < r.index) m.remove("sign")
      (e["end_node"] as? JsonObject)?.let { node ->
        val ai = node["admin_index"]?.jsonPrimitive?.intOrNull
        if (ai != null && ai in adminMap.indices) m["end_node"] = JsonObject(LinkedHashMap<String, JsonElement>(node).also { it["admin_index"] = JsonPrimitive(adminMap[ai]) })
      }
      edges += JsonObject(m)
    }
    val points = ArrayList<JsonElement>(head["points"]?.jsonArray ?: JsonArray(emptyList()))
    for (el in old["points"]?.jsonArray ?: JsonArray(emptyList())) {
      val p = el as? JsonObject ?: continue
      val off = p["offset"]?.jsonPrimitive?.doubleOrNull ?: continue
      if (off <= r.alongM) continue
      points += JsonObject(LinkedHashMap<String, JsonElement>(p).also { it["offset"] = JsonPrimitive(off - r.alongM + headLen) })
    }
    val out = LinkedHashMap<String, JsonElement>()
    out["units"] = old["units"] ?: JsonPrimitive("kilometers")
    out["shape"] = JsonPrimitive(Geo.encodePolyline6(joined))
    out["admins"] = JsonArray(admins)
    out["edges"] = JsonArray(edges)
    out["points"] = JsonArray(points)
    if (old["partial_ways"]?.jsonPrimitive?.booleanOrNull == true || head["partial_ways"]?.jsonPrimitive?.booleanOrNull == true) out["partial_ways"] = JsonPrimitive(true)
    return JsonObject(out).toString()
  }
}
