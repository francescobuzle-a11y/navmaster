package app.navmaster.truck.ui.jv

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** A shape of the junction view on the screen: its kind and its outline (pixels). */
class ScreenPoly(val kind: Int, val xs: FloatArray, val ys: FloatArray, val n: Int)

/** (Kept for the pictures' structure: no panel hangs in the scene, the signs stay in the band on top.) */
class ScreenQuad(val q: FloatArray)

/**
 * One picture of the junction view: the horizon, the shapes of the ground, the gantry, the panels
 * hanging from it, the vehicle (its shadow, sides and top), where the camera stands.
 */
class JvFrame(
    val horizon: Float,
    val polys: List<ScreenPoly>,
    val gantry: List<ScreenPoly>,
    val signs: List<ScreenQuad>,
    val vehicle: List<ScreenPoly>,
    val camS: Double,
    val scale: Double,
)

/** What the camera remembers between two pictures: its sideways position, smoothed. */
class JvCam {
  var x = Double.NaN
  var s = Double.NaN
}

/**
 * The camera of the junction view: 13 m above the road, 26 m behind the vehicle, looking down a
 * little, the same lateral scale in every panel (the road at the vehicle takes the same share of the
 * width upright and sideways; a taller panel just sees farther ahead). It goes with the vehicle all
 * the way, from where the view opens (600 m before the junction) through the gore and into the road
 * taken, over the vehicle's own lane, sliding smoothly from one lane to the next. The direction
 * signs stay still in the band on top of the picture (they say which way to go, they are not part
 * of the scene). Pure Kotlin: the tablet turns the polygons into paths, the computer into pictures.
 */
object JvProject {
  const val CAM_H = 14.0
  const val PITCH_DEG = 19.0
  /** Metres of road to either side of the vehicle that fill the panel's width. */
  const val HALF_VIEW_M = 17.0
  const val NEAR = 2.0
  /** The camera this far behind the vehicle. */
  const val BACK = 26.0
  const val GANTRY = 30
  const val GANTRY_SHADE = 31
  const val VEH_SHADOW = 40
  const val VEH_SIDE = 41
  const val VEH_TOP = 42

