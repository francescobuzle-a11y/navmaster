package app.navmaster.truck.location

import android.location.Location
import android.os.Build
import android.os.SystemClock
import app.navmaster.truck.core.Geo
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route

/**
 * The simulated drive, as a lorry drives: on each stretch the speed the route was computed with
 * (openrouteservice's time of the step), slowing down before bends, turns and roundabouts as much
 * as their curve asks (the sideways acceleration a loaded lorry takes), braking and pulling away
 * at a lorry's pace, stopping at the end. [warp] runs it faster than real (the speedometer shows
 * the vehicle's own speed, not the sped-up one). Before, the simulation went 10 m at every tick
 * whatever the road: the same pace in a village street and on the motorway.
 */
class NmSimulator {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _loc = MutableStateFlow<Location?>(null)
  private var job: Job? = null
  @Volatile private var speedNow = 0.0

  /** How many times faster than real. */
  @Volatile var warp: Int = 3

  val updates: Flow<Location> = _loc.filterNotNull()

  fun last(): Location? = _loc.value

  /** The speed profile of a route: every [STEP] metres the point, the direction, the speed (m/s). */
  private class Plan(val lat: DoubleArray, val lon: DoubleArray, val bearing: DoubleArray, val v: DoubleArray) {
    val n get() = lat.size
  }

  fun setRoute(route: Route) {
    val carry = if (job?.isActive == true) speedNow else 0.0
    job?.cancel()
    val plan = plan(route, carry) ?: return
    _loc.value = location(plan, 0.0, plan.v[0])
    job = scope.launch { run(plan) }
  }

  fun stop() {
    job?.cancel()
    job = null
    speedNow = 0.0
  }

  private suspend fun run(p: Plan) {
    var s = 0.0
    val total = (p.n - 1) * STEP
    var last = SystemClock.elapsedRealtime()
    var sent = 0L
    while (scope.isActive) {
      delay(TICK_MS)
      val now = SystemClock.elapsedRealtime()
      val dt = (now - last).coerceIn(0L, 500L) / 1000.0 * warp
      last = now
      val v = speedAt(p, s)
      s = min(total, s + max(v, 0.3) * dt)
      speedNow = if (s >= total) 0.0 else speedAt(p, s)
      // five positions a second for the guidance and the map (the arrow glides between them)
      if (now - sent >= SEND_MS || s >= total) {
        sent = now
        _loc.value = location(p, s, speedNow)
      }
      if (s >= total) break
    }
  }

  private fun speedAt(p: Plan, s: Double): Double {
    val f = s / STEP
    val i = f.toInt().coerceIn(0, p.n - 1)
    val j = (i + 1).coerceAtMost(p.n - 1)
    val t = f - i
    return p.v[i] + (p.v[j] - p.v[i]) * t
  }

