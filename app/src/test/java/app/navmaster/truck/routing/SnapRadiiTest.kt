package app.navmaster.truck.routing

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop the vehicle cannot reach within openrouteservice's 400 m: the point of the error, the
 * wider radii tried (2 km, then 5 km, remembered by coordinate), how far the point was moved, the
 * words for the driver.
 */
class SnapRadiiTest {
  private val spec = RouteSpec(hgv = true, vehicleType = "hgv", heightM = 4.0, widthM = 2.55, lengthM = 16.5, weightT = 27.0, axleLoadT = 11.5,
      hazmat = false, topSpeedKmh = 80.0, avoidTolls = false, avoidFerries = true, avoidMotorways = false, preference = "recommended")
  private val three = listOf(doubleArrayOf(45.46, 9.19), doubleArrayOf(45.5416, 10.2118), doubleArrayOf(44.49, 11.34))
  private val error2010 = "Could not find routable point within a radius of 400 meters of specified coordinate 1: 10.2118000 45.5416000."

  @Test
  fun pointOfTheError() {
    assertEquals(1, NmRouting.pointOf(error2010))
    assertEquals(0, NmRouting.pointOf("... of specified coordinate 0: 9.1 45.4."))
    assertEquals(12, NmRouting.pointOf("coordinate 12:"))
    assertNull(NmRouting.pointOf("Could not find routable point"))
  }

  @Test
  fun wordsNameThePointAndTheRadius() {
    assertEquals("nessuna strada percorribile entro 400 m dalla tappa 1", NmRouting.words(2010, error2010, 3))
    assertEquals("nessuna strada percorribile entro 400 m dalla destinazione", NmRouting.words(2010, error2010, 2))
    assertEquals("nessuna strada percorribile entro 400 m dalla partenza", NmRouting.words(2010, error2010.replace("coordinate 1", "coordinate 0"), 3))
    // after the widest radius: what to do
    assertEquals("nessuna strada per il mezzo entro 5,0 km dalla tappa 1: toglila dal viaggio o spostala", NmRouting.words(2010, error2010, 3, 5000.0))
    assertEquals("nessuna strada per il mezzo entro 2,0 km da un punto: toglila dal viaggio o spostala", NmRouting.words(2010, "no coordinate", 3, 2000.0))
    assertEquals("nessun percorso tra i punti con le misure e le scelte del mezzo", NmRouting.words(2009, "", 3))
  }

  @Test
  fun metresForTheDriver() {
    assertEquals("650 m", NmRouting.metres(652.0))
    assertEquals("1,3 km", NmRouting.metres(1311.0))
    assertEquals("5,0 km", NmRouting.metres(5000.0))
  }

  @Test
  fun radiiWidenInStepsAndStayWithTheCoordinate() {
    val r = SnapRadii()
    assertEquals(400.0, r.radiusOf(45.5416, 10.2118), 1e-9)
    assertEquals(2000.0, r.widen(45.5416, 10.2118)!!, 1e-9)
    assertEquals(2000.0, r.radiusOf(45.5416, 10.2118), 1e-9)
    assertEquals(5000.0, r.widen(45.5416, 10.2118)!!, 1e-9)
    // the widest already in use: nothing more to try
    assertNull(r.widen(45.5416, 10.2118))
    assertEquals(5000.0, r.radiusOf(45.5416, 10.2118), 1e-9)
    // the other points keep their 400 m; the same coordinate within a metre is the same point
    assertTrue(r.radiiOf(three).contentEquals(doubleArrayOf(400.0, 5000.0, 400.0)))
    assertEquals(5000.0, r.radiusOf(45.541601, 10.211799), 1e-9)
    r.clear()
    assertEquals(400.0, r.radiusOf(45.5416, 10.2118), 1e-9)
    // enough retries for four stops tried up to the widest radius
    assertEquals(SnapRadii.WIDER.size * 4, SnapRadii.MAX_RETRIES)
    assertTrue(SnapRadii.WIDER.contentEquals(doubleArrayOf(2000.0, 5000.0)))
  }

  @Test
  fun onlyTheStopsAreAskedAgainWithAWiderRadius() {
    val kinds = listOf("break", "via", "break", "break")
    // the start (the vehicle's position while driving) never
    assertTrue(!NmRouting.widens(0, kinds))
    // the recalculations' own point ahead on the road (passed through, with a bearing) never
    assertTrue(!NmRouting.widens(1, kinds, listOf(90.0, 45.0, null, null)))
    // a point the driver put on the map (passed through, no bearing): yes
    assertTrue(NmRouting.widens(1, kinds))
    assertTrue(NmRouting.widens(1, kinds, listOf(90.0, null, null, null)))
    // the stops and the destination
    assertTrue(NmRouting.widens(2, kinds))
    assertTrue(NmRouting.widens(3, kinds))
    // without the kinds: every point after the start
    assertTrue(NmRouting.widens(1, emptyList()))
    assertTrue(!NmRouting.widens(0, emptyList()))
  }

  @Test
  fun radiiOnlyInTheBodyWhenWidened() {
    val plain = NmRouting.request(three, spec, listOf("break", "break", "break"), 0, null, radiuses = null)
    assertNull(plain["radiuses"])
    val widened = NmRouting.request(three, spec, listOf("break", "break", "break"), 0, null, radiuses = doubleArrayOf(400.0, 2000.0, 400.0))
    assertEquals(listOf(400.0, 2000.0, 400.0), widened["radiuses"]!!.jsonArray.map { it.jsonPrimitive.double })
    // the server gets them too
    val server = NmRouting.request(three, spec, listOf("break", "break", "break"), 0, null, forServer = true, radiuses = doubleArrayOf(400.0, 2000.0, 400.0))
    assertEquals(3, server["radiuses"]!!.jsonArray.size)
    // a wrong count is left out
    assertNull(NmRouting.request(three, spec, listOf("break", "break", "break"), 0, null, radiuses = doubleArrayOf(400.0, 2000.0))["radiuses"])
  }

  @Test
  fun movedPointsMeasuredFromTheWayPoint() {
    // the route: start, the stop moved about 1.3 km east, the destination
    val lat = doubleArrayOf(45.46, 45.50, 45.5416, 45.52, 44.49)
    val lon = doubleArrayOf(9.19, 9.70, 10.2285, 10.80, 11.34)
    val route = RouteResult(lat, lon, intArrayOf(0, 2, 4), 250_000.0, 10_000_000L)
    val moved = NmRouting.moved(three, doubleArrayOf(400.0, 2000.0, 400.0), route)
    assertEquals(1, moved.size)
    assertEquals(1, moved[0].index)
    assertEquals(2000.0, moved[0].radiusM, 1e-9)
    assertEquals(1300.0, moved[0].meters, 60.0)
    // where the stop is really reached: the route's way point
    assertEquals(45.5416, moved[0].lat, 1e-9)
    assertEquals(10.2285, moved[0].lon, 1e-9)
    // a wider radius with the point within 400 m of the road: not moved; no radius widened: nothing
    val near = RouteResult(lat, doubleArrayOf(9.19, 9.70, 10.2130, 10.80, 11.34), intArrayOf(0, 2, 4), 250_000.0, 10_000_000L)
    assertTrue(NmRouting.moved(three, doubleArrayOf(400.0, 2000.0, 400.0), near).isEmpty())
    assertTrue(NmRouting.moved(three, doubleArrayOf(400.0, 400.0, 400.0), route).isEmpty())
  }
}
