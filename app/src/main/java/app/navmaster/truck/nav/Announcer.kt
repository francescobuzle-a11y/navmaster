package app.navmaster.truck.nav

import android.util.Log
import app.navmaster.truck.settings.VoiceLevel
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteStep

/**
 * The voice of the manoeuvres, one manoeuvre at a time, as the dedicated navigators do it:
 *
 * - nothing in the first seconds after a manoeuvre (the next one is not announced while the
 *   driver is still turning);
 * - on fast roads a far notice ("Tra 2 chilometri, esci a destra"), only with the NORMAL voice;
 * - the preparation, at a distance that depends on the speed (about 15 s before: 200 m in town,
 *   350 m at 90 km/h, 550 m on the motorway);
 * - the manoeuvre itself, 4-5 s before it; only then, and only when the next manoeuvre follows
 *   within a few seconds, "... poi svolta a sinistra".
 *
 * The words are the route's own (Valhalla, in Italian), without the chained "Poi ..." parts and
 * without the distance, which is said here, at the right moment.
 */
class Announcer(private val speak: (String) -> Unit) {
  private val done = HashSet<String>()
  private var routeKey = ""

  private fun clean(text: String): String? {
    val sentences = text.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    val keep = sentences.filterNot { THEN.containsMatchIn(it) }
    val first = keep.joinToString(" ").replace(DISTANCE, "").trim().trimEnd('.', ' ')
    if (first.isBlank()) return null
    return first.replaceFirstChar { it.uppercase() }
  }

  /** What to say for the manoeuvre at the end of [step]: its closest spoken instruction. */
  fun textOf(step: RouteStep): String? =
      step.spokenInstructions.minByOrNull { it.triggerDistanceBeforeManeuver }?.text?.let { clean(it) }

  private fun distanceWords(m: Double): String = when {
    m >= 950 -> {
      val km = Math.round(m / 500.0) * 0.5
      if (km == 1.0) "1 chilometro" else (if (km % 1.0 == 0.0) "${km.toInt()}" else "%.1f".format(java.util.Locale.ITALIAN, km)) + " chilometri"
    }
    m >= 100 -> "${(Math.round(m / 50.0) * 50).toInt()} metri"
    else -> "${(Math.round(m / 10.0) * 10).toInt().coerceAtLeast(10)} metri"
  }

  /**
   * Called at every position. [along] metres driven on [route] (from its start), [toManeuver] to
   * the end of the current step, [speed] m/s.
   */
  fun update(route: Route, key: String, along: Double, toManeuver: Double, speed: Double, fast: Boolean, level: VoiceLevel) {
    if (key != routeKey) {
      routeKey = key
      done.clear()
    }
    val steps = route.steps
    if (steps.isEmpty()) return
    // the current step: the one whose end is where the next manoeuvre is
    val maneuverAt = along + toManeuver
    var acc = 0.0
    var index = 0
    var best = Double.MAX_VALUE
    var stepStart = 0.0
    for ((i, st) in steps.withIndex()) {
      val end = acc + st.distance
      val d = kotlin.math.abs(end - maneuverAt)
      if (d < best) {
        best = d
        index = i
        stepStart = acc
      }
      acc = end
    }
    val step = steps[index]
    val main = textOf(step) ?: return
    val v = speed.coerceAtLeast(8.0)
    val dNow = (v * 4.5).coerceIn(30.0, 160.0)
    val dPrep = (v * 15).coerceIn(180.0, 1000.0)
    val sinceManeuver = along - stepStart
    // just out of a manoeuvre: nothing yet, unless the next one is already here
    val settled = sinceManeuver > maxOf(120.0, v * 5) || index == 0
    val id = "$key:$index"
    when {
      toManeuver <= dNow -> if (done.add("$id:now")) {
        done += "$id:prep"
        done += "$id:far"
        val next = steps.getOrNull(index + 1)
        val soon = next != null && next.distance > 0 && next.distance < maxOf(120.0, v * 7) && index + 2 < steps.size
        val then = if (soon) next?.let { textOf(it) } else null
        if (then != null) done += "$key:${index + 1}:prep"
        say(main + (then?.let { ", poi " + it.replaceFirstChar { c -> c.lowercase() } } ?: "") + ".")
      }
      toManeuver <= dPrep && toManeuver > dNow + v * 3 && settled -> if (done.add("$id:prep")) {
        done += "$id:far"
        say("Tra ${distanceWords(toManeuver)}, ${main.replaceFirstChar { it.lowercase() }}.")
      }
      level != VoiceLevel.ESSENTIAL && fast && toManeuver in 1200.0..2400.0 && toManeuver > dPrep + v * 10 && settled ->
        if (done.add("$id:far")) say("Tra ${distanceWords(toManeuver)}, ${main.replaceFirstChar { it.lowercase() }}.")
    }
  }

  private fun say(text: String) {
    Log.i("NavMasterVoice", "say: $text")
    speak(text)
  }

  companion object {
    /** The chained second manoeuvre of Valhalla ("Poi svolta a sinistra"), said here only when due. */
    private val THEN = Regex("^(poi|quindi|subito dopo|then|and then)\\b", RegexOption.IGNORE_CASE)
    /** "Tra 300 metri, " / "Fra 2 chilometri " / "In 500 m, " at the start of an instruction. */
    private val DISTANCE = Regex("^(tra|fra|in|entro)\\s+[\\d.,]+\\s*(metri|metro|chilometri|chilometro|km|m|miglia|piedi|meters|kilometers)\\b[,]?\\s*",
        RegexOption.IGNORE_CASE)
  }
}
