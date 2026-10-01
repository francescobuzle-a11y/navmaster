package app.navmaster.truck.nav

import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything the voice says goes through here, one sentence at a time, so that nothing is cut
 * and nothing is said late:
 *
 * - a sentence is never cut by another one, with a single exception: the manoeuvre due now may
 *   interrupt a warning (a late manoeuvre is worse than a warning said in part);
 * - a manoeuvre never interrupts another manoeuvre: the Announcer times them so that each one ends
 *   seconds before the next is due;
 * - a warning or a message starts only if it ends before the next manoeuvre is due (it waits for
 *   after it otherwise), and a sentence that could not be said in time is dropped: a distance said
 *   ten seconds late is a wrong distance;
 * - how long the voice takes to say a sentence is learnt from the voice itself (each phone's voice
 *   speaks at its own pace), so the Announcer can start each manoeuvre at the right moment.
 */
class VoiceQueue(
    private val scope: CoroutineScope,
    private val tts: () -> TextToSpeech?,
    private val muted: () -> Boolean,
) {
  enum class Pri { INFO, WARN, PREP, NOW }

  private class Item(val text: String, val pri: Pri, val until: Long, val tag: String?, val valid: () -> Boolean, val at: Long)

  private val pending = ArrayList<Item>()
  private var speaking: Item? = null
  private var startedAt = 0L
  private var seenBusy = false
  private var job: Job? = null

  /** Characters a second of this voice (learnt). */
  @Volatile var charsPerS = 14.5
    private set

  /** When the next manoeuvre is due to be said, in seconds (from the Announcer). */
  var maneuverDueInS: () -> Double = { Double.MAX_VALUE }

  /** How long the voice takes to say [text], in seconds (start delay included). */
  fun durationS(text: String): Double = 0.45 + text.length / charsPerS

  /** True while something is being said or waits to be said. */
  val busy: Boolean
    get() = speaking != null || pending.isNotEmpty()

  fun say(text: String, pri: Pri, maxAgeMs: Long, tag: String? = null, valid: () -> Boolean = { true }) {
    if (text.isBlank()) return
    // all the work on the main thread (some warnings come from background work)
    if (Looper.myLooper() != Looper.getMainLooper()) {
      scope.launch(Dispatchers.Main) { say(text, pri, maxAgeMs, tag, valid) }
      return
    }
    val now = SystemClock.elapsedRealtime()
    // a newer sentence for the same manoeuvre replaces the one still waiting
    if (tag != null) pending.removeAll { it.tag == tag }
    pending += Item(text, pri, now + maxAgeMs, tag, valid, now)
    pump()
    ensureLoop()
  }

  /** Everything waiting is forgotten and the voice stops (guidance ended, muted). */
  fun clear() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      scope.launch(Dispatchers.Main) { clear() }
      return
    }
    pending.clear()
    if (speaking != null) runCatching { tts()?.stop() }
    speaking = null
  }

  private fun ensureLoop() {
    if (job?.isActive == true) return
    job = scope.launch {
      while (true) {
        delay(120)
        pump()
        if (speaking == null && pending.isEmpty()) break
      }
    }
  }

  private fun pump() {
    val t = tts() ?: return
    val now = SystemClock.elapsedRealtime()
    val engineBusy = runCatching { t.isSpeaking }.getOrDefault(false)
    val cur = speaking
    if (cur != null && engineBusy) seenBusy = true
    // the sentence being said has ended (the engine needs a moment to start: until it has been
    // heard speaking, it is not over for 3 seconds)
    if (cur != null && !engineBusy && (seenBusy || now - startedAt > 3000)) {
      val took = (now - startedAt) / 1000.0
      // learnt only from whole sentences of some length
      if (seenBusy && cur.text.length >= 15 && took > 0.8 && took < 20) {
        val cps = cur.text.length / (took - 0.35).coerceAtLeast(0.3)
        if (cps in 7.0..30.0) charsPerS = charsPerS * 0.7 + cps * 0.3
      }
      speaking = null
    }
    if (muted()) {
      pending.clear()
      return
    }
    pending.removeAll { now > it.until || !runCatching { it.valid() }.getOrDefault(false) }
    if (pending.isEmpty()) return
    val next = pending.maxWithOrNull(compareBy<Item>({ it.pri.ordinal }, { -it.at })) ?: return
    val sayingNow = speaking
    if (sayingNow != null || engineBusy) {
      // only the manoeuvre due now goes before a warning being said; nothing cuts a manoeuvre
      if (next.pri == Pri.NOW && sayingNow != null && sayingNow.pri <= Pri.WARN) {
        Log.i(TAG, "manoeuvre first: warning cut (${sayingNow.text.take(40)}…)")
        runCatching { t.stop() }
        speaking = null
      } else return
    }
    // a warning only if it is over before the next manoeuvre is due
    if (next.pri <= Pri.WARN) {
      val due = runCatching { maneuverDueInS() }.getOrDefault(Double.MAX_VALUE)
      if (due < durationS(next.text) + 1.0) return
    }
    pending.remove(next)
    speaking = next
    startedAt = now
    seenBusy = false
    Log.i(TAG, "say (${next.pri}): ${next.text}")
    runCatching { t.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, "nm-${now}") }
  }

  companion object {
    private const val TAG = "NavMasterVoice"
  }
}
