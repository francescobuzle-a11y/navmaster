package app.navmaster.truck.ui

import app.navmaster.truck.core.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the route's line is cut into "already driven" and "ahead" (NmDrivenRoute). After a jump of
 * the simulation the guidance draws only the end of the route while the position is measured on the
 * whole route: cut at the whole route's metres, the first kilometres ahead of the vehicle were drawn
 * as driven and the blue line stayed grey. Pure Kotlin.
 */
class DrivenRouteTest {
  @Test
  fun positionOnTheDrawnLine() {
    // the line drawn is the whole route (0 = not told, or the same length): the position as it is
    assertEquals(1000.0, onDrawnLine(1000.0, 0.0, 5000.0), 1e-9)
    assertEquals(1000.0, onDrawnLine(1000.0, 5000.0, 5000.0), 1e-9)
    assertEquals(1000.0, onDrawnLine(1000.0, 5000.4, 5000.0), 1e-9)
    // only the end of a 71 km route, from 15 km on: the vehicle 200 m after the start of the line
    assertEquals(200.0, onDrawnLine(15_200.0, 71_000.0, 56_000.0), 1e-9)
    assertEquals(0.0, onDrawnLine(15_000.0, 71_000.0, 56_000.0), 1e-9)
    // near the end of the line
    assertEquals(55_990.0, onDrawnLine(70_990.0, 71_000.0, 56_000.0), 1e-9)
    // not on the line (the old line while a new one comes, a jump back): all of it ahead, none driven
    assertEquals(0.0, onDrawnLine(12_000.0, 71_000.0, 56_000.0), 1e-9)
    assertEquals(0.0, onDrawnLine(80_000.0, 71_000.0, 56_000.0), 1e-9)
    // a few metres before the start (the jump lands a little before the line): clamped
    assertEquals(0.0, onDrawnLine(14_980.0, 71_000.0, 56_000.0), 1e-9)
  }

  /** The longitudes of the points of a GeoJSON line text (RouteChunks.split). */
  private fun lngs(json: String): List<Double> =
      Regex("""\[(-?\d+\.\d+),(-?\d+\.\d+)]""").findAll(json).map { it.groupValues[1].toDouble() }.toList()

  @Test
  fun blueLineAheadAfterAJump() {
    // a 71 km route east in stretches of 800 m; the simulation puts the vehicle at 15 km and the
    // guidance goes on with the end of the route, starting under the vehicle
    val whole = (0..710).map { JvCases.at(it * 100.0, 0.0) }
    val cum = Geo.cumulative(whole)
    val tail = whole.drop(150)
    val chunks = RouteChunks(tail)
    val vehicle = 152                       // 200 m after the start of the line drawn
    val vehicleLng = whole[vehicle].lng
    val along = onDrawnLine(cum[vehicle], cum.last(), chunks.cum.last())
    assertEquals(200.0, along, 3.0)

    val (behind, ahead) = chunks.split(along)
    val b = lngs(behind)
    val a = lngs(ahead)
    // the driven part is the 200 m behind the vehicle (from the start of the line to the vehicle) ...
    assertEquals(whole[150].lng, b.min(), 1e-6)
    assertEquals(vehicleLng, b.max(), 2e-5)
    // ... and the line ahead goes on from the vehicle to the end of its stretch (the stretches after it
    // are drawn whole by the layer's filter: i > the stretch the vehicle is in)
    assertEquals(vehicleLng, a.min(), 2e-5)
    assertEquals(tail[chunks.starts[1]].lng, a.max(), 1e-6)
    assertEquals("the vehicle is in the first stretch of the line drawn", 0, chunks.chunkAt(along))

    // the vehicle really is at the cut; at the whole route's metres it would be 15 km away from it
    assertEquals(0.0, chunks.distanceTo(along, whole[vehicle]), 3.0)
    assertTrue(chunks.distanceTo(cum[vehicle], whole[vehicle]) > OFF_LINE_M)

    // what the cut was at the whole route's metres (the old way): 15 km into the drawn line, so about
    // 18 stretches (14 km) ahead of the vehicle were drawn as driven, grey
    assertTrue(chunks.chunkAt(cum[vehicle]) > 10)
  }
}
