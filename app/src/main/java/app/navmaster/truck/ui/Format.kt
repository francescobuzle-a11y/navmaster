package app.navmaster.truck.ui

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Italian formatting for the cabin: "850 m", "1,2 km", "14 km", "1 h 20 min", "14:35". */
object Fmt {
  data class Dist(val value: String, val unit: String)

  fun distance(m: Double): Dist =
      when {
        m < 100 -> Dist(((m / 10).roundToInt() * 10).toString(), "m")
        m < 950 -> Dist(((m / 50).roundToInt() * 50).toString(), "m")
        m < 10_000 -> Dist(String.format(Locale.ITALY, "%.1f", m / 1000.0), "km")
        else -> Dist((m / 1000.0).roundToInt().toString(), "km")
      }

  fun distanceText(m: Double): String = distance(m).let { "${it.value} ${it.unit}" }

  fun duration(s: Double): String {
    val min = (s / 60.0).roundToInt()
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
  }

  fun eta(secondsFromNow: Double): String =
      LocalTime.now().plusSeconds(secondsFromNow.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))

  fun metres(v: Double): String = String.format(Locale.ITALY, "%.2f m", v).replace(",00", "")

  fun tonnes(v: Double): String = String.format(Locale.ITALY, "%.1f t", v).replace(",0 ", " ")
}
