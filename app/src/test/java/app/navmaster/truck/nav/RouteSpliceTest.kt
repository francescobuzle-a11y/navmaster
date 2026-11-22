package app.navmaster.truck.nav

import app.navmaster.truck.core.Geo
import app.navmaster.truck.routing.EdgeInfo
import app.navmaster.truck.routing.RouteAnalysis
import app.navmaster.truck.ui.JvCases
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ferrostar.BoundingBox
import uniffi.ferrostar.DrivingSide
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

/**
 * The joined route of a wrong turn on a long trip (RouteSplice): a short route to a point R of the
 * old one, joined to the old one from R. The guidance's steps must fit end to end with their
 * distances, the step R is in keeps its banners and voice, R is no stop, the stops ahead stay, and
 * the roads (OrsGuide's attributes) are moved to the joined line. Pure Kotlin.
 */
class RouteSpliceTest {
  /** A point [e] metres east and [n] metres north of the test origin, on a 1 m grid (polyline6 keeps it). */
  private fun at(e: Double, n: Double = 0.0): GeographicCoordinate = JvCases.at(e, n).let { Geo.decodePolyline6(Geo.encodePolyline6(listOf(it))).first() }

  private fun step(pts: List<GeographicCoordinate>, type: ManeuverType, name: String, banner: String = name): RouteStep {
    val len = Geo.cumulative(pts).lastOrNull() ?: 0.0
    return RouteStep(pts, len, len / 20.0, name, emptyList(), name,
        listOf(VisualInstruction(VisualInstructionContent(banner, type, ManeuverModifier.STRAIGHT, null, emptyList(), emptyList()), null, null, len)),
        listOf(SpokenInstruction(banner, null, len, java.util.UUID.nameUUIDFromBytes(banner.toByteArray()))), null, emptyList(), DrivingSide.RIGHT, null)
  }

  private fun route(pts: List<GeographicCoordinate>, steps: List<RouteStep>, wps: List<Waypoint>) =
      Route(pts, BoundingBox(pts.first(), pts.last()), steps.sumOf { it.distance }, wps, steps)

  /** 100 km east, a vertex every 500 m; manoeuvres at 0, 30 km and 70 km; a stop at 60 km; the end. */
  private fun oldRoute(): Route {
    val pts = (0..200).map { at(it * 500.0) }
    fun slice(a: Int, b: Int) = pts.subList(a, b + 1)
    val steps = listOf(
        step(slice(0, 60), ManeuverType.DEPART, "SS10"),
        step(slice(60, 140), ManeuverType.CONTINUE, "A21", "Verso Brescia"),
        step(slice(140, 200), ManeuverType.TURN, "A4", "Esci verso Milano"),
        step(listOf(pts[200], pts[200]), ManeuverType.ARRIVE, "Arrivo"),
    )
    val wps = listOf(Waypoint(pts[0], WaypointKind.BREAK), Waypoint(pts[120], WaypointKind.BREAK), Waypoint(pts[200], WaypointKind.BREAK))
    return route(pts, steps, wps)
  }

  private fun analysis(r: Route) = RouteAnalysis(r, listOf(EdgeInfo(0.0, 100_000.0, "primary", "road", false, "paved", 2, 1, "SS10", "IT", false, false, null, null)))

  @Test
  fun picksAPlainStretchAheadAndNotPastAStop() {
    val a = analysis(oldRoute())
    // left the route at 31 km: R 2 km on (not a motorway), more than 300 m from the manoeuvre at 30 km
    val r = RouteSplice.pickRejoin(a, 31_000.0, listOf(60_000.0))!!
    assertTrue("R at ${r.alongM}", r.alongM >= 33_000.0 && r.alongM <= 34_000.0)
    assertEquals(90.0, r.bearing, 2.0)
    // a stop 1 km ahead: no join (the full route instead)
    assertNull(RouteSplice.pickRejoin(a, 58_500.0, listOf(60_000.0)))
    // close to the end: the full route
    assertNull(RouteSplice.pickRejoin(a, 75_000.0, emptyList()))
  }

