package app.navmaster.truck.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The variants from the server (openrouteservice's alternatives) within the factors of the request. */
class ServerVariantsTest {
  private fun r(km: Double, min: Double, feature: Int) =
      RouteResult(doubleArrayOf(45.0, 45.1), doubleArrayOf(9.0, 9.1), intArrayOf(0, 1), km * 1000, (min * 60_000).toLong(), null, feature)

  @Test
  fun automaticNeverBeyond120() {
    val all = listOf(r(60.0, 50.0, 0), r(63.0, 54.0, 1), r(62.0, 67.5, 2), r(80.0, 55.0, 3))
    // 1.08× in time and 1.05× in km kept; 1.35× in time and 1.33× in km left out
    assertEquals(listOf(1), NmRouting.serverVariants(all, VariantAsk.AUTO).map { it.feature })
  }

  @Test
  fun moreUpTo150AndShorterOnes() {
    val all = listOf(r(60.0, 50.0, 0), r(62.0, 67.5, 1), r(52.0, 70.0, 2), r(100.0, 60.0, 3), r(60.0, 80.0, 4))
    // 1.35× in time kept; shorter and 1.4× slower kept; 1.67× in km out; 1.6× in time out
    assertEquals(listOf(1, 2), NmRouting.serverVariants(all, VariantAsk.MORE).map { it.feature })
  }

  @Test
  fun bestLeftOutAndNoneWithoutIt() {
    assertTrue(NmRouting.serverVariants(listOf(r(60.0, 50.0, 0)), VariantAsk.AUTO).isEmpty())
    assertTrue(NmRouting.serverVariants(emptyList(), VariantAsk.AUTO).isEmpty())
  }

  @Test
  fun serverAskedOnlyWhenItCanGiveVariants() {
    assertTrue(NmRouting.serverHasVariants(2, far = false))
    assertFalse(NmRouting.serverHasVariants(2, far = true))
    assertFalse(NmRouting.serverHasVariants(3, far = false))
  }
}
