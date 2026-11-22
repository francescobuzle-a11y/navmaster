package app.navmaster.truck.ui

import app.navmaster.truck.core.Geo
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The arrow off the route and with no route (FreeTrack): fed one satellite fix a second, drawn at
 * 60 frames a second, it must glide (no frame moves it much more than the vehicle moves in that
 * frame, never backwards), stay on the vehicle (a few metres), turn smoothly, and stand still when
 * the vehicle does. Before, the arrow was drawn at each fix: 20-25 m hops once a second. Pure Kotlin.
 */
class FreeTrackTest {
  private val lat0 = 45.15
  private val lon0 = 10.38
  private val mLat = 1.0 / 110_540.0
  private val mLon = 1.0 / (111_320.0 * cos(Math.toRadians(lat0)))

  /** The vehicle's true position and course at [t] seconds. */
  private class Truth(val x: Double, val y: Double, val course: Double, val speed: Double)

  private class Run(val maxStepOverSpeed: Double, val maxBackStep: Double, val meanErr: Double, val maxErr: Double, val maxTurnPerFrame: Double)

  /**
   * Drives [seconds] along [path] (east metres x, north metres y), fixes every second with
   * [noiseM] of noise, measured [lagS] before they arrive; frames every 1/60 s.
   */
  private fun drive(seconds: Double, noiseM: Double, lagS: Double, skip: (Int) -> Boolean = { false }, noSpeed: Boolean = false, path: (Double) -> Truth): Run {
    val ft = FreeTrack()
    val rnd = java.util.Random(11)
    val frame = 1.0 / 60
    var nextFix = 0.0
    var prevX = Double.NaN
    var prevY = 0.0
    var prevB = Double.NaN
    var maxStepOverSpeed = 0.0
    var maxBack = 0.0
    var errSum = 0.0
    var errMax = 0.0
    var n = 0
    var maxTurn = 0.0
    var t = 0.0
    val ns = { s: Double -> (s * 1e9).toLong() + 1_000_000_000L }
    while (t < seconds) {
      if (t >= nextFix + lagS) {
        val f = path(nextFix)
        if (!skip(nextFix.toInt())) ft.feed(lat0 + (f.y + rnd.nextGaussian() * noiseM) * mLat, lon0 + (f.x + rnd.nextGaussian() * noiseM) * mLon,
            if (noSpeed) Double.NaN else f.speed, if (noSpeed) Double.NaN else f.course, 4.0, ns(nextFix))
        nextFix += 1.0
      }
      val u = ft.step(ns(t))
      if (u != null) {
        val x = (u.coordinates.lng - lon0) / mLon
        val y = (u.coordinates.lat - lat0) / mLat
        val truth = path(t)
        if (!prevX.isNaN() && t > 3.0) {
          val dx = x - prevX
          val dy = y - prevY
          val step = Math.hypot(dx, dy)
          maxStepOverSpeed = maxOf(maxStepOverSpeed, step - truth.speed * frame)
          // along the vehicle's course: never backwards
          val along = dx * sin(Math.toRadians(truth.course)) + dy * cos(Math.toRadians(truth.course))
          if (truth.speed > 1) maxBack = maxOf(maxBack, -along)
          val err = Math.hypot(x - truth.x, y - truth.y)
          errSum += err
          errMax = maxOf(errMax, err)
          n++
          val b = u.courseOverGround?.degrees?.toDouble()
          if (b != null && !prevB.isNaN()) maxTurn = maxOf(maxTurn, abs(Geo.angleDiff(prevB, b)))
        }
        prevX = x
        prevY = y
        prevB = u.courseOverGround?.degrees?.toDouble() ?: Double.NaN
      }
      t += frame
    }
    return Run(maxStepOverSpeed, maxBack, errSum / n, errMax, maxTurn)
  }

  @Test
  fun motorwayAt90KmhGlides() {
    val v = 25.0
    val r = drive(40.0, 2.0, 0.3) { t -> Truth(v * t, 0.0, 90.0, v) }
    // a frame moves the arrow at most 0.25 m more than the vehicle (0.42 m a frame at 90 km/h);
    // drawn at each fix it moved 25 m in one frame
    assertTrue("step ${r.maxStepOverSpeed}", r.maxStepOverSpeed < 0.25)
    assertTrue("backwards ${r.maxBackStep}", r.maxBackStep < 0.05)
    assertTrue("mean error ${r.meanErr}", r.meanErr < 3.0)
    assertTrue("max error ${r.maxErr}", r.maxErr < 7.0)
  }

