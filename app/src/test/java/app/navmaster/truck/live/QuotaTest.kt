package app.navmaster.truck.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every driver's own TomTom and HERE accounts: the whole free allowance less a tenth, HERE's daily cap. */
class QuotaTest {
  @Test
  fun wholeAllowanceLessATenth() {
    assertEquals(180_000, Quota.usable(200_000))
    assertEquals(900, Quota.usable(1_000))
    assertEquals(27_000, Quota.usable(30_000))
    assertEquals(1, Quota.usable(0))
  }

  @Test
  fun hereDailyCapForAllServices() {
    val cap = Quota.usable(1_000)
    // the searches stop 100 before the cap, the traffic goes on to the cap
    assertTrue(Quota.hereDayFits(dayCalls = 0, daySearch = 799, n = 1, search = true, dayCap = cap))
    assertFalse(Quota.hereDayFits(dayCalls = 0, daySearch = 800, n = 1, search = true, dayCap = cap))
    assertTrue(Quota.hereDayFits(dayCalls = 99, daySearch = 800, n = 1, search = false, dayCap = cap))
    assertFalse(Quota.hereDayFits(dayCalls = 100, daySearch = 800, n = 1, search = false, dayCap = cap))
    // traffic and searches count together
    assertFalse(Quota.hereDayFits(dayCalls = 400, daySearch = 400, n = 1, search = true, dayCap = cap))
    // a small cap keeps at most a quarter for the traffic
    assertTrue(Quota.hereDayFits(dayCalls = 0, daySearch = 29, n = 1, search = true, dayCap = 40))
    assertFalse(Quota.hereDayFits(dayCalls = 0, daySearch = 30, n = 1, search = true, dayCap = 40))
  }
}
