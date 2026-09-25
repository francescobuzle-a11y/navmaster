package app.navmaster.truck.nav

import android.util.Log
import app.navmaster.truck.settings.VoiceLevel
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.flow.StateFlow
import uniffi.ferrostar.SpokenInstruction

/**
 * Between the route and the voice: says what matters once, and leaves the rest to the screen.
 *  - ESSENTIAL: no "keep going for 5 km", no far warnings (more than 1.2 km before), no repeats;
 *  - NORMAL: the far warning too, still no "keep going" and no repeats;
 *  - FULL: everything the route has.
 */
class VoiceFilter(private val inner: SpokenInstructionObserver, private val level: () -> VoiceLevel) : SpokenInstructionObserver {
  private val recent = ArrayDeque<Pair<String, Long>>()

  override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
    val text = spokenInstruction.text.trim()
    val lv = level()
    val now = System.currentTimeMillis()
    val drop = when {
      lv == VoiceLevel.FULL -> false
      KEEP_GOING.containsMatchIn(text) -> true
      recent.any { it.first.equals(text, true) && now - it.second < 60_000 } -> true
      lv == VoiceLevel.ESSENTIAL && spokenInstruction.triggerDistanceBeforeManeuver > 1_200 -> true
      else -> false
    }
    if (drop) {
      Log.d("NavMasterVoice", "silent ($lv): $text")
      return
    }
    recent.addLast(text to now)
    while (recent.size > 20) recent.removeFirst()
    inner.onSpokenInstructionTrigger(spokenInstruction)
  }

  override fun stopAndClearQueue() = inner.stopAndClearQueue()

  override fun setMuted(isMuted: Boolean) = inner.setMuted(isMuted)

  override val muteState: StateFlow<Boolean>
    get() = inner.muteState

  companion object {
    /** "Prosegui per 5 chilometri", "Continua dritto per 800 metri", "Continue for 2 kilometers". */
    private val KEEP_GOING = Regex("^(prosegui|continua|continue|mantieni)\\b[^.]*\\bper\\b\\s*\\d|^continue\\b[^.]*\\bfor\\b\\s*\\d", RegexOption.IGNORE_CASE)
  }
}