  fun frame(geo: JvGeo, vehAlongAbs: Double, w: Double, h: Double, skyPx: Double, vehLane: Int?, cam: JvCam? = null): JvFrame? {
    if (w < 10 || h < 10) return null
    val gS = geo.nodeS + geo.split
    val vehS = vehAlongAbs - geo.start
    val endS = geo.nodeS + (geo.bc.lastOrNull() ?: 0.0)
    // with the vehicle all the way: never ahead of the end of the road taken
    val sCam = (vehS - BACK).coerceIn(0.0, max(0.0, endS - 60))
    val camP = geo.along(sCam)
    val a0 = geo.along(sCam - 6)
    val a1 = geo.along(sCam + 44)
    var fx = a1.x - a0.x
    var fy = a1.y - a0.y
    val fl = hypot(fx, fy).takeIf { it > 1e-6 } ?: 1.0
    fx /= fl
    fy /= fl
    val rx = fy
    val ry = -fx
    // the vehicle on the lane the satellites put it in (when known), else on the lanes to take;
    // the camera over that lane (a little towards the middle), then over the road taken; it
    // slides from one lane to the next instead of jumping
    val lane = vehLane?.takeIf { it in 0 until geo.lanesAtNode }
    fun laneR(sAt: Double): Double = -geo.laneExtra(sAt, lane)
    val camXT = laneR(sCam + BACK) * 0.6
    val camX = if (cam == null) camXT else {
      if (cam.x.isNaN() || abs(sCam - cam.s) > 200) cam.x = camXT
      else cam.x += (camXT - cam.x) * (1 - exp(-abs(sCam - cam.s) / 22.0))
      cam.s = sCam
      cam.x
    }

    // a tall panel (sideways, the panel on the right): the camera higher and looking down more,
    // a little closer, so the road fills the panel up to the signs
    val tall = ((h / w - 0.62) / 0.7).coerceIn(0.0, 1.0)
    val camH = CAM_H + 9.0 * tall
    val th = Math.toRadians(PITCH_DEG + 5.0 * tall)
    val cosT = cos(th)
    val sinT = sin(th)
    val tanT = tan(th)
    val fT = BACK
    val dT = fT * cosT + camH * sinT
    val uT = fT * sinT - camH * cosT
    val k = 1.0 + 0.35 * tall
    var f0 = (w / 2) * dT / HALF_VIEW_M * k
    var dy = f0 * (tanT - uT / dT)
    // the horizon: the vehicle sits low in the picture, the sky keeps the room asked
    val horizonMin = max(h * 0.12, skyPx + h * 0.03)
    var horizon = max(horizonMin, h * 0.78 - dy)
    if (horizon + dy > h * 0.86) {
      f0 *= (h * 0.86 - horizon) / dy
      dy = f0 * (tanT - uT / dT)
    }
    val cy = horizon + f0 * tanT

    fun depth(f: Double, z: Double) = f * cosT + (camH - z) * sinT
    fun sx(d: Double, r: Double) = (w / 2 + f0 * (r - camX) / d).toFloat()
    fun sy(d: Double, f: Double, z: Double) = (cy - f0 * (f * sinT - (camH - z) * cosT) / d).toFloat()
    /** A point of the world (metres, height [z]) on the screen, or null when behind the camera. */
    fun proj(px: Double, py: Double, z: Double): FloatArray? {
      val vx = px - camP.x
      val vy = py - camP.y
      val f = vx * fx + vy * fy
      val r = vx * rx + vy * ry
      val d = depth(f, z)
      if (d < NEAR) return null
      return floatArrayOf(sx(d, r), sy(d, f, z))
    }
    fun quad(kind: Int, vararg p: FloatArray?): ScreenPoly? {
      if (p.any { it == null }) return null
      return ScreenPoly(kind, FloatArray(p.size) { p[it]!![0] }, FloatArray(p.size) { p[it]!![1] }, p.size)
    }

    val out = ArrayList<ScreenPoly>()
    val fs = DoubleArray(4096)
    val rs = DoubleArray(4096)
    val ds = DoubleArray(4096)
    for (sh in geo.drawOrder) {
      val n = sh.pts.size
      if (n < 2 || n > 4000) continue
      val bnd = sh.bound
      val bx = bnd[0] - camP.x
      val by = bnd[1] - camP.y
      if (hypot(bx, by) - bnd[2] > 650 || bx * fx + by * fy < -bnd[2] - 40) continue
      var anyIn = false
      for (i in 0 until n) {
        val vx = sh.pts[i].x - camP.x
        val vy = sh.pts[i].y - camP.y
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

    val gantry = ArrayList<ScreenPoly>()
    val quads = ArrayList<ScreenQuad>()
    // the vehicle: a solid arrow on its lane, where it really is (shadow, sides, top)
    val veh = ArrayList<ScreenPoly>()
    if (vehS > 0 && vehS < endS) {
      val vp = geo.along(vehS)
      val vq = geo.along(vehS + 6)
      var dx = vq.x - vp.x
      var dyy = vq.y - vp.y
      val dl = hypot(dx, dyy).takeIf { it > 1e-6 } ?: 1.0
      dx /= dl
      dyy /= dl
      // sideways: the lane's offset is left-positive; the arrow's own frame is forward/right
      val lr = laneR(vehS)
      val rxv = dyy
      val ryv = -dx
      fun at(fwd: Double, right: Double) = doubleArrayOf(vp.x + dx * fwd + rxv * (right + lr), vp.y + dyy * fwd + ryv * (right + lr))
      val top = 0.9
      val shape = listOf(at(3.0, 0.0), at(-2.5, 1.6), at(-1.3, 0.0), at(-2.5, -1.6))
      val shadow = shape.map { doubleArrayOf(it[0] + rxv * 0.35 - dx * 0.2, it[1] + ryv * 0.35 - dyy * 0.2) }
      quad(VEH_SHADOW, *shadow.map { proj(it[0], it[1], 0.0) }.toTypedArray())?.let { veh += it }
      // the sides, the farther ones first
      val sides = ArrayList<Pair<Double, ScreenPoly>>()
      for (i in shape.indices) {
        val a = shape[i]
        val b = shape[(i + 1) % shape.size]
        val q = quad(VEH_SIDE, proj(a[0], a[1], 0.0), proj(b[0], b[1], 0.0), proj(b[0], b[1], top), proj(a[0], a[1], top)) ?: continue
        val mx = (a[0] + b[0]) / 2 - camP.x
        val my = (a[1] + b[1]) / 2 - camP.y
        sides += (mx * fx + my * fy) to q
      }
      sides.sortByDescending { it.first }
      veh += sides.map { it.second }
      quad(VEH_TOP, *shape.map { proj(it[0], it[1], top) }.toTypedArray())?.let { veh += it }
    }
    return JvFrame(horizon.toFloat(), out, gantry, quads, veh, sCam, f0)
  }
}
