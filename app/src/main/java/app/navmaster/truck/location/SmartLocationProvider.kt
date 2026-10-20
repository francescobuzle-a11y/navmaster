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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  private class Line(val pts: List<GeographicCoordinate>, val cum: DoubleArray, val wide: List<DoubleArray>) {
    /** The route is wide here (a toll plaza: twenty lanes, the fix 60 m from the line and still on the road). */
    fun isWide(s: Double): Boolean = wide.any { s >= it[0] && s <= it[1] }
  }

  @Volatile private var line: Line? = null

  // ---- the speed of the fixes checked against the speed measured from the positions
  private var spLat = 0.0
  private var spLon = 0.0
  private var spAt = 0L
  private var measured = -1.0
  private var implausible = 0
  private var plausible = 0
  @Volatile private var speedUnreliable = false
  private var speedKmh = false

  /**
   * The speed to use for a fix: the receiver's own (Doppler: precise and current) as long as it
   * agrees with the speed measured from the positions; when the receiver's is wrong for fixes in a
   * row (some external GPS receivers and the apps that feed them to Android give km/h instead of
   * m/s, or a stale value), the measured speed is used instead (divided by 3.6 if the receiver's is
   * just km/h). A speed too high sent the arrow and the instructions ahead of the vehicle: the
   * position was carried on at that speed between fixes and the manoeuvres were said and advanced
   * as if it were already there.
   */
  private fun plausibleSpeed(l: Location, acc: Double, el: Long): Double {
    val reported = if (l.hasSpeed()) l.speed.toDouble() else 0.0
    val dt = (el - spAt) / 1000.0
    if (spAt > 0L && dt in 0.3..5.0 && acc <= 25.0) {
      val vm = dist(GeographicCoordinate(spLat, spLon), GeographicCoordinate(l.latitude, l.longitude)) / dt
      measured = if (measured < 0) vm else measured * 0.5 + vm * 0.5
    } else if (dt > 5.0) measured = -1.0
    if (acc <= 25.0) {
      spLat = l.latitude
      spLon = l.longitude
      spAt = el
    }
    if (measured < 0 || !l.hasSpeed()) return if (speedUnreliable && measured >= 0) measured else reported
    // the receiver's speed is wrong when it is well above the measured one while moving (an
    // acceleration explains +50% and 3 m/s, not more), or clearly moving while the receiver says
    // nothing; fixes in a row decide, one odd fix does not
    val wrong = (measured > 2.0 && reported > measured * 1.5 + 3.0) || (measured > 4.0 && reported < 0.5)
    if (wrong) {
      implausible++
      plausible = 0
    } else if (measured > 3.0) {
      // only while moving: crawling in a queue tells nothing about the receiver
      plausible++
      implausible = 0
    }
    if (!speedUnreliable && implausible >= 3) {
      speedUnreliable = true
      speedKmh = measured > 2.0 && reported / measured in 3.2..4.0
      Log.w(TAG, "speed of the receiver not plausible (says ${"%.1f".format(java.util.Locale.ROOT, reported)} m/s, " +
          "measured ${"%.1f".format(java.util.Locale.ROOT, measured)} m/s): the measured one is used" + (if (speedKmh) " (km/h given as m/s)" else ""))
    } else if (speedUnreliable && plausible >= 8) {
      speedUnreliable = false
      Log.i(TAG, "speed of the receiver plausible again")
    }
    return if (!speedUnreliable) reported else if (speedKmh) reported / 3.6 else measured
  }

  // the last direction of travel measured while moving (for a route asked while standing still)
  @Volatile private var lastCourse = Double.NaN
  @Volatile private var lastCourseAt = 0L
  @Volatile private var lastCourseLat = 0.0
  @Volatile private var lastCourseLon = 0.0

  /**
   * The direction the vehicle was going the last time it moved, if that was near ([lat], [lon])
   * (within 250 m) and recently (10 minutes): a vehicle standing still (a queue at the toll
   * plaza, a light) has no course in its fix, but it is still facing the way it came, and a route
   * asked then must start on its carriageway, not on the opposite one that happens to be nearer.
   */
  fun courseNear(lat: Double, lon: Double): Double? {
    if (lastCourse.isNaN() || System.currentTimeMillis() - lastCourseAt > 600_000) return null
    if (dist(GeographicCoordinate(lat, lon), GeographicCoordinate(lastCourseLat, lastCourseLon)) > 250) return null
    return lastCourse
  }

  /**
   * The route being driven. [wide]: stretches (metres from the start, [from, to]) where the road is
   * far wider than one carriageway (toll plazas): there a fix up to 90 m from the line, going the
   * route's way, still counts as on the route; elsewhere 30 m.
   */
  fun setRoute(geometry: List<GeographicCoordinate>?, wide: List<DoubleArray> = emptyList()) {
    line = if (geometry == null || geometry.size < 2) null else {
      val cum = DoubleArray(geometry.size)
      for (i in 1 until geometry.size) cum[i] = cum[i - 1] + dist(geometry[i - 1], geometry[i])
      Line(geometry, cum, wide)
    }
    drAlong = -1.0
    hintIndex = 0
    _onRoute.value = null
  }

  /**
   * The last position on the route as this provider worked it out from the satellites (or along
   * the route in a tunnel): metres from the start of the route, the speed, when (System.nanoTime,
   * the clock of the frames), the accuracy. The arrow on the map glides from here (ui.MainScreen
   * SmoothTrack), not
   * from the guidance's own snapping, which stands still at the end of a step until the step moves
   * on and then jumps.
   */
  class Fix(val alongM: Double, val speedMs: Double, val atNanos: Long, val accuracyM: Double, val reckoned: Boolean,
            /** Metres to the right of the route's line (negative: left), for the lane the vehicle is in. */
            val lateralM: Double = 0.0)

  @Volatile private var fixLateral = 0.0

  private val _onRoute = MutableStateFlow<Fix?>(null)
  val onRoute: StateFlow<Fix?> = _onRoute.asStateFlow()

  /**
   * The last position handed on, as the satellites gave it (never moved onto the route): the arrow
   * on the map shows it as it is whenever the vehicle is off the route, on a track, a yard, a field.
   */
  private val _raw = MutableStateFlow<Location?>(null)
  val raw: StateFlow<Location?> = _raw.asStateFlow()

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
    if (bestD > 90) scan(0, n - 1)
    // off the route from 30 m aside, and sooner when the vehicle clearly goes another way than the
    // route there (a slip road leaving the motorway, a turn not planned): the arrow must leave the
    // route the moment the vehicle does. At a toll plaza (Line.wide) the lanes spread over 100 m:
    // up to 90 m aside, going the route's way, the vehicle is still on it (before, a lorry in a
    // side lane of the plaza was "off the route" and the route was recalculated from the nearest
    // road, at times a ramp: off the motorway and back onto it)
    if (bestI < 0) return null
    val wide = bestD > 30 && ln.isWide(bestS)
    if (bestD > 30 && !(wide && bestD <= 90)) return null
    val moving = l.hasBearing() && l.hasSpeed() && l.speed >= 3f
    if (bestD > 6 && moving) {
      val rb = bearing(ln.pts[bestI], ln.pts[bestI + 1])
      val diff = ((l.bearing - rb + 540.0) % 360.0) - 180.0
      if (Math.abs(diff) > (if (wide) 35 else 50)) return null
    } else if (wide && !moving && bestD > 60) {
      // far from the line and no direction to tell by: not on the route
      return null
    }
    hintIndex = bestI
    // on which side of the line: the cross product of the segment and the point (right is negative)
    val a = ln.pts[bestI]
    val b = ln.pts[bestI + 1]
    val k = Math.cos(Math.toRadians(p.lat))
    val ex = (b.lng - a.lng) * k
    val ey = b.lat - a.lat
    val px = (p.lng - a.lng) * k
    val py = p.lat - a.lat
    fixLateral = if (ex * py - ey * px > 0) -bestD else bestD
    return bestS
  }

  @SuppressLint("MissingPermission")
  override fun locationUpdates(intervalMillis: Long): Flow<Location> = callbackFlow {
    val listeners = mutableListOf<LocationListener>()
    // the last position handed on (to hold it still while the vehicle stands)
    var lastSent: Location? = null
    var lastOnRouteAt = 0L
    fun send(l: Location) {
      lastSent = l
      _raw.value = l
      trySend(l)
    }
    fun accept(l: Location) {
      val now = System.currentTimeMillis()
      if (l.hasBearing() && l.hasSpeed() && l.speed >= 3f && l.provider != LocationManager.NETWORK_PROVIDER) {
        lastCourse = l.bearing.toDouble()
        lastCourseAt = now
        lastCourseLat = l.latitude
        lastCourseLon = l.longitude
      }
      if (l.provider == LocationManager.GPS_PROVIDER) {
        val el = SystemClock.elapsedRealtime()
        val ln = line
        if (ln == null) {
          lastGpsAt = now
          send(l)
          return
        }
        val s = alongOf(l)
        val acc = if (l.hasAccuracy()) l.accuracy.toDouble() else 10.0
        val v = plausibleSpeed(l, acc, el)
        val driving = fixAlong >= 0 && fixSpeed >= 3.0 && el - fixAt < 30_000
        // a poor fix while driving on the route (tunnel mouths, under bridges, between buildings:
        // positions tens of metres off) is not handed on: it made the arrow jump sideways or
        // backwards and could start a needless recalculation. For the guidance it is as if the
        // satellites were silent, so the position goes on along the route (below) until good fixes
        // come back. A GOOD fix off the route is handed on at once (the vehicle really left it: the
        // arrow and the recalculation must follow immediately); a doubtful one (over 20 m of
        // accuracy) only after 4 s off.
        if (driving && (acc > 35.0 || (s == null && acc > 20.0 && el - lastOnRouteAt < 4_000))) {
          Log.d(TAG, "poor fix left out (accuracy ${acc.toInt()} m, on route ${s != null})")
          return
        }
        lastGpsAt = now
        val wasTunnel = tunnel
        tunnel = false
        drAlong = -1.0
        if (s != null) {
          lastOnRouteAt = el
          fixAlong = s
          // the speed measured by the satellites (Doppler): precise, and current when braking
          fixSpeed = v
          fixAt = el
          _onRoute.value = Fix(s, if (v < 0.8) 0.0 else v, System.nanoTime(), acc, false, fixLateral)
        } else {
          fixAlong = -1.0
          _onRoute.value = null
        }
        if (wasTunnel) Log.i(TAG, "satellites back: dead reckoning ends")
        // standing still (a queue, a light, a roundabout entry): the fixes wander by some metres;
        // the position handed on stays where it is, so the map does not twitch
        val prev = lastSent
        if (s != null && v < 0.8 && prev != null && prev.distanceTo(l) < 12f) {
          send(Location(prev).apply {
            time = l.time
            elapsedRealtimeNanos = l.elapsedRealtimeNanos
            speed = 0f
          })
          return
        }
        // a speed the receiver gets wrong is corrected for the guidance and the speedometer too
        if (l.hasSpeed() && kotlin.math.abs(l.speed - v) > 0.3) send(Location(l).apply { speed = v.toFloat() }) else send(l)
      } else if (drAlong < 0 && now - lastGpsAt > 8000) {
        // the network position only when there is nothing better (never inside a tunnel being
        // driven through: there the position along the route is far more precise)
        send(l)
      }
    }
    lastLocation()?.let { trySend(it) }
    val list = providers()
    Log.d(TAG, "location from ${list.joinToString()}")
    for (p in list) {
      // every method spelt out: on Android 10 they have no default and switching a provider off
      // crashed the app (AbstractMethodError onProviderDisabled)
      val listener = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) = accept(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
      }
      val interval = if (p == LocationManager.GPS_PROVIDER) intervalMillis else maxOf(intervalMillis, 3000L)
      runCatching { lm.requestLocationUpdates(p, interval, 0f, listener, Looper.getMainLooper()) }
          .onSuccess { listeners += listener }
          .onFailure { Log.w(TAG, "$p: $it") }
    }
    // in a tunnel (no satellite fix for more than 1.5 s while driving on the route): a position
    // along the route every second at the speed of the entry, until the satellites are back
    val reckoning = launch {
      var lastDr = 0L
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
        // (3 s: a receiver that skips a fix or two must not have the position carried on at full
        // speed, which put the arrow ahead of the vehicle at every braking before a turn)
        if (silent < 3000 || now - maxOf(lastDr, fixAt) < 1000) continue
        // at most 4 minutes or 8 km on the speed of the entry: a longer silence is not a tunnel
        if (silent > 240_000 || fixSpeed * silent / 1000.0 > 8000) {
          if (tunnel) Log.i(TAG, "dead reckoning stopped after ${silent / 1000} s")
          tunnel = false
          drAlong = -1.0
          fixAlong = -1.0
          _onRoute.value = null
          continue
        }
        if (silent >= 1500 && !tunnel) {
          tunnel = true
          Log.i(TAG, "no satellites: dead reckoning along the route at ${"%.0f".format(fixSpeed * 3.6)} km/h")
        }
        val s = (fixAlong + fixSpeed * silent / 1000.0).coerceAtMost(ln.cum.last() - 1)
        drAlong = s
        lastDr = now
        _onRoute.value = Fix(s, fixSpeed, System.nanoTime(), 8.0, true)
        send(locationAt(ln, s, fixSpeed))
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