  private fun location(p: Plan, s: Double, speed: Double): Location {
    val f = s / STEP
    val i = f.toInt().coerceIn(0, p.n - 1)
    val j = (i + 1).coerceAtMost(p.n - 1)
    val t = (f - i).coerceIn(0.0, 1.0)
    return Location("sim").apply {
      latitude = p.lat[i] + (p.lat[j] - p.lat[i]) * t
      longitude = p.lon[i] + (p.lon[j] - p.lon[i]) * t
      bearing = p.bearing[i].toFloat()
      this.speed = speed.toFloat()
      accuracy = 3f
      time = System.currentTimeMillis()
      elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        speedAccuracyMetersPerSecond = 0.5f
        bearingAccuracyDegrees = 5f
      }
    }
  }

  companion object {
    private const val STEP = 5.0
    private const val TICK_MS = 100L
    private const val SEND_MS = 200L
    /** A lorry: sideways acceleration taken in a bend, braking, pulling away (m/s²). */
    private const val LATERAL = 1.6
    private const val BRAKE = 1.1
    private const val PULL = 0.55

    /** The speed profile of [route], starting at [startSpeed]. */
    private fun plan(route: Route, startSpeed: Double): Plan? {
      val g = route.geometry
      if (g.size < 2) return null
      val cum = Geo.cumulative(g)
      val length = cum.last()
      if (length < STEP) return null
      val n = (length / STEP).toInt() + 1
      val lat = DoubleArray(n); val lon = DoubleArray(n); val bearing = DoubleArray(n)
      var k = 0
      for (i in 0 until n) {
        val s = i * STEP
        while (k < cum.size - 2 && cum[k + 1] < s) k++
        val seg = (cum[k + 1] - cum[k]).coerceAtLeast(1e-6)
        val t = ((s - cum[k]) / seg).coerceIn(0.0, 1.0)
        lat[i] = g[k].lat + (g[k + 1].lat - g[k].lat) * t
        lon[i] = g[k].lng + (g[k + 1].lng - g[k].lng) * t
      }
      for (i in 0 until n) {
        val a = (i - 2).coerceAtLeast(0); val b = (i + 2).coerceAtMost(n - 1)
        bearing[i] = if (a == b) 0.0 else Geo.bearing(GeographicCoordinate(lat[a], lon[a]), GeographicCoordinate(lat[b], lon[b]))
      }
      // the cruising speed of each step: its length over its time (openrouteservice's speeds)
      val v = DoubleArray(n) { 13.9 }
      var at = 0.0
      for (st in route.steps) {
        val cruise = if (st.duration > 1 && st.distance > 1) (st.distance / st.duration).coerceIn(3.0, 36.0) else 13.9
        val i0 = (at / STEP).toInt().coerceIn(0, n - 1)
        val i1 = ((at + st.distance) / STEP).toInt().coerceIn(0, n - 1)
        for (i in i0..i1) v[i] = cruise
        at += st.distance
      }
      // the bends: the change of direction over 20 m on each side gives the radius, and the speed
      // that keeps the sideways acceleration of a lorry
      for (i in 4 until n - 4) {
        val h0 = Geo.bearing(GeographicCoordinate(lat[i - 4], lon[i - 4]), GeographicCoordinate(lat[i], lon[i]))
        val h1 = Geo.bearing(GeographicCoordinate(lat[i], lon[i]), GeographicCoordinate(lat[i + 4], lon[i + 4]))
        val turn = Math.toRadians(abs(Geo.angleDiff(h0, h1)))
        if (turn < 0.05) continue
        val radius = (4 * STEP) / turn
        v[i] = min(v[i], max(2.5, sqrt(LATERAL * radius)))
      }
      v[0] = min(v[0], startSpeed)
      v[n - 1] = 0.0
      // braking before (backwards) and pulling away after (forwards), at a lorry's pace
      for (i in n - 2 downTo 0) v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * BRAKE * STEP))
      for (i in 1 until n) v[i] = min(v[i], sqrt(v[i - 1] * v[i - 1] + 2 * PULL * STEP))
      return Plan(lat, lon, bearing, v)
    }
  }
}

/**
 * The position for the guidance: the satellites (SmartLocationProvider), or the simulated drive
 * ([NmSimulator]) while one runs.
 */
class NmLocationProvider(private val live: NavigationLocationProviding, private val sim: NmSimulator) : NavigationLocationProviding {
  private val _isSimulating = MutableStateFlow(false)
  val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

  fun enableSimulationOn(route: Route) {
    sim.setRoute(route)
    _isSimulating.value = true
  }

  fun disableSimulation() {
    sim.stop()
    _isSimulating.value = false
  }

  override suspend fun lastLocation(): Location? = if (_isSimulating.value) sim.last() else live.lastLocation()

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun locationUpdates(intervalMillis: Long): Flow<Location> =
      _isSimulating.flatMapLatest { simulating -> if (simulating) sim.updates else live.locationUpdates(intervalMillis) }
}
