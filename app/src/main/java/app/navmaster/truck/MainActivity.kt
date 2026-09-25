package app.navmaster.truck

import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.navmaster.truck.nav.NavViewModel
import app.navmaster.truck.ui.MainScreen
import app.navmaster.truck.ui.NmTheme
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import java.util.Locale
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.createFerrostarLogger

class MainActivity : ComponentActivity(), AndroidTtsStatusListener {
  private val vm: NavViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppGraph.tts.statusObserver = this
    // the voice says each thing once; how much it says is in the settings
    AppGraph.ferrostar.spokenInstructionObserver =
        app.navmaster.truck.nav.VoiceFilter(AppGraph.tts) { AppGraph.settings.settings.value.voiceLevel }
    createFerrostarLogger()
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    val crit = intent?.getIntExtra("nm_crit", -1)?.takeIf { it >= 0 }
    app.navmaster.truck.ui.UiHints.tab = intent?.getIntExtra("nm_tab", 0) ?: 0
    setContent { NmTheme { MainScreen(vm, intent?.getStringExtra("nm_sheet"), crit) } }
    handleTestIntent()
  }

  override fun onStart() {
    super.onStart()
    AppGraph.tts.start()
  }

  override fun onDestroy() {
    super.onDestroy()
    AppGraph.tts.shutdown()
  }

  /**
   * Emulator tests: `am start ... --es nm_dest 44.06,12.56 --ez nm_sim true` routes to a point and
   * starts (simulated) guidance, so the CI can take screenshots of a real offline route.
   */
  private fun handleTestIntent() {
    intent?.getStringExtra("nm_from")?.split(',')?.takeIf { it.size == 2 }?.let {
      vm.setTestLocation(it[0].trim().toDouble(), it[1].trim().toDouble())
    }
    // settings for the emulator scenarios (they stay saved, like the driver's own choices)
    intent?.getIntExtra("nm_tollmax", -1)?.takeIf { it >= 0 }?.let { v -> AppGraph.settings.update { it.copy(tollMaxExtraMin = v) } }
    intent?.getStringExtra("nm_tolls")?.let { t ->
      app.navmaster.truck.settings.TollPolicy.entries.firstOrNull { it.name.equals(t, true) }?.let { p -> AppGraph.settings.update { it.copy(tollPolicy = p) } }
    }
    intent?.getStringExtra("nm_view")?.let { v ->
      val view = if (v.equals("2d", true)) app.navmaster.truck.settings.DriveView.VIEW_2D else app.navmaster.truck.settings.DriveView.VIEW_3D
      AppGraph.settings.update { it.copy(driveView = view) }
    }
    intent?.getIntExtra("nm_poicount", -1)?.takeIf { it >= 0 }?.let { v -> AppGraph.settings.update { it.copy(poiRailCount = v) } }
    intent?.getIntExtra("nm_poisec", -1)?.takeIf { it >= 0 }?.let { v -> AppGraph.settings.update { it.copy(poiRailSeconds = v) } }
    val dest = intent?.getStringExtra("nm_dest") ?: return
    val parts = dest.split(',')
    if (parts.size != 2) return
    val c = GeographicCoordinate(parts[0].trim().toDouble(), parts[1].trim().toDouble())
    intent?.getStringExtra("nm_profile")?.let { AppGraph.profiles.select(it) }
    intent?.getStringExtra("nm_load")?.toDoubleOrNull()?.let { AppGraph.profiles.setLoad(it) }
    val label = intent?.getStringExtra("nm_label") ?: "Prova"
    // plan only: the route choice with its difficulties stays on screen
    if (intent?.getBooleanExtra("nm_plan", false) == true) vm.autoPlan(c, label)
    else vm.autoRun(c, label, intent?.getBooleanExtra("nm_sim", true) ?: true, intent?.getIntExtra("nm_variant", -1)?.takeIf { it >= 0 })
  }

  override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
    if (tts != null) {
      val r = tts.setLanguage(Locale.ITALY)
      Log.i(TAG, "TTS italiano: $r")
    } else {
      Log.e(TAG, "TTS non disponibile: $status")
    }
  }

  override fun onTtsSpeakError(utteranceId: String, status: Int) {
    Log.e(TAG, "TTS errore $utteranceId: $status")
  }

  override fun onTtsShutdownAndRelease() {
    Log.i(TAG, "TTS chiuso")
  }

  companion object {
    private const val TAG = "NavMaster"
  }
}
