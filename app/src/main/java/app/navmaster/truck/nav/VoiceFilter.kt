package app.navmaster.truck.nav

import android.util.Log
import app.navmaster.truck.settings.VoiceLevel
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import kotlinx.coroutines.flow.StateFlow
import uniffi.ferrostar.SpokenInstruction

/**
 * Between the route and the voice: the route's own cues are not spoken (see Announcer, which says
 * each manoeuvre itself); only the mute state goes through.
 */
class VoiceFilter(private val inner: SpokenInstructionObserver, private val level: () -> VoiceLevel) : SpokenInstructionObserver {
  private val recent = ArrayDeque<Pair<String, Long>>()

  override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
    // the manoeuvres are said by the Announcer, one at a time and at the right moment (the
    // route's own voice cues come too early on long steps and chain the next manoeuvre)
    Log.d("NavMasterVoice", "route cue left to the Announcer: ${spokenInstruction.text}")
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
