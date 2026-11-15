package app.navmaster.truck.ui

import app.navmaster.truck.routing.EdgeInfo
import app.navmaster.truck.routing.RouteAnalysis
import app.navmaster.truck.settings.Settings
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
 * The driving camera's stretches along a route (DriveCamera): a roundabout from the settings'
 * distance before it to just after it, a motorway exit, a turn in town only when asked; the view
 * as TomTom GO's, a little less close where the map stops at 60°. Pure Kotlin.
 */
class DriveCameraTest {
  private fun edge(start: Double, end: Double, cls: String, use: String = "", roundabout: Boolean = false) =
      EdgeInfo(start, end, cls, use, false, "paved", 2, 1, null, "IT", false, false, null, null, roundabout = roundabout)

  private fun step(pts: List<GeographicCoordinate>, type: ManeuverType, mod: ManeuverModifier?) =
      RouteStep(pts, 100.0, 10.0, null, emptyList(), "", listOf(VisualInstruction(VisualInstructionContent("", type, mod, null, emptyList(), emptyList()), null, null, 100.0)),
          emptyList(), emptyList(), emptyList(), DrivingSide.RIGHT, null)

  /**
   * A straight road east of 3 km: a roundabout at 1000–1040 m (instruction at 1000), a turn in town
   * at 1600 m, a motorway from 2000 m with its exit at 2600 m.
   */
  private fun route(): RouteAnalysis {
    val pts = (0..30).map { JvCases.at(it * 100.0, 0.0) }
    fun at(m: Double) = pts[(m / 100).toInt()]
    val route = Route(pts, BoundingBox(pts.first(), pts.last()), 3000.0, emptyList(), listOf(
        step(listOf(at(0.0), at(100.0)), ManeuverType.DEPART, null),
        step(listOf(at(1000.0), at(1100.0)), ManeuverType.ROUNDABOUT, ManeuverModifier.STRAIGHT),
        step(listOf(at(1600.0), at(1700.0)), ManeuverType.TURN, ManeuverModifier.RIGHT),
        step(listOf(at(2600.0), at(2700.0)), ManeuverType.OFF_RAMP, ManeuverModifier.SLIGHT_RIGHT),
        step(listOf(pts.last()), ManeuverType.ARRIVE, null)))
    val len = app.navmaster.truck.core.Geo.cumulative(pts).last()
    val a = RouteAnalysis(route, listOf(
        edge(0.0, 1000.0, "secondary"), edge(1000.0, 1020.0, "secondary", roundabout = true), edge(1020.0, 1040.0, "secondary", roundabout = true),
        edge(1040.0, 2000.0, "secondary"), edge(2000.0, 2600.0, "motorway"), edge(2600.0, len, "motorway", "ramp")))
    return a
  }

  @Test
  fun stretches() {
    val a = route()
    // the instructions sit where their steps start (the line's own metres differ by a few)
    val near = { m: Double -> a.maneuvers.any { kotlin.math.abs(it.first - m) < 6 } }
    assertTrue(a.maneuvers.toString(), near(1000.0) && near(1600.0) && near(2600.0))
    assertEquals(listOf(1000.0 to 1040.0), DriveCamera.roundabouts(a))
    val s = Settings()
    val ws = DriveCamera.windows(a, s)
    // the roundabout from 120 m before to 25 m after it, the exit from 300 m before to 60 m after; no turn in town
    assertEquals(2, ws.size)
    val rb = ws.first { it.roundabout }
    assertEquals(880.0, rb.fromM, 0.1)
    assertEquals(1065.0, rb.toM, 0.1)
    assertEquals(43.0, rb.tilt, 0.0)
    val ex = ws.first { !it.roundabout }
    assertTrue("$ex", kotlin.math.abs(ex.fromM - 2300.0) < 8 && kotlin.math.abs(ex.toM - 2660.0) < 8)
    assertNull(DriveCamera.windowAt(ws, 700.0))
    assertTrue(DriveCamera.windowAt(ws, 950.0)!!.roundabout)
    assertNull(DriveCamera.windowAt(ws, 1600.0))
    assertTrue(!DriveCamera.windowAt(ws, 2500.0)!!.roundabout)
    // turns in town too when asked, from 120 m before
    val town = DriveCamera.windows(a, s.copy(junctionZoomInTown = true))
    assertTrue(DriveCamera.windowAt(town, 1550.0) != null)
    // nothing at all when switched off
    assertTrue(DriveCamera.windows(a, s.copy(roundaboutView = false, junctionZoomView = false)).isEmpty())
  }

  @Test
  fun laneAndDrivenRoute() {
    // heading east, 2 m to the right is 2 m south
    val l = uniffi.ferrostar.UserLocation(JvCases.at(0.0, 0.0), 5.0, uniffi.ferrostar.CourseOverGround(90u.toUShort(), 5u.toUShort()),
        java.time.Instant.EPOCH, null)
    val r = DriveCamera.laneShift(l, 2.0)
    assertEquals(-2.0, (r.coordinates.lat - JvCases.lat0) * 110_540.0, 0.05)
    assertEquals(0.0, (r.coordinates.lng - JvCases.lon0) * JvCases.kx, 0.05)
    // a route of 3 km east cut in stretches of 800 m; at 1000 m the second stretch is split under the vehicle
    val pts = (0..30).map { JvCases.at(it * 100.0, 0.0) }
    val c = RouteChunks(pts)
    assertEquals(4, c.starts.size)
    assertEquals(1, c.chunkAt(1000.0))
    assertEquals(0, c.chunkAt(10.0))
    val (behind, ahead) = c.split(1050.0)
    val cut = JvCases.at(1050.0, 0.0)
    val lng = String.format(java.util.Locale.ROOT, "%.5f", cut.lng)
    assertTrue(behind, behind.contains(lng.dropLast(1)))
    assertTrue(ahead, ahead.contains(lng.dropLast(1)))
    assertTrue(c.json.contains("\"i\":3"))
  }

  @Test
  fun plainViewAndEasing() {
    val s = Settings()
    // TomTom GO's view where the map allows it, upright and across alike
    assertEquals(78.0 to 18.6, DriveCamera.plainView(s, false, 85.0))
    assertEquals(78.0 to 18.6, DriveCamera.plainView(s, true, 85.0))
    // the map stops at 60°: a little less close
    val capped = DriveCamera.plainView(s, false, 60.0)
    assertEquals(60.0, capped.first, 1e-9)
    assertEquals(18.6 - 18 * 0.08, capped.second, 1e-6)
    // the short way round, eased
    assertEquals(10.0, DriveCamera.lerpAngle(350.0, 30.0, 0.5), 1e-9)
    assertEquals(350.0, DriveCamera.lerpAngle(350.0, 30.0, 0.0), 1e-9)
    assertEquals(0.0, DriveCamera.ease(0.0), 0.0)
    assertEquals(1.0, DriveCamera.ease(1.0), 0.0)
    assertEquals(0.5, DriveCamera.ease(0.5), 1e-9)
  }
}
