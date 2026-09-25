package app.navmaster.truck.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Position from satellites and from the network (Wi-Fi / mobile cells) together.
 *
 * The network fix arrives in a second even indoors or at the first start in a new country, so the
 * map can centre on the driver and suggest the country's map before GPS has a fix. As soon as GPS
 * answers, network fixes are ignored unless GPS has been silent for a while (tunnels, parking
 * garages), so guidance keeps the precise position.
 */
class SmartLocationProvider(context: Context) : NavigationLocationProviding {
  private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  @Volatile private var lastGpsAt = 0L

  @SuppressLint("MissingPermission")
  override suspend fun lastLocation(): Location? = best(providers().mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() })

  private fun providers(): List<String> {
    val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
    val order = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) order += LocationManager.FUSED_PROVIDER
    return order.filter { it in enabled }
  }

  private fun best(list: List<Location>): Location? {
    val now = System.currentTimeMillis()
    return list.minByOrNull { l ->
      val ageS = (now - l.time).coerceAtLeast(0) / 1000.0
      (if (l.hasAccuracy()) l.accuracy.toDouble() else 500.0) + ageS * 2
    }
  }

  @SuppressLint("MissingPermission")
  override fun locationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
    val listeners = mutableListOf<LocationListener>()
    fun accept(l: Location) {
      val now = System.currentTimeMillis()
      if (l.provider == LocationManager.GPS_PROVIDER) {
        lastGpsAt = now
        trySend(l)
      } else if (now - lastGpsAt > 8000) {
        trySend(l)
      }
    }
    lastLocation()?.let { trySend(it) }
    val list = providers()
    Log.d(TAG, "location from ${list.joinToString()}")
    for (p in list) {
      val listener = LocationListener { accept(it) }
      val interval = if (p == LocationManager.GPS_PROVIDER) intervalMillis else maxOf(intervalMillis, 3000L)
      runCatching { lm.requestLocationUpdates(p, interval, 0f, listener, Looper.getMainLooper()) }
          .onSuccess { listeners += listener }
          .onFailure { Log.w(TAG, "$p: $it") }
    }
    awaitClose { listeners.forEach { lm.removeUpdates(it) } }
  }

  companion object {
    private const val TAG = "NavMasterLocation"
  }
}
