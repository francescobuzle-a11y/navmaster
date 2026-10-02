package app.navmaster.truck.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate

/**
 * Position from satellites and from the network (Wi-Fi / mobile cells) together, and through
 * tunnels the position worked out along the route.
 *
 * The network fix arrives in a second even indoors or at the first start in a new country, so the
 * map can centre on the driver and suggest the country's map before GPS has a fix. As soon as GPS
 * answers, network fixes are ignored unless GPS has been silent for a while (parking garages).
 *
 * In tunnels (dead reckoning, as the dedicated navigators do), during guidance: when the satellites
 * are silent the vehicle goes on along the route at the speed it had, so the map, the
 * distances and the voice keep moving instead of freezing at the tunnel mouth and jumping forward
 * at the exit; when GPS comes back it takes over again at once.
 */
class SmartLocationProvider(context: Context) : NavigationLocationProviding {
  private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
  @Volatile private var lastGpsAt = 0L

  // ---- the route being driven, for the tunnels (set by the guidance, null when not guiding)
  private class Line(val pts: List<GeographicCoordinate>, val cum: DoubleArray)

  @Volatile private var line: Line? = null

  fun setRoute(geometry: List<GeographicCoordinate>?) {
    line = if (geometry == null || geometry.size < 2) null else {
      val cum = DoubleArray(geometry.size)
      for (i in 1 until geometry.size) cum[i] = cum[i - 1] + dist(geometry[i - 1], geometry[i])
      Line(geometry, cum)
    }
    drAlong = -1.0
  }

  // the last good satellite fix: where it was on the route, how fast, when (elapsed realtime)
  @Volatile private var fixAlong = -1.0
  @Volatile private var fixSpeed = 0.0
  @Volatile private var fixAt = 0L
  @Volatile private var drAlong = -1.0
  @Volatile private var tunnel = false
  private var hintIndex = 0

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

  /** Where a satellite fix is along the route (metres from its start), null when off the route. */
  private fun alongOf(l: Location): Double? {
    val ln = line ?: return null
    val p = GeographicCoordinate(l.latitude, l.longitude)
    val n = ln.pts.size
    // near the last match first (the vehicle moves forward), the whole line when not found there
    var bestD = Double.MAX_VALUE
    var bestS = 0.0
    var bestI = -1
    fun scan(from: Int, to: Int) {
      for (i in from.coerceAtLeast(0) until to.coerceAtMost(n - 1)) {
        val (d, t) = segDist(p, ln.pts[i], ln.pts[i + 1])
        if (d < bestD) {
          bestD = d
          bestS = ln.cum[i] + (ln.cum[i + 1] - ln.cum[i]) * t
          bestI = i
        }
      }
    }
    scan(hintIndex - 20, hintIndex + 400)
    if (bestD > 60) scan(0, n - 1)
    if (bestI < 0 || bestD > 45) return null
    hintIndex = bestI
    return bestS
  }