  @Test
  fun curveInTownGlidesAndTurnsSmoothly() {
    // a bend of 120 m radius at 50 km/h
    val v = 14.0
    val radius = 120.0
    val r = drive(40.0, 2.0, 0.3) { t ->
      val a = v * t / radius
      Truth(radius * sin(a), radius * (1 - cos(a)) * -1, (90.0 + Math.toDegrees(a)) % 360.0, v)
    }
    assertTrue("step ${r.maxStepOverSpeed}", r.maxStepOverSpeed < 0.25)
    assertTrue("mean error ${r.meanErr}", r.meanErr < 3.5)
    // the course turns by degrees, never by the 7 degrees a second the vehicle turns, all at once
    assertTrue("turn per frame ${r.maxTurnPerFrame}", r.maxTurnPerFrame <= 2.0)
  }

  @Test
  fun standingStillDoesNotTwitch() {
    val ft = FreeTrack()
    val rnd = java.util.Random(3)
    val ns = { s: Double -> (s * 1e9).toLong() + 1_000_000_000L }
    var t = 0.0
    var first: Pair<Double, Double>? = null
    var maxMove = 0.0
    var nextFix = 0.0
    while (t < 30.0) {
      if (t >= nextFix) {
        // a lorry at a red light: fixes wandering 4 m around it, speed 0
        ft.feed(lat0 + rnd.nextGaussian() * 4.0 * mLat, lon0 + rnd.nextGaussian() * 4.0 * mLon, 0.0, Double.NaN, 6.0, ns(nextFix))
        nextFix += 1.0
      }
      val u = ft.step(ns(t))
      if (u != null && t > 1.5) {
        val p = u.coordinates.lat to u.coordinates.lng
        val f = first ?: p.also { first = it }
        maxMove = maxOf(maxMove, Geo.dist(f.first, f.second, p.first, p.second))
      }
      t += 1.0 / 60
    }
    assertTrue("moved $maxMove m standing still", maxMove < 1.0)
  }

  @Test
  fun takesOverFromTheArrowShownWithoutAJump() {
    val ft = FreeTrack()
    val ns = { s: Double -> (s * 1e9).toLong() + 1_000_000_000L }
    // the route's arrow was 6 m behind the first off-route fix
    ft.seed(uniffi.ferrostar.UserLocation(uniffi.ferrostar.GeographicCoordinate(lat0, lon0 - 6 * mLon), 4.0,
        uniffi.ferrostar.CourseOverGround(90u.toUShort(), 5u.toUShort()), java.time.Instant.now(), null))
    ft.feed(lat0, lon0, 20.0, 90.0, 4.0, ns(0.0))
    val a = ft.step(ns(0.0))!!
    val b = ft.step(ns(1.0 / 60))!!
    // the first frame is where the old arrow was, the next one a small step on
    assertTrue(Geo.dist(a.coordinates.lat, a.coordinates.lng, lat0, lon0 - 6 * mLon) < 0.5)
    assertTrue(Geo.dist(a.coordinates.lat, a.coordinates.lng, b.coordinates.lat, b.coordinates.lng) < 1.0)
  }

  @Test
  fun aSkippedFixSlowsTheArrowWithoutAStopOrAJump() {
    // the receiver skips one fix in eight (under a bridge): no hard stop, no surge, never backwards
    val v = 25.0
    val r = drive(60.0, 2.0, 0.3, skip = { it % 8 == 5 }) { t -> Truth(v * t, 0.0, 90.0, v) }
    assertTrue("step ${r.maxStepOverSpeed}", r.maxStepOverSpeed < 0.3)
    assertTrue("backwards ${r.maxBackStep}", r.maxBackStep < 0.05)
    assertTrue("max error ${r.maxErr}", r.maxErr < 15.0)
  }

  @Test
  fun aReceiverWithoutSpeedStillGlides() {
    // speed and course worked out from the fixes themselves
    val v = 20.0
    val r = drive(40.0, 1.5, 0.3, noSpeed = true) { t -> Truth(v * t, 0.0, 90.0, v) }
    assertTrue("step ${r.maxStepOverSpeed}", r.maxStepOverSpeed < 0.4)
    assertTrue("mean error ${r.meanErr}", r.meanErr < 4.0)
  }
}
