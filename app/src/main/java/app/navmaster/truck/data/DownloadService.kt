package app.navmaster.truck.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.navmaster.truck.AppGraph
import app.navmaster.truck.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the download of the maps going with the screen off or the app in the background (a
 * notification shows the progress), with the processor and the Wi-Fi kept awake. It stops by
 * itself when nothing is left to download.
 */
class DownloadService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var wake: PowerManager.WakeLock? = null
  private var wifi: WifiManager.WifiLock? = null
  private var lastNotify = 0L

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scaricamento mappe", NotificationManager.IMPORTANCE_LOW))
    try {
      startForeground(ID, notification("Preparazione…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } catch (e: Exception) {
      Log.w(TAG, "download service: $e")
    }
    runCatching {
      wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "navmaster:download").apply {
        setReferenceCounted(false)
        acquire(8 * 3600_000L)
      }
    }
    runCatching {
      wifi = applicationContext.getSystemService(WifiManager::class.java)
          .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "navmaster:download").apply {
            setReferenceCounted(false)
            acquire()
          }
    }
    scope.launch {
      AppGraph.regions.states.collect { states ->
        val active = states.values.filter { it is DownloadState.Running || it is DownloadState.Queued }
        if (active.isEmpty()) {
          stopForeground(STOP_FOREGROUND_REMOVE)
          stopSelf()
          return@collect
        }
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return@collect
        lastNotify = now
        val r = states.values.filterIsInstance<DownloadState.Running>().firstOrNull()
        nm.notify(ID, notification(r?.step ?: "In coda…", r?.doneBytes ?: 0, r?.totalBytes ?: 0))
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

  override fun onTimeout(startId: Int, fgsType: Int) {
    // Android limits long background transfers: the download resumes at the next start of the app
    stopSelf()
  }

  override fun onDestroy() {
    scope.cancel()
    runCatching { wake?.release() }
    runCatching { wifi?.release() }
    super.onDestroy()
  }

  private fun notification(text: String, done: Long, total: Long): Notification {
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)
    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("NavMaster · mappe offline" + if (total > 0) " · $pct%" else "")
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, pct, total <= 0)
        .setContentIntent(open)
        .build()
  }

  companion object {
    private const val TAG = "NavMasterData"
    private const val CHANNEL = "downloads"
    private const val ID = 4711

    fun start(context: Context) {
      try {
        context.startForegroundService(Intent(context, DownloadService::class.java))
      } catch (e: Exception) {
        // not allowed from the background: the download goes on while the app is open
        Log.w(TAG, "download service not started: $e")
      }
    }
  }
}
