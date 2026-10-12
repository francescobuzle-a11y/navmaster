package app.navmaster.truck.ui.jv

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** A shape of the junction view on the screen: its kind and its outline (pixels). */
class ScreenPoly(val kind: Int, val xs: FloatArray, val ys: FloatArray, val n: Int)

/** One picture of the junction view: the horizon, the shapes and the vehicle's arrow (4 points, or null). */
class JvFrame(val horizon: Float, val polys: List<ScreenPoly>, val vehicle: FloatArray?, val camS: Double, val scale: Double)

/**
 * The camera of the junction view: 14 m above the road, 30 m behind the vehicle, looking down a
 * little, the same lateral scale in every panel (the road at the vehicle takes the same share of the
 * width upright and sideways; a taller panel just sees farther ahead). It waits about 190 m before
 * the gore, so the whole junction is in view, then goes with the vehicle through it and into the
 * road taken. Pure Kotlin: the tablet turns the polygons into paths, the computer into pictures.
 */
object JvProject {
  /** Camera height and pitch for a wide panel; a tall panel has a higher camera looking down more (farther ahead in view). */
  const val CAM_H = 14.0
  const val PITCH_DEG = 19.0
  /** Metres of road to either side of the vehicle that fill the panel's width. */
  const val HALF_VIEW_M = 17.0
  const val NEAR = 2.0

