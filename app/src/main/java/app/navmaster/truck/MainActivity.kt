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
    // ready to use on a real tablet: the traffic from Waze on (once; the driver can turn it off)
    val emulator = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")
    if (!emulator && AppGraph.settings.settings.value.readyDefaults < 1) {
      AppGraph.settings.update { it.copy(personalFeed = true, personalFeedDirect = true, readyDefaults = 1) }
    }
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
    intent?.getStringExtra("nm_probecmp")?.let { pr ->
      val pts = pr.split('_').mapNotNull { q -> q.split(',').takeIf { it.size == 2 }?.let { GeographicCoordinate(it[0].trim().toDouble(), it[1].trim().toDouble()) } }
      intent?.getStringExtra("nm_load")?.toDoubleOrNull()?.let { AppGraph.profiles.setLoad(it) }
      if (pts.size == 2) vm.probeCompare(pts[0], pts[1], "camper", intent?.getStringExtra("nm_profile") ?: "camion")
    }
    intent?.getStringExtra("nm_probe")?.let { pr ->
      intent?.getStringExtra("nm_profile")?.let { AppGraph.profiles.select(it) }
      intent?.getStringExtra("nm_load")?.toDoubleOrNull()?.let { AppGraph.profiles.setLoad(it) }
      val dbg = intent?.getStringExtra("nm_costing")?.split(',')?.mapNotNull { kv ->
        kv.split(':').takeIf { it.size == 2 }?.let { it[0] to (it[1].toDoubleOrNull() ?: return@mapNotNull null) } }?.toMap() ?: emptyMap()
      vm.probe(debugCosting = dbg, points = pr.split(';', '_').mapNotNull { q -> q.split(',').takeIf { it.size == 2 }?.let { GeographicCoordinate(it[0].trim().toDouble(), it[1].trim().toDouble()) } })
    }
    intent?.getStringExtra("nm_night")?.let { n ->
      val mode = when (n) { "night" -> app.navmaster.truck.settings.NightMode.NIGHT; "day" -> app.navmaster.truck.settings.NightMode.DAY
        else -> app.navmaster.truck.settings.NightMode.AUTO }
      AppGraph.settings.update { it.copy(nightMode = mode) }
    }
    if (intent?.getBooleanExtra("nm_simtest", false) == true) vm.simSelfTest()
    intent?.getStringExtra("nm_live_prefix")?.let { app.navmaster.truck.live.SharedReports.prefix = it }
    intent?.getStringExtra("nm_tomtom_key")?.let { k -> AppGraph.settings.update { it.copy(tomtomKey = if (k == "none") "" else k) } }
    // emulator test of the direct mode on a sample file (never the real service from the tests)
    intent?.getStringExtra("nm_waze_direct")?.let { u ->
      app.navmaster.truck.live.PersonalFeed.directUrl = u
      AppGraph.settings.update { it.copy(personalFeed = true, personalFeedDirect = true) }
    }
    intent?.getStringExtra("nm_personal_feed")?.let { u ->
      AppGraph.settings.update { it.copy(personalFeed = u != "none", personalFeedUrl = if (u == "none") "" else u, personalFeedDirect = false) }
    }
    if (intent?.hasExtra("nm_traffic_map") == true) {
      val v = intent?.getBooleanExtra("nm_traffic_map", false) == true
      AppGraph.settings.update { it.copy(trafficOnMap = v) }
    }
    val reportAhead = intent?.getStringExtra("nm_report_ahead")?.split(':')
    val detour = intent?.getStringExtra("nm_detour")?.split(',')?.takeIf { it.size == 2 }?.let {
      GeographicCoordinate(it[0].trim().toDouble(), it[1].trim().toDouble())
    }
    vm.scheduleLiveTests(app.navmaster.truck.live.LiveKind.of(reportAhead?.getOrNull(0)), reportAhead?.getOrNull(1)?.toDoubleOrNull() ?: 2000.0,
        detour, intent?.getBooleanExtra("nm_livetest", false) == true)
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
