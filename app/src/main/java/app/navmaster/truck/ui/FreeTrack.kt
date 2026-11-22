package app.navmaster.truck.ui

import app.navmaster.truck.core.Geo
import uniffi.ferrostar.GeographicCoordinate

/**
 * The arrow where the vehicle is when it is not on the route (a road off the route, a recalculation
 * under way) or there is no route (the map open while driving): it glides at every frame. Its target
 * is the last satellite fix carried on along its course at its speed since it was measured (at most
 * 1.6 s, a fix a second measured some hundreds of ms before it arrives; then slowing down to a
 * stop in a second: a receiver that skips a fix does not send the arrow ahead nor stops it dead); the arrow moves at the
 * vehicle's speed and is pulled softly towards the target (half a second, at most 30% of the speed
 * + 1.5 m/s), so a new fix corrects it without a hop, and it jumps only when far (60 m: a position lost for long). Its
 * direction turns smoothly the short way round; standing still or below 2 m/s it keeps the last
 * one, and fixes wandering within 25 m of a vehicle standing still do not move it.
 * Times are System.nanoTime, the clock of the frames.
 */
internal class FreeTrack {
  private var fixLat = Double.NaN
  private var fixLon = 0.0
  private var fixAt = 0L
  private var speed = 0.0
  private var course = Double.NaN
  private var accuracy = 10.0
  private var speedObj: uniffi.ferrostar.Speed? = null
  private var lat = Double.NaN
  private var lon = 0.0
  private var brg = Double.NaN
  private var lastFrame = 0L
  /** The last fix as the receiver gave it (the target may be held where the vehicle stopped). */
  private var rawLat = Double.NaN
  private var rawLon = 0.0
  private var rawAt = 0L

  /** Goes on from the arrow drawn last (the route's, before a recalculation), if any. */
  fun seed(from: uniffi.ferrostar.UserLocation?) {
    lastFrame = 0L
    if (from == null) return
    lat = from.coordinates.lat
    lon = from.coordinates.lng
    from.courseOverGround?.degrees?.toDouble()?.let { brg = it }
  }

  /**
   * A satellite fix: [speed] m/s and [bearing] degrees (NaN when the receiver gives none: then
   * worked out from the fix before), [accuracy] metres, [measuredAt] System.nanoTime when it was measured.
   */
  fun feed(fixLatitude: Double, fixLongitude: Double, speed: Double, bearing: Double, accuracy: Double, measuredAt: Long) {
    var v = speed
    var b = bearing
    if (v.isNaN()) {
      val dtS = (measuredAt - rawAt) / 1e9
      v = if (!rawLat.isNaN() && dtS in 0.2..5.0) Geo.dist(rawLat, rawLon, fixLatitude, fixLongitude) / dtS else 0.0
      if (b.isNaN() && v >= 2.0) b = Geo.bearing(GeographicCoordinate(rawLat, rawLon), GeographicCoordinate(fixLatitude, fixLongitude))
    }
    rawLat = fixLatitude
    rawLon = fixLongitude
    rawAt = measuredAt
    this.accuracy = accuracy
    speedObj = if (speed.isNaN()) null else uniffi.ferrostar.Speed(speed, 1.0)
    if (!b.isNaN() && v >= 2.0) course = b
    // standing still: the target stays where the vehicle stopped (the fixes wander by some metres
    // around it, 10-15 m at times under buildings), unless a fix is really far; the arrow still
    // glides there if it had not caught up yet
    if (v < 0.8 && !fixLat.isNaN() && Geo.dist(fixLat, fixLon, fixLatitude, fixLongitude) < 25.0) {
      this.speed = 0.0
    } else {
      fixLat = fixLatitude
      fixLon = fixLongitude
      this.speed = v
    }
    fixAt = measuredAt
  }

