package app.navmaster.truck.ui

import app.navmaster.truck.limits.LimitsIndex
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** The time windows of the restrictions (OSM conditional): shown only when they surely apply. */
class RulesTest {
  private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min)

  @Test
  fun timeWindows() {
    // 2026-11-02 is a Monday
    val mon10 = at(2026, 11, 2, 10); val sat10 = at(2026, 11, 7, 10); val sat03 = at(2026, 11, 7, 3)
    val mon03 = at(2026, 11, 2, 3); val fri23 = at(2026, 11, 6, 23); val sun10 = at(2026, 11, 8, 10)
    assertEquals(true, LimitsIndex.conditionalWindow("Mo-Fr 07:00-19:00", mon10))
    assertEquals(false, LimitsIndex.conditionalWindow("Mo-Fr 07:00-19:00", sat10))
    // past midnight: Friday night goes on into Saturday morning, Sunday night does not reach Monday
    assertEquals(true, LimitsIndex.conditionalWindow("Mo-Fr 22:00-06:00", sat03))
    assertEquals(false, LimitsIndex.conditionalWindow("Mo-Fr 22:00-06:00", mon03))
    assertEquals(true, LimitsIndex.conditionalWindow("Mo-Fr 22:00-06:00", fri23))
    assertEquals(true, LimitsIndex.conditionalWindow("22:00-06:00", mon03))
    // public holidays: an ordinary Sunday still counts; 1 January too; a Tuesday not
    assertEquals(true, LimitsIndex.conditionalWindow("Su,PH 07:00-22:00", sun10))
    assertEquals(true, LimitsIndex.conditionalWindow("Su,PH 07:00-22:00", at(2030, 1, 1, 10)))
    assertEquals(false, LimitsIndex.conditionalWindow("Su,PH 07:00-22:00", at(2026, 11, 3, 10)))
    // what cannot be told for sure is not shown
    assertEquals(null, LimitsIndex.conditionalWindow("sunrise-sunset", mon10))
    assertEquals(null, LimitsIndex.conditionalWindow("Nov-Mar", mon10))
  }
}
