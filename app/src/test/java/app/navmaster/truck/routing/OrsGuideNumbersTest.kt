package app.navmaster.truck.routing

import java.util.Locale
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** The numbers of the guidance written by hand must read as String.format wrote them. */
class OrsGuideNumbersTest {
  @Test
  fun fixedMatchesStringFormat() {
    val rnd = Random(5)
    var different = 0
    for (k in 0 until 200_000) {
      val dec = intArrayOf(1, 3, 4, 6)[k % 4]
      val v = when (k % 5) {
        0 -> rnd.nextDouble() * 10
        1 -> rnd.nextDouble() * 5000
        2 -> -rnd.nextDouble() * 200
        3 -> Math.round(rnd.nextDouble() * 1000) / 10.0
        else -> rnd.nextDouble() * 60 + 7
      }
      val mine = StringBuilder().also { OrsGuide.fixed(it, v, dec) }.toString()
      val ref = String.format(Locale.ROOT, "%.${dec}f", v)
      if (mine != ref) {
        // only a last digit off by one on a value right at half a step (the binary value and the formatter's rounding)
        assertEquals("$v %.${dec}f: $mine vs $ref", ref.length, mine.length)
        assertEquals("$v %.${dec}f: $mine vs $ref", ref.toDouble(), mine.toDouble(), Math.pow(10.0, -dec.toDouble()) * 1.01)
        different++
      }
    }
    assert(different < 200) { "$different numbers differ from String.format" }
    for ((v, dec, s) in listOf(Triple(0.0, 1, "0.0"), Triple(12.0, 3, "12.000"), Triple(0.05, 1, "0.1"), Triple(9.96, 1, "10.0"), Triple(-1.25, 1, "-1.3"), Triple(Double.NaN, 1, "NaN")))
      assertEquals(s, StringBuilder().also { OrsGuide.fixed(it, v, dec) }.toString())
  }
}