  /** The short route: from 300 m north of the old line at 33 km, down to R and east along it. */
  private fun head(r: RouteSplice.Rejoin): Route {
    val rx = r.alongM
    // (it ends on R itself, as the engine puts it on the old road)
    val pts = listOf(at(rx - 2_000, 300.0), at(rx - 1_000, 300.0), at(rx - 500, 150.0), r.point)
    val steps = listOf(
        step(pts.subList(0, 2), ManeuverType.DEPART, "SP1"),
        step(pts.subList(1, 4), ManeuverType.TURN, "Rampa", "Verso A21"),
        step(listOf(pts[3], pts[3]), ManeuverType.ARRIVE, "Arrivo"),
    )
    return route(pts, steps, listOf(Waypoint(pts[0], WaypointKind.BREAK), Waypoint(pts[3], WaypointKind.BREAK)))
  }

  @Test
  fun joinedStepsFitEndToEnd() {
    val old = oldRoute()
    val a = analysis(old)
    val r = RouteSplice.pickRejoin(a, 31_000.0, listOf(60_000.0))!!
    val h = head(r)
    val j = RouteSplice.route(old, a.cum, r, h, h.geometry.first())!!
    // the line: the head's, then the old one after R
    assertEquals(h.geometry.size + old.geometry.size - r.index - 1, j.geometry.size)
    assertEquals(old.geometry.last(), j.geometry.last())
    // steps end to end, their distances adding up
    for (k in 1 until j.steps.size) assertEquals(j.steps[k - 1].geometry.last(), j.steps[k].geometry.first())
    assertEquals(j.distance, j.steps.sumOf { it.distance }, 1e-6)
    val lineLen = Geo.cumulative(j.geometry).last()
    assertEquals(lineLen, j.distance, 5.0)
    // the head's last step goes on along the old A21 step: its own manoeuvre, the old step's banner
    val merged = j.steps.first { it.roadName == "Rampa" }
    assertEquals("Verso Brescia", merged.visualInstructions.first().primaryContent.text)
    assertEquals("Verso Brescia", merged.spokenInstructions.first().text)
    // the old steps after it as they were, the old arrival last; the head's arrival dropped
    assertEquals(listOf("SP1", "Rampa", "A4", "Arrivo"), j.steps.map { it.roadName })
    // waypoints: the vehicle, the stop at 60 km (ahead of R), the destination; R is no stop
    assertEquals(3, j.waypoints.size)
    assertEquals(h.geometry.first(), j.waypoints.first().coordinate)
    assertEquals(old.geometry[120], j.waypoints[1].coordinate)
  }

