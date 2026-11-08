package app.navmaster.truck.nav

import app.navmaster.truck.routing.Branch
import app.navmaster.truck.routing.EdgeInfo
import app.navmaster.truck.routing.JunctionShape
import app.navmaster.truck.routing.RouteAnalysis
import app.navmaster.truck.ui.JvCases
import org.junit.Assert.assertEquals
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
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

/**
 * The lanes of a junction from the lane counts alone (no arrows painted on the map): split between
 * the two roads, a lane opening for the exit, lanes shared by both roads, nothing when the counts
 * do not explain the junction; what the voice and the strip say of them; the same on a route of
 * the graph, and the junctions of that route the map lacks a sign or lanes for. Pure Kotlin.
 */
class LaneModelTest {
  private fun takes(l: List<LanePlan.Lane>?) = l?.map { if (it.added) (if (it.take) "A" else "a") else if (it.take) "T" else "-" }?.joinToString("")

  @Test
  fun lanesFromCounts() {
    val exit = LanePlan.Kind.EXIT
    val fork = LanePlan.Kind.FORK
    // 3 = 1 + 2: the right lane goes to the exit
    assertEquals("--T", takes(LaneModel.lanesFromCounts(3, 1, 2, 1, exit)))
    // 2 before, 2 going on, 1 on the slip road: a lane opens on the right before the exit
    assertEquals("--A", takes(LaneModel.lanesFromCounts(2, 1, 2, 1, exit)))
    // the same on the left
    assertEquals("A--", takes(LaneModel.lanesFromCounts(2, 1, 2, -1, exit)))
    // an exit leaves from one opening lane even when its slip road has two
    assertEquals("---A", takes(LaneModel.lanesFromCounts(3, 2, 3, 1, exit)))
    // 3 < 2 + 2: the middle lane goes both ways, lit with the right one
    assertEquals("-TT", takes(LaneModel.lanesFromCounts(3, 2, 2, 1, fork)))
    // a fork keeping all its lanes for the road taken (on the left): the other road's lane opens on the right
    assertEquals("TTTa", takes(LaneModel.lanesFromCounts(3, 3, 1, -1, fork)))
    // a lane ends somewhere (4 before, 1 + 2 after): nothing made up
    assertNull(LaneModel.lanesFromCounts(4, 1, 2, 1, exit))
    // a count missing, no side
    assertNull(LaneModel.lanesFromCounts(3, 0, 2, 1, exit))
    assertNull(LaneModel.lanesFromCounts(3, 1, 2, 0, exit))
  }

  @Test
  fun voiceAndStrip() {
    val opened = LaneModel.lanesFromCounts(2, 1, 2, 1, LanePlan.Kind.EXIT)!!
    // the lane opening for the exit is where the exit is: the voice adds nothing, the strip names it
    assertNull(LanePlan.say(opened, LanePlan.Kind.EXIT))
    assertEquals("Corsia di destra", LanePlan.hint(opened))
    // three lanes of the road itself, the right one to the exit: said
    assertEquals("usa la corsia di destra", LanePlan.say(LaneModel.lanesFromCounts(3, 1, 2, 1, LanePlan.Kind.EXIT)!!, LanePlan.Kind.EXIT))
    // an opening lane on the left does not move the road's own lanes for the voice
    val left = listOf(LanePlan.Lane("straight", false, added = true)) + (0 until 3).map { LanePlan.Lane("straight", it == 2) }
    assertEquals("usa la corsia di destra", LanePlan.say(left, LanePlan.Kind.FORK))
  }

  private fun edge(start: Double, end: Double, cls: String, use: String, lanes: Int, way: Long, sign: app.navmaster.truck.routing.EdgeSign? = null) =
      EdgeInfo(start, end, cls, use, false, "paved", lanes, way, null, "IT", false, false, null, null, sign = sign)

  private fun step(pts: List<GeographicCoordinate>, type: ManeuverType, mod: ManeuverModifier?) =
      RouteStep(pts, 100.0, 10.0, null, emptyList(), "", listOf(VisualInstruction(VisualInstructionContent("", type, mod, null, emptyList(), emptyList()), null, null, 100.0)),
          emptyList(), emptyList(), emptyList(), DrivingSide.RIGHT, null)

  /** A motorway of 2 lanes east, an exit on the right onto a slip road with no lanes on the map, the motorway going on with 2. */
  private fun exitRoute(rampSign: app.navmaster.truck.routing.EdgeSign?): RouteAnalysis {
    val ramp = JvCases.drive(0.0, 0.0, 40.0 to 8.0, 220.0 to 105.0)
    val pts = JvCases.line(-900.0 to 0.0, 0.0 to 0.0, *ramp.toTypedArray())
    val i0 = 1
    val route = Route(pts, BoundingBox(pts.first(), pts.last()), 1200.0, emptyList(),
        listOf(step(pts.subList(0, i0 + 1), ManeuverType.DEPART, null), step(pts.subList(i0, pts.size), ManeuverType.OFF_RAMP, ManeuverModifier.SLIGHT_RIGHT),
            step(listOf(pts.last()), ManeuverType.ARRIVE, null)))
    val len = app.navmaster.truck.core.Geo.cumulative(pts).last()
    val a = RouteAnalysis(route, listOf(edge(0.0, 900.0, "motorway", "", 2, 1), edge(900.0, len, "motorway", "ramp", 0, 2, rampSign)))
    a.junctionShapes = listOf(JunctionShape(900.0, 90.0, 100.0, 0, listOf(Branch(90.0, "motorway", 2, "", 3))))
    return a
  }

  @Test
  fun exitOnTheGraph() {
    val a = exitRoute(null)
    // the exit's instruction found on the route's line where the slip road starts (within a few metres)
    assertEquals(1, a.maneuvers.count { kotlin.math.abs(it.first - 900.0) < 5 && it.second == "OFF_RAMP" })
    val j = LaneModel.at(a, 900.0)!!
    assertEquals(LanePlan.Kind.EXIT, j.kind)
    assertEquals(1, j.side)
    // the slip road without its lanes on the map counts as one: the deceleration lane on the right
    assertEquals("--A", takes(j.lanes))
    // on the list of the junctions to complete: the sign is missing, the lanes are known
    val gaps = MapGaps.scan(a, false, "2026-11-08")
    assertEquals(1, gaps.size)
    val g = gaps.single().second!!
    assertTrue(g.noSign)
    assertTrue(!g.noLanes)
    assertEquals("Uscita", g.kind)
    assertTrue(g.editUrl, g.editUrl.startsWith("https://www.openstreetmap.org/edit?editor=id&way=2#map=19/"))
    // with the sign on the slip road: complete, off the list
    val signed = exitRoute(app.navmaster.truck.routing.EdgeSign(listOf("12"), listOf("SS9"), listOf("Lodi"), emptyList()))
    assertNull(MapGaps.scan(signed, false, "2026-11-08").single().second)
    assertTrue(MapGaps.gpx(listOf(g)).contains("<wpt lat="))
  }
}