  @SuppressLint("MissingPermission")
  override fun locationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
    val listeners = mutableListOf<LocationListener>()
    fun accept(l: Location) {
      val now = System.currentTimeMillis()
      if (l.provider == LocationManager.GPS_PROVIDER) {
        lastGpsAt = now
        val wasTunnel = tunnel
        tunnel = false
        drAlong = -1.0
        if (line != null) {
          val s = alongOf(l)
          val v = if (l.hasSpeed()) l.speed.toDouble() else 0.0
          if (s != null) {
            fixAlong = s
            // the speed measured by the satellites (Doppler): precise, and current when braking
            fixSpeed = v
            fixAt = SystemClock.elapsedRealtime()
          } else {
            fixAlong = -1.0
          }
          if (wasTunnel) Log.i(TAG, "satellites back: dead reckoning ends")
        }
        trySend(l)
      } else if (drAlong < 0 && now - lastGpsAt > 8000) {
        // the network position only when there is nothing better (never inside a tunnel being
        // driven through: there the position along the route is far more precise)
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
    // in a tunnel (no satellite fix for more than 1.5 s while driving on the route): a position
    // along the route every second at the speed of the entry, until the satellites are back
    val reckoning = launch {
      var lastSent = 0L
      while (isActive) {
        delay(80)
        val ln = line ?: continue
        if (fixAlong < 0 || fixSpeed < 2.5) {
          tunnel = false
          continue
        }
        val now = SystemClock.elapsedRealtime()
        val silent = now - fixAt
        // only when the satellites are silent (a tunnel): between two normal fixes the map itself
        // glides the arrow along the route; extra positions there made the map jump back and forth
        if (silent < 1500 || now - maxOf(lastSent, fixAt) < 1000) continue
        // at most 4 minutes or 8 km on the speed of the entry: a longer silence is not a tunnel
        if (silent > 240_000 || fixSpeed * silent / 1000.0 > 8000) {
          if (tunnel) Log.i(TAG, "dead reckoning stopped after ${silent / 1000} s")
          tunnel = false
          drAlong = -1.0
          fixAlong = -1.0
          continue
        }
        if (silent >= 1500 && !tunnel) {
          tunnel = true
          Log.i(TAG, "no satellites: dead reckoning along the route at ${"%.0f".format(fixSpeed * 3.6)} km/h")
        }
        val s = (fixAlong + fixSpeed * silent / 1000.0).coerceAtMost(ln.cum.last() - 1)
        drAlong = s
        lastSent = now
        trySend(locationAt(ln, s, fixSpeed))
      }
    }
    awaitClose {
      reckoning.cancel()
      listeners.forEach { lm.removeUpdates(it) }
    }
  }

  private fun locationAt(ln: Line, s: Double, speed: Double): Location {
    val a = pointAt(ln, s)
    val b = pointAt(ln, (s + 15).coerceAtMost(ln.cum.last()))
    val c = pointAt(ln, (s - 15).coerceAtLeast(0.0))
    return Location("dr").apply {
      latitude = a.lat
      longitude = a.lng
      time = System.currentTimeMillis()
      elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
      this.speed = speed.toFloat()
      bearing = bearing(c, b).toFloat()
      accuracy = 8f
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        speedAccuracyMetersPerSecond = 2f
        bearingAccuracyDegrees = 10f
      }
    }
  }

  companion object {
    private const val TAG = "NavMasterLocation"
    private const val R = 6_371_000.0

    private fun dist(a: GeographicCoordinate, b: GeographicCoordinate): Double {
      val la = Math.toRadians(a.lat)
      val lb = Math.toRadians(b.lat)
      val dLat = lb - la
      val dLon = Math.toRadians(b.lng - a.lng)
      val h = Math.sin(dLat / 2).let { it * it } + Math.cos(la) * Math.cos(lb) * Math.sin(dLon / 2).let { it * it }
      return 2 * R * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Distance of [p] from the segment a-b (metres) and where along it (0..1), on a local plane. */
    private fun segDist(p: GeographicCoordinate, a: GeographicCoordinate, b: GeographicCoordinate): Pair<Double, Double> {
      val k = Math.cos(Math.toRadians(p.lat))
      val ax = (a.lng - p.lng) * k * 111_320.0
      val ay = (a.lat - p.lat) * 110_540.0
      val bx = (b.lng - p.lng) * k * 111_320.0
      val by = (b.lat - p.lat) * 110_540.0
      val dx = bx - ax
      val dy = by - ay
      val l2 = dx * dx + dy * dy
      val t = if (l2 <= 1e-9) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
      val x = ax + dx * t
      val y = ay + dy * t
      return Math.sqrt(x * x + y * y) to t
    }

    private fun pointAt(ln: Line, s: Double): GeographicCoordinate {
      val cum = ln.cum
      if (s <= 0) return ln.pts.first()
      if (s >= cum.last()) return ln.pts.last()
      var lo = 0
      var hi = cum.size - 1
      while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (cum[mid] <= s) lo = mid else hi = mid
      }
      val seg = cum[hi] - cum[lo]
      val t = if (seg <= 0) 0.0 else (s - cum[lo]) / seg
      val a = ln.pts[lo]
      val b = ln.pts[hi]
      return GeographicCoordinate(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    private fun bearing(a: GeographicCoordinate, b: GeographicCoordinate): Double {
      val la = Math.toRadians(a.lat)
      val lb = Math.toRadians(b.lat)
      val dLon = Math.toRadians(b.lng - a.lng)
      val y = Math.sin(dLon) * Math.cos(lb)
      val x = Math.cos(la) * Math.sin(lb) - Math.sin(la) * Math.cos(lb) * Math.cos(dLon)
      return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }
  }
}
