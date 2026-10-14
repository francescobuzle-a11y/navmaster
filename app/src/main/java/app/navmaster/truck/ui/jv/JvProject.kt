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

/** A panel of the gantry on the screen: its four corners (top-left, top-right, bottom-right, bottom-left; pixels). */
class ScreenQuad(val spec: SignSpec, val q: FloatArray)

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
 * taken, over the vehicle's own lane, sliding smoothly from one lane to the next. The gantry with
 * the direction signs stands over the road ahead of the vehicle, at the gore when the vehicle is
 * near, closer otherwise (so that the signs can be read from afar), and the vehicle drives under
 * it. Pure Kotlin: the tablet turns the polygons into paths, the computer into pictures.
 */
object JvProject {
  const val CAM_H = 14.0
  const val PITCH_DEG = 19.0
  /** Metres of road to either side of the vehicle that fill the panel's width. */
  const val HALF_VIEW_M = 17.0
  const val NEAR = 2.0
  /** The camera this far behind the vehicle. */
  const val BACK = 26.0
  /** The gantry at most this far ahead of the vehicle (nearer than that it stands at the gore). */
  const val SIGN_AHEAD = 18.0
  /** Metres before the nose of the gore where the gantry really stands. */
  const val SIGN_BEFORE_GORE = 14.0
  /** The bottom of the panels above the road, and the height of the beam. */
  const val SIGN_Z = 5.4
  const val BEAM_H = 0.7

  const val GANTRY = 30
  const val GANTRY_SHADE = 31
  const val VEH_SHADOW = 40
  const val VEH_SIDE = 41
  const val VEH_TOP = 42

  fun frame(geo: JvGeo, vehAlongAbs: Double, w: Double, h: Double, skyPx: Double, vehLane: Int?,
            signs: List<SignSpec> = emptyList(), cam: JvCam? = null): JvFrame? {
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
    val horizonMin = max(h * 0.20, skyPx)
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

    // the gantry and its panels: over the road ahead of the vehicle, at the gore at the last
    val gantry = ArrayList<ScreenPoly>()
    val quads = ArrayList<ScreenQuad>()
    if (signs.isNotEmpty() && geo.split > 0) {
      val sG = min(vehS + SIGN_AHEAD, gS - SIGN_BEFORE_GORE).coerceAtLeast(10.0)
      val gp = geo.along(sG)
      val g0 = geo.along(sG - 4)
      val g1 = geo.along(sG + 4)
      var tx = g1.x - g0.x
      var ty = g1.y - g0.y
      val tl = hypot(tx, ty).takeIf { it > 1e-6 } ?: 1.0
      tx /= tl
      ty /= tl
      val nx = -ty
      val ny = tx
      fun world(off: Double, ahead: Double = 0.0) = doubleArrayOf(gp.x + nx * off + tx * ahead, gp.y + ny * off + ty * ahead)
      val kk = geo.profileIndex(min(sG, geo.nodeS))
      val left = geo.profile.left[kk]
      val right = geo.profile.right[kk]
      val zTop = SIGN_Z + (signs.maxOfOrNull { it.heightM } ?: 4.6)
      val postW = 0.55
      for (off in listOf(left + 1.6, right - 1.6)) {
        val a = world(off - postW / 2)
        val b = world(off + postW / 2)
        val c = world(off + postW / 2, -postW)
        quad(GANTRY, proj(a[0], a[1], 0.0), proj(b[0], b[1], 0.0), proj(b[0], b[1], zTop + BEAM_H), proj(a[0], a[1], zTop + BEAM_H))?.let { gantry += it }
        quad(GANTRY_SHADE, proj(b[0], b[1], 0.0), proj(c[0], c[1], 0.0), proj(c[0], c[1], zTop + BEAM_H), proj(b[0], b[1], zTop + BEAM_H))?.let { gantry += it }
      }
      val la = world(left + 1.6)
      val ra = world(right - 1.6)
      quad(GANTRY, proj(la[0], la[1], zTop), proj(ra[0], ra[1], zTop), proj(ra[0], ra[1], zTop + BEAM_H), proj(la[0], la[1], zTop + BEAM_H))?.let { gantry += it }
      // the panels: each over the middle of its lanes at the gantry, as wide as they are
      val sLane = min(sG, geo.nodeS - 3)
      val placed = ArrayList<DoubleArray>()
      for (s in signs) {
        var c = (s.laneFrom..s.laneTo).map { geo.laneOffAt(sLane, it) }.average()
        var l0 = c + s.widthM / 2
        var r0 = c - s.widthM / 2
        // not over another panel (the lanes of a narrower road), not beyond the posts
        for (p in placed) {
          if (r0 < p[0] && l0 > p[1]) { if (abs(c - (p[0] + p[1]) / 2) < 0.01 || c > (p[0] + p[1]) / 2) { r0 = p[0] + 0.25; l0 = r0 + s.widthM } else { l0 = p[1] - 0.25; r0 = l0 - s.widthM } }
        }
        placed += doubleArrayOf(l0, r0)
        val z0 = SIGN_Z
        val z1 = SIGN_Z + s.heightM
        val pl = world(l0, 0.3)
        val pr = world(r0, 0.3)
        val tl0 = proj(pl[0], pl[1], z1)
        val tr0 = proj(pr[0], pr[1], z1)
        val br0 = proj(pr[0], pr[1], z0)
        val bl0 = proj(pl[0], pl[1], z0)
        if (tl0 == null || tr0 == null || br0 == null || bl0 == null) continue
        quads += ScreenQuad(s, floatArrayOf(tl0[0], tl0[1], tr0[0], tr0[1], br0[0], br0[1], bl0[0], bl0[1]))
      }
    }

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
