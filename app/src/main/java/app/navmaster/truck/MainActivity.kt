package app.navmaster.truck

import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import app.navmaster.truck.nav.NavViewModel
import app.navmaster.truck.ui.MainScreen
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import java.util.Locale
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.createFerrostarLogger

class MainActivity : ComponentActivity(), AndroidTtsStatusListener {
  private val vm: NavViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppGraph.tts.statusObserver = this
    AppGraph.ferrostar.spokenInstructionObserver = AppGraph.tts
    createFerrostarLogger()
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    setContent { MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF2EB85C), onPrimary = androidx.compose.ui.graphics.Color.White, secondary = androidx.compose.ui.graphics.Color(0xFF2EB85C))) { MainScreen(vm) } }
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
    val dest = intent?.getStringExtra("nm_dest") ?: return
    val parts = dest.split(',')
    if (parts.size != 2) return
    val c = GeographicCoordinate(parts[0].trim().toDouble(), parts[1].trim().toDouble())
    intent?.getStringExtra("nm_profile")?.let { AppGraph.profiles.select(it) }
    intent?.getStringExtra("nm_load")?.toDoubleOrNull()?.let { AppGraph.profiles.setLoad(it) }
    vm.autoRun(c, intent?.getStringExtra("nm_label") ?: "Prova", intent?.getBooleanExtra("nm_sim", true) ?: true)
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
