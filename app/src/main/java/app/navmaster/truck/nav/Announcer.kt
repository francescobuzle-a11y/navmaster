package app.navmaster.truck.nav

import app.navmaster.truck.settings.VoiceLevel
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteStep

/**
 * The voice of the manoeuvres, one manoeuvre at a time, timed as the dedicated navigators do it:
 *
 * - on fast roads a far notice about 2 km before ("Tra 2 chilometri, esci a destra"), only with
 *   the NORMAL voice;
 * - the preparation, about 30 s before on fast roads (700-1100 m) and 13 s before in town
 *   (120-350 m), with the lane to keep where a lane can be missed;
 * - the manoeuvre itself, timed so that the sentence ENDS a few seconds before the point (9 s
 *   before a motorway exit, where the deceleration lane begins; 2.5 s before a turn in town),
 *   short ("Esci a destra"), and only then, when the next manoeuvre follows within a few seconds,
 *   "... poi svolta a sinistra".
 *
 * Every moment is worked out from the real speed of the vehicle (measured from the positions as
 * well, so a fast simulation is announced in time too), from how often positions arrive and from
 * how long the voice takes to say each sentence: a sentence is started early enough to be over
 * before the next one is due, so they never overlap.
 *
 * The words are the route's own (Valhalla, in Italian), without the chained "Poi ..." parts and
 * without the distance, which is said here, at the right moment.
 */