  /** The arrow is where the vehicle stands: no frame would move it (the screen need not be redrawn until the next fix). */
  fun settled(): Boolean = !fixLat.isNaN() && !lat.isNaN() && speed < 1.0 && Geo.dist(lat, lon, fixLat, fixLon) < 0.05

  fun step(t: Long): uniffi.ferrostar.UserLocation? {
    if (fixLat.isNaN()) return null
    val dt = if (lastFrame == 0L) 0.0 else ((t - lastFrame) / 1e9).coerceIn(0.0, 0.1)
    lastFrame = t
    val moving = speed >= 1.0 && !course.isNaN()
    val cRad = if (moving) Math.toRadians(course) else 0.0
    val mLat = 1.0 / 110_540.0
    val mLon = 1.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(fixLat)))
    // the target: the fix carried on along its course since it was measured, at full speed for
    // HOLD_S, then slowing down to a stop over EASE_S (a fix that does not come: the arrow neither
    // runs ahead of the vehicle nor stops dead)
    val ahead = ((t - fixAt) / 1e9).coerceAtLeast(0.0)
    val run = if (moving) speed * runAfter(ahead) else 0.0
    val tLat = fixLat + run * kotlin.math.cos(cRad) * mLat
    val tLon = fixLon + run * kotlin.math.sin(cRad) * mLon
    if (lat.isNaN() || Geo.dist(lat, lon, tLat, tLon) > 60.0) {
      lat = tLat
      lon = tLon
    } else {
      // on at the vehicle's speed (no lag behind it), as the target moves
      if (moving) {
        val r = rateAt(ahead)
        lat += speed * r * dt * kotlin.math.cos(cRad) * mLat
        lon += speed * r * dt * kotlin.math.sin(cRad) * mLon
      }
      // pulled towards the target, at most 30% of the speed + 1.5 m/s for the few metres of a new
      // fix (caught up in a second or two, gliding, never a hop), faster for a wider gap (the arrow
      // of the route it took over from was some way off)
      val k = 1.0 - kotlin.math.exp(-dt / 0.5)
      val gy = (tLat - lat) / mLat
      val gx = (tLon - lon) / mLon
      val gap = Math.hypot(gx, gy)
      val c = gap * k
      val cap = (0.3 * speed + 1.5 + 1.5 * maxOf(0.0, gap - 6.0)) * dt
      val f = if (c > cap && c > 0) cap / c * k else k
      lat += gy * f * mLat
      lon += gx * f * mLon
    }
    if (!course.isNaN() && speed >= 2.0) {
      brg = if (brg.isNaN()) course else DriveCamera.lerpAngle(brg, course, 1.0 - kotlin.math.exp(-dt / 0.4))
    }
    val b = brg
    return uniffi.ferrostar.UserLocation(
        GeographicCoordinate(lat, lon), accuracy,
        if (b.isNaN()) null else uniffi.ferrostar.CourseOverGround(((kotlin.math.round(b).toInt() % 360 + 360) % 360).toUShort(), 5u.toUShort()),
        java.time.Instant.now(), speedObj)
  }

  /** Metres covered per m/s after [a] seconds: [a] up to HOLD_S, then slowing down to a stop over EASE_S. */
  private fun runAfter(a: Double): Double {
    if (a <= HOLD_S) return a
    val e = minOf(a - HOLD_S, EASE_S)
    return HOLD_S + e - e * e / (2 * EASE_S)
  }

  /** The speed at [a] seconds, as a fraction of the fix's (the derivative of [runAfter]). */
  private fun rateAt(a: Double): Double = if (a <= HOLD_S) 1.0 else (1.0 - (a - HOLD_S) / EASE_S).coerceAtLeast(0.0)

  private companion object {
    /** How long a fix is carried on at full speed: the second until the next one plus its delay (it arrives some hundreds of ms after it was measured). */
    const val HOLD_S = 1.6
    /** Then slowing down to a stop over this time, when no new fix comes. */
    const val EASE_S = 1.0
  }
}
