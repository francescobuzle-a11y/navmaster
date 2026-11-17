package app.navmaster.truck.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ferrostar.GeographicCoordinate

/** The same route offered twice is left out, a variant as good as the best one is not (rule B). */
class RouteSamenessTest {
  private fun c(lat: Double, lng: Double) = GeographicCoordinate(lat, lng)

  /** A line north from 45.0, 9.0, [km] long, a point every 500 m; [bump] moves it east between two latitudes. */
  private fun line(km: Double, bump: Triple<Double, Double, Double>? = null, eastM: Double = 0.0): List<GeographicCoordinate> {
    val n = (km * 2).toInt()
    return (0..n).map { i ->
      val lat = 45.0 + i * 0.5 / 111.2
      val off = eastM + (bump?.takeIf { lat > it.first && lat < it.second }?.third ?: 0.0)
      c(lat, 9.0 + off / (111_200.0 * kotlin.math.cos(Math.toRadians(45.0))))
    }
  }

  private fun track(g: List<GeographicCoordinate>, km: Double, min: Double, sig: String? = null) = Track(km * 1000, min * 60, sig, g)

  @Test
  fun sameRouteTwice() {
    val g = line(100.0)
    assertTrue(RouteSameness.duplicate(track(g, 100.0, 60.0), track(g, 100.0, 60.0)))
    // the same road snapped 10 m apart, a few metres more: still the same
    assertTrue(RouteSameness.duplicate(track(g, 100.0, 60.0), track(line(100.0, eastM = 10.0), 100.2, 60.3)))
    // the engine's variant against the best one (no signature on the best one): the line decides
    assertTrue(RouteSameness.duplicate(track(g, 100.0, 60.0), track(g, 100.0, 60.0, "A1")))
  }

  @Test
  fun variantAsGoodAsTheBestIsNotADuplicate() {
    // 1000 km in 600 min, a variant +2 km / +3 min on another road for 60 km: the totals are "the
    // same" (0.4 % and 1 %), 94 % of the line too, but it is another way
    val best = line(1000.0)
    val variant = line(1000.0, Triple(45.0 + 400 / 111.2, 45.0 + 460 / 111.2, 3_000.0))
    val a = track(best, 1000.0, 600.0)
    val b = track(variant, 1002.0, 603.0)
    assertTrue(RouteSameness.sameTotals(a, b))
    assertFalse(RouteSameness.duplicate(a, b))
    // the 60 km apart and the two slanting pieces that lead there and back (3 km to the side each)
    val off = RouteSameness.offTrackM(variant, best)
    assertTrue("off $off", off in 59_000.0..67_000.0)
    // two variants of the engine on other roads with the same totals
    val other = line(1000.0, Triple(45.0 + 700 / 111.2, 45.0 + 760 / 111.2, -3_000.0))
    assertFalse(RouteSameness.duplicate(b, track(other, 1002.0, 603.0)))
    // the engine says different roads: not the same, whatever the totals
    assertFalse(RouteSameness.duplicate(track(best, 1000.0, 600.0, "A1 · A22"), track(best, 1000.0, 600.0, "A1 · A21")))
  }

  @Test
  fun differentTotalsAreNotTheSame() {
    val g = line(100.0)
    assertFalse(RouteSameness.duplicate(track(g, 100.0, 60.0), track(g, 101.0, 60.0)))
    assertFalse(RouteSameness.duplicate(track(g, 100.0, 60.0), track(g, 100.0, 62.0)))
  }
}