class Announcer(
    /** Says [text]: urgent for the manoeuvre due now; [tag] the manoeuvre; [valid] still worth saying. */
    private val speak: (text: String, urgent: Boolean, tag: String, valid: () -> Boolean) -> Unit,
    /** How long the voice takes to say a sentence, in seconds. */
    private val durationS: (String) -> Double,
) {
  private val done = HashSet<String>()
  private var routeKey = ""
  private var lastCallMs = 0L
  private var tickS = 1.0

  /** The current position on the route, for the sentences waiting to be said (still valid?). */
  @Volatile private var curAlong = 0.0
  @Volatile private var curKey = ""

  /** Distance to the manoeuvre and speed at the last position (for the log of the voice). */
  @Volatile var toManeuverNow = 0.0
    private set
  @Volatile var speedNow = 0.0
    private set

  // the moment (clock of update) when the next sentence about a manoeuvre is due: a moment, not a
  // count of seconds, so that it stays right even if the positions stop coming for a while
  @Volatile private var dueAtMs: Long = Long.MAX_VALUE

  /** Seconds until the next sentence about a manoeuvre is due (for the warnings, which wait). */
  fun dueInS(nowMs: Long): Double = if (dueAtMs == Long.MAX_VALUE) Double.MAX_VALUE else ((dueAtMs - nowMs) / 1000.0).coerceAtLeast(0.0)

  private fun clean(text: String): String? {
    val sentences = text.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    val keep = sentences.filterNot { THEN.containsMatchIn(it) }
    val first = keep.joinToString(" ").replace(DISTANCE, "").trim().trimEnd('.', ' ')
    if (first.isBlank()) return null
    return first.replaceFirstChar { it.uppercase() }
  }

  /** What to say for the manoeuvre at the end of [step]: its closest spoken instruction. */
  fun textOf(step: RouteStep): String? {
    val raw = step.spokenInstructions.minByOrNull { it.triggerDistanceBeforeManeuver }?.text?.let { clean(it) } ?: return null
    val p = step.visualInstructions.firstOrNull()?.primaryContent
    // in good Italian, as a person says it (see SpeechIt); null = not said at all
    return SpeechIt.maneuver(raw, p?.maneuverType?.name, p?.maneuverModifier?.name)
  }

  private fun rawTextOf(step: RouteStep): String? =
      step.spokenInstructions.minByOrNull { it.triggerDistanceBeforeManeuver }?.text?.let { clean(it) }

  private fun typeOf(step: RouteStep): String = step.visualInstructions.firstOrNull()?.primaryContent?.maneuverType?.name?.uppercase() ?: ""

  /** Leaving a roundabout: already said with its entry ("prendi la 2a uscita"), never alone. */
  private fun isRoundaboutExit(step: RouteStep): Boolean {
    val type = typeOf(step)
    if ("EXIT_ROUNDABOUT" in type || "EXIT_ROTARY" in type) return true
    val t = rawTextOf(step)?.lowercase() ?: return false
    return (t.startsWith("esci") || t.startsWith("exit")) && ("rotatoria" in t || "rotonda" in t || "roundabout" in t)
  }

  /**
   * Called at every position. [along] metres driven on [route] (from its start), [toManeuver] to
   * the end of the current step, [speed] m/s (the larger of the GPS one and the measured one),
   * [fast] on a motorway or a fast road, [lanes] what to say about the lanes of a step (or null),
   * [nowMs] a clock in milliseconds.
   */
  fun update(
      route: Route, key: String, along: Double, toManeuver: Double, speed: Double, fast: Boolean, level: VoiceLevel,
      nowMs: Long, lanes: (Int) -> String? = { null },
  ) {
    if (key != routeKey) {
      routeKey = key
      done.clear()
      lastCallMs = 0L
    }
    curKey = key
    curAlong = along
    toManeuverNow = toManeuver
    speedNow = speed
    // how often the positions arrive (once a second; faster in a simulation)
    if (lastCallMs > 0) {
      val dt = ((nowMs - lastCallMs) / 1000.0).coerceIn(0.05, 3.0)
      tickS = tickS * 0.7 + dt * 0.3
    }
    lastCallMs = nowMs
    dueAtMs = Long.MAX_VALUE
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
    if (isRoundaboutExit(step)) return
    val main = textOf(step) ?: return
    val v = speed.coerceAtLeast(if (fast) 12.0 else 5.0)
    val type = typeOf(step).replace("_", "")
    val exitLike = "OFFRAMP" in type || "FORK" in type || "ONRAMP" in type
    val id = "$key:$index"
    val laneWords = runCatching { lanes(index) }.getOrNull()
    // the delay before the voice is heard, and half the time between two positions (the
    // manoeuvre is said at the position before the right moment rather than after it)
    val latency = 0.4 + tickS / 2

    // ---- the manoeuvre itself: short when it was already prepared
    val prepared = "$id:prep" in done || "$id:far" in done
    val next = steps.getOrNull(index + 1)
    val soon = next != null && next.distance > 0 && next.distance < maxOf(120.0, v * 7) && index + 2 < steps.size && !isRoundaboutExit(next)
    val then = if (soon) next?.let { textOf(it) } else null
    val nowCore = if (prepared) SpeechIt.short(main, dropRoad = true) else main
    val nowText = nowCore + (then?.let { ", poi " + SpeechIt.short(it).replaceFirstChar { c -> c.lowercase() } } ?: "") + "."
    val lead = when {
      fast && exitLike -> 9.0
      fast -> 5.0
      else -> 2.5
    }
    val dNow = (v * (durationS(nowText) + lead + latency)).coerceIn(25.0, if (fast) 450.0 else 160.0)

    // ---- the preparation
    val dPrep = if (fast) (v * 32).coerceIn(700.0, 1100.0) else (v * 13).coerceIn(120.0, 350.0)
    fun prepText(dist: Double): String =
        "Tra ${SpeechIt.distance(dist)}, ${main.replaceFirstChar { it.lowercase() }}" + (laneWords?.let { ", $it" } ?: "") + "."
    val sinceManeuver = along - stepStart
    // just out of a manoeuvre: nothing yet, unless the next one is already here
    val settled = sinceManeuver > maxOf(120.0, v * 5) || index == 0
    val far = 2000.0

    when {
      toManeuver <= dNow -> if (done.add("$id:now")) {
        done += "$id:prep"
        done += "$id:far"
        // the next manoeuvre was just said with this one: not said again a few seconds later
        if (then != null) {
          done += "$key:${index + 1}:prep"
          done += "$key:${index + 1}:far"
          done += "$key:${index + 1}:now"
        }
        val at = maneuverAt
        speak(nowText, true, id) { curKey == key && curAlong < at - 5 }
      }
      toManeuver <= dPrep + v * tickS * 0.5 && settled && "$id:prep" !in done -> {
        // said at the distance the vehicle will be at halfway through the sentence
        val text0 = prepText(toManeuver)
        val dSaid = toManeuver - v * (latency + durationS(text0) / 2)
        val text = prepText(dSaid)
        // only if it is over a few seconds before the manoeuvre has to be said
        if (toManeuver - dNow >= v * (durationS(text) + 2.0) && dSaid >= (if (fast) 300.0 else 60.0)) {
          done += "$id:prep"
          done += "$id:far"
          val at = maneuverAt
          speak(text, false, id) { curKey == key && at - curAlong > dNow + v * 2 }
        } else {
          done += "$id:prep"
          done += "$id:far"
        }
      }
      level != VoiceLevel.ESSENTIAL && fast && settled && toManeuver <= far + v * tickS * 0.5 && toManeuver > dPrep + v * 12 &&
          "$id:far" !in done -> {
        done += "$id:far"
        val dSaid = toManeuver - v * (latency + 1.5)
        val at = maneuverAt
        speak(prepText(dSaid), false, id) { curKey == key && at - curAlong > dPrep + v * 3 }
      }
    }

    // at the manoeuvre (already said) with the next one very close behind it: the next one is said
    // now ("Poi svolta a destra"), not when the guidance moves on to it, metres before it
    if ("$id:now" in done && toManeuver < 40) {
      val nxt = steps.getOrNull(index + 1)
      val nid = "$key:${index + 1}"
      if (nxt != null && "$nid:now" !in done && index + 2 < steps.size && !isRoundaboutExit(nxt)) {
        val nText = textOf(nxt)
        if (nText != null && toManeuver + nxt.distance <= dNow) {
          done += "$nid:now"
          done += "$nid:prep"
          done += "$nid:far"
          val at = maneuverAt + nxt.distance
          speak("Poi " + SpeechIt.short(nText, dropRoad = false).replaceFirstChar { it.lowercase() } + ".", true, nid) { curKey == key && curAlong < at - 5 }
        }
      }
    }

    // when the next sentence about this manoeuvre is due (warnings wait if they would overlap it)
    val dueAt = when {
      "$id:now" in done -> null
      "$id:prep" !in done && settled -> dPrep
      else -> dNow
    }
    dueAtMs = if (dueAt == null) Long.MAX_VALUE else nowMs + (((toManeuver - dueAt) / v).coerceAtLeast(0.0) * 1000).toLong()
  }

  companion object {
    /** The chained second manoeuvre of Valhalla ("Poi svolta a sinistra"), said here only when due. */
    private val THEN = Regex("^(poi|quindi|subito dopo|then|and then)\\b", RegexOption.IGNORE_CASE)
    /** "Tra 300 metri, " / "Fra 2 chilometri " / "In 500 m, " at the start of an instruction. */
    private val DISTANCE = Regex("^(tra|fra|in|entro)\\s+[\\d.,]+\\s*(metri|metro|chilometri|chilometro|km|m|miglia|piedi|meters|kilometers)\\b[,]?\\s*",
        RegexOption.IGNORE_CASE)
  }
}