  fun frame(geo: JvGeo, vehAlongAbs: Double, w: Double, h: Double, signsPx: Double, vehLane: Int?): JvFrame? {
    if (w < 10 || h < 10) return null
    val gS = geo.nodeS + geo.split
    val vehS = vehAlongAbs - geo.start
    val endS = geo.nodeS + (geo.bc.lastOrNull() ?: 0.0) - 80
    // waits before the junction, then follows the vehicle (30 m behind it)
    val sCam = max(gS - 190, vehS - 30).coerceAtMost(max(endS, gS - 190))
    val cam = geo.along(sCam)
    val a0 = geo.along(sCam - 10)
    val a1 = geo.along(sCam + 60)
    var fx = a1.x - a0.x
    var fy = a1.y - a0.y
    val fl = hypot(fx, fy).takeIf { it > 1e-6 } ?: 1.0
    fx /= fl
    fy /= fl
    val rx = fy
    val ry = -fx
    // the vehicle on the lane the satellites put it in (when known), else on the lanes to take;
    // the camera over that lane, then over the road taken
    val lane = vehLane?.takeIf { it in 0 until geo.lanesAtNode }
    fun laneR(sAt: Double): Double = -geo.laneExtra(sAt, lane)
    val camX = laneR(sCam + 30) * 0.5

    // a tall panel (sideways, the panel on the right): the camera higher and looking down more,
    // a little closer, so the road fills the panel up to the signs
    val tall = ((h / w - 0.62) / 0.7).coerceIn(0.0, 1.0)
    val camH = CAM_H + 10.0 * tall
    val th = Math.toRadians(PITCH_DEG + 5.0 * tall)
    val cosT = kotlin.math.cos(th)
    val sinT = sin(th)
    val tanT = tan(th)
    val fT = 30.0
    val dT = fT * cosT + camH * sinT
    val uT = fT * sinT - camH * cosT
    val k = 1.0 + 0.35 * tall
    var f0 = (w / 2) * dT / HALF_VIEW_M * k
    var dy = f0 * (tanT - uT / dT)
    // the horizon: the vehicle sits low in the picture, the signs stay above the far road
    val horizonMin = max(h * 0.12, signsPx + h * 0.03)
    var horizon = max(horizonMin, h * 0.86 - dy)
    if (horizon + dy > h * 0.95) {
      f0 *= (h * 0.95 - horizon) / dy
      dy = f0 * (tanT - uT / dT)
    }
    val cy = horizon + f0 * tanT

    fun depth(f: Double, z: Double) = f * cosT + (camH - z) * sinT
    fun sx(d: Double, r: Double) = (w / 2 + f0 * (r - camX) / d).toFloat()
    fun sy(d: Double, f: Double, z: Double) = (cy - f0 * (f * sinT - (camH - z) * cosT) / d).toFloat()

    val out = ArrayList<ScreenPoly>()
    val fs = DoubleArray(4096)
    val rs = DoubleArray(4096)
    val ds = DoubleArray(4096)
    for (sh in geo.drawOrder) {
      val n = sh.pts.size
      if (n < 2 || n > 4000) continue
      val bnd = sh.bound
      val bx = bnd[0] - cam.x
      val by = bnd[1] - cam.y
      if (hypot(bx, by) - bnd[2] > 650 || bx * fx + by * fy < -bnd[2] - 40) continue
      var anyIn = false
      for (i in 0 until n) {
        val vx = sh.pts[i].x - cam.x
        val vy = sh.pts[i].y - cam.y
        fs[i] = vx * fx + vy * fy
        rs[i] = vx * rx + vy * ry
        ds[i] = depth(fs[i], 0.0)
        if (ds[i] >= NEAR) anyIn = true
      }
      if (!anyIn) continue
      val xs = FloatArray(2 * n + 4)
      val ys = FloatArray(2 * n + 4)
      var m = 0
      if (sh.kind == JvGeo.RAIL) {
        val idx = (0 until n).filter { depth(fs[it], sh.z1) >= NEAR && depth(fs[it], sh.z0) >= NEAR }
        if (idx.size < 2) continue
        for (i in idx) { val d = depth(fs[i], sh.z1); xs[m] = sx(d, rs[i]); ys[m] = sy(d, fs[i], sh.z1); m++ }
        for (i in idx.reversed()) { val d = depth(fs[i], sh.z0); xs[m] = sx(d, rs[i]); ys[m] = sy(d, fs[i], sh.z0); m++ }
      } else {
        for (i in 0 until n) {
          val j = (i + 1) % n
          val ina = ds[i] >= NEAR
          val inb = ds[j] >= NEAR
          if (ina) { xs[m] = sx(ds[i], rs[i]); ys[m] = sy(ds[i], fs[i], 0.0); m++ }
          if (ina != inb) {
            val t = (NEAR - ds[i]) / (ds[j] - ds[i])
            val r = rs[i] + (rs[j] - rs[i]) * t
            val f = fs[i] + (fs[j] - fs[i]) * t
            xs[m] = sx(NEAR, r); ys[m] = sy(NEAR, f, 0.0); m++
          }
        }
      }
      if (m < 3) continue
      out += ScreenPoly(sh.kind, xs, ys, m)
    }
    // the vehicle: an arrow on its lane, where it really is
    var veh: FloatArray? = null
    if (vehS > 0 && vehS < endS + 80) {
      val vp = geo.along(vehS)
      val vq = geo.along(vehS + 6)
      val lr = laneR(vehS)
      fun toScreen(px: Double, py: Double, r0: Double): FloatArray? {
        val vx = px - cam.x
        val vy = py - cam.y
        val f = vx * fx + vy * fy
        val r = vx * rx + vy * ry + r0
        val d = depth(f, 0.0)
        if (d < NEAR) return null
        return floatArrayOf(sx(d, r), sy(d, f, 0.0))
      }
      var dx = vq.x - vp.x
      var dyy = vq.y - vp.y
      val dl = hypot(dx, dyy).takeIf { it > 1e-6 } ?: 1.0
      dx /= dl
      dyy /= dl
      val nx = dyy
      val ny = -dx
      val len = 5.5
      val half = 1.4
      val tip = toScreen(vp.x + dx * len / 2, vp.y + dyy * len / 2, lr)
      val left = toScreen(vp.x - dx * len / 2 - nx * half, vp.y - dyy * len / 2 - ny * half, lr)
      val back = toScreen(vp.x - dx * len * 0.22, vp.y - dyy * len * 0.22, lr)
      val right = toScreen(vp.x - dx * len / 2 + nx * half, vp.y - dyy * len / 2 + ny * half, lr)
      if (tip != null && left != null && back != null && right != null) veh = floatArrayOf(tip[0], tip[1], left[0], left[1], back[0], back[1], right[0], right[1])
    }
    return JvFrame(horizon.toFloat(), out, veh, sCam, f0)
  }
}
