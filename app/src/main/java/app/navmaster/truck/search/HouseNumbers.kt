package app.navmaster.truck.search

import kotlin.math.abs

/**
 * A house number that is not in the map, placed as the navigators do: between the known numbers
 * of the same street on the same side (odd with odd, even with even), in proportion. "Via Roma 25"
 * with 21 and 31 known lands 40% of the way from 21 to 31. Pure Kotlin, tested on the computer.
 */
object HouseNumbers {
  data class Estimate(val lat: Double, val lon: Double, val exact: Boolean, val note: String?)

  data class Known(val num: String, val lat: Double, val lon: Double) {
    val n: Int? = num.takeWhile { it.isDigit() }.toIntOrNull()
  }

  fun find(number: String, known: List<Known>): Estimate? {
    if (known.isEmpty()) return null
    known.firstOrNull { it.num.equals(number, true) }?.let { return Estimate(it.lat, it.lon, true, null) }
    val target = number.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    // "12/a", "12 bis": the building of 12
    known.firstOrNull { it.n == target }?.let { return Estimate(it.lat, it.lon, true, null) }
    val nums = known.filter { it.n != null }
    if (nums.isEmpty()) return null
    // same side of the street when there are numbers there; otherwise any
    val side = nums.filter { it.n!! % 2 == target % 2 }.takeIf { it.size >= 2 } ?: nums
    val below = side.filter { it.n!! < target }.maxByOrNull { it.n!! }
    val above = side.filter { it.n!! > target }.minByOrNull { it.n!! }
    if (below != null && above != null) {
      val f = (target - below.n!!).toDouble() / (above.n!! - below.n!!)
      return Estimate(below.lat + (above.lat - below.lat) * f, below.lon + (above.lon - below.lon) * f, false,
          "tra il ${below.num} e il ${above.num}")
    }
    // beyond the last known number: the nearest one (never extrapolated far along a guessed line)
    val nearest = side.minByOrNull { abs(it.n!! - target) } ?: return null
    return Estimate(nearest.lat, nearest.lon, false, "vicino al ${nearest.num}")
  }
}