  @Test
  fun roadsMoveToTheJoinedLine() {
    val old = oldRoute()
    val a = analysis(old)
    val r = RouteSplice.pickRejoin(a, 31_000.0, listOf(60_000.0))!!
    val h = head(r)
    val j = RouteSplice.route(old, a.cum, r, h, h.geometry.first())!!
    fun attrs(pts: List<GeographicCoordinate>, edges: List<Triple<Int, Int, String>>, admins: List<String>, points: List<Double>): String =
        """{"units":"kilometers","shape":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(Geo.encodePolyline6(pts)))},""" +
            """"admins":[${admins.joinToString(",") { "{\"country_code\":\"$it\"}" }}],"edges":[""" +
            edges.joinToString(",") { (b, e, name) -> """{"way_id":1,"begin_shape_index":$b,"end_shape_index":$e,"names":["$name"],"sign":{"exit_toward":[{"text":"x"}]},"end_node":{"admin_index":${admins.size - 1},"type":"street_intersection"}}""" } +
            """],"points":[${points.joinToString(",") { """{"type":"speed_camera","lat":0,"lon":0,"value":50,"bearing":90,"offset":$it,"distance":3}""" }}]}"""
    val oldJson = attrs(old.geometry, listOf(Triple(0, 60, "SS10"), Triple(60, 140, "A21"), Triple(140, 200, "A4")), listOf("AT", "IT"), listOf(10_000.0, 50_000.0))
    val headJson = attrs(h.geometry, listOf(Triple(0, 1, "SP1"), Triple(1, 3, "Rampa")), listOf("IT"), listOf(500.0))
    val out = RouteSplice.attributes(oldJson, headJson, r, old.geometry.size, h.geometry, j.geometry)!!
    val o = Json.parseToJsonElement(out).jsonObject
    assertEquals(j.geometry.size, Geo.decodePolyline6(o["shape"]!!.jsonPrimitive.content).size)
    val edges = o["edges"]!!.jsonArray.map { it.jsonObject }
    // head edges, then the old A21 (from R on, its start sign gone) and A4, contiguous to the end
    assertEquals(listOf("SP1", "Rampa", "A21", "A4"), edges.map { it["names"]!!.jsonArray.first().jsonPrimitive.content })
    for (k in 1 until edges.size) assertEquals(edges[k - 1]["end_shape_index"]!!.jsonPrimitive.int, edges[k]["begin_shape_index"]!!.jsonPrimitive.int)
    assertEquals(j.geometry.size - 1, edges.last()["end_shape_index"]!!.jsonPrimitive.int)
    assertNull(edges[2]["sign"])
    assertNotNull(edges[3]["sign"])
    // the old country list joined to the head's: IT kept at its index, AT added
    val admins = o["admins"]!!.jsonArray.map { it.jsonObject["country_code"]!!.jsonPrimitive.content }
    assertEquals(listOf("IT", "AT"), admins)
    assertEquals(0, edges[3]["end_node"]!!.jsonObject["admin_index"]!!.jsonPrimitive.int)
    // the camera at 50 km of the old route: moved by (head length − R); the one at 10 km (behind) gone
    val headLen = Geo.cumulative(h.geometry).last()
    val offs = o["points"]!!.jsonArray.map { it.jsonObject["offset"]!!.jsonPrimitive.double }
    assertEquals(2, offs.size)
    assertEquals(50_000.0 - r.alongM + headLen, offs[1], 1e-6)
  }

  @Test
  fun joinsOnARouteWithAStopBehind() {
    // two legs: the stop at 60 km already passed (its arrival step repeats its point), R in leg 2
    val pts = (0..200).map { at(it * 500.0) }
    fun slice(a: Int, b: Int) = pts.subList(a, b + 1)
    val steps = listOf(
        step(slice(0, 60), ManeuverType.DEPART, "SS10"),
        step(slice(60, 120), ManeuverType.CONTINUE, "A21"),
        step(listOf(pts[120], pts[120]), ManeuverType.ARRIVE, "Tappa"),
        step(slice(120, 140), ManeuverType.DEPART, "A21b", "Verso Brescia"),
        step(slice(140, 200), ManeuverType.TURN, "A4", "Esci verso Milano"),
        step(listOf(pts[200], pts[200]), ManeuverType.ARRIVE, "Arrivo"),
    )
    val old = route(pts, steps, listOf(Waypoint(pts[0], WaypointKind.BREAK), Waypoint(pts[120], WaypointKind.BREAK), Waypoint(pts[200], WaypointKind.BREAK)))
    val a = analysis(old)
    val r = RouteSplice.pickRejoin(a, 59_000.0, emptyList())!!
    assertTrue(r.index in 121 until 140)
    val h = head(r)
    val j = RouteSplice.route(old, a.cum, r, h, h.geometry.first())!!
    assertEquals(listOf("SP1", "Rampa", "A4", "Arrivo"), j.steps.map { it.roadName })
    assertEquals(old.geometry[r.index + 1], j.steps[1].geometry[h.steps[1].geometry.size])
    // the passed stop is not a waypoint any more: the vehicle, then the destination
    assertEquals(2, j.waypoints.size)
  }
}
