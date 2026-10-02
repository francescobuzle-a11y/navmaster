package app.navmaster.truck.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.RoadTiles
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** The real junction for a scene of the guidance: the route around it and the roads of the offline map. */
internal fun realJunctionOf(scene: JunctionScene, md: JvModel): RealJv? {
  val a = scene.analysis ?: return null
  // roundabouts keep the drawing made for them
  if (scene.roundabout) return null
  val m = scene.maneuverAtM
  val start = (m - 600).coerceAtLeast(0.0)
  val end = (m + 500).coerceAtMost(a.length)
  if (m - start < 80 || end - m < 60) return null
  val o = a.pointAt(m)
  val file = AppGraph.regions.regionAt(o.lat, o.lng)?.map ?: return null
  if (!file.exists()) return null
  val routeLL = (0..((end - start) / 3.0).toInt()).map { i -> a.pointAt(start + i * 3.0).let { doubleArrayOf(it.lat, it.lng) } }
  val lines = RoadTiles.around(file, o.lat, o.lng, 800.0)
  return RealJv.assemble(routeLL, start, m, lines, md, scene.motorway, scene.exit, scene.country)
}

/**
 * Draws [rj] as the driver needs it: the road seen from above and behind the vehicle (a camera 20 m
 * high, looking down at 21°), with its real lanes, the road going on ahead and the lanes to take
 * painted violet with the arrow into the road to take.
 *
 * The camera waits about 190 m before the gore (the whole junction in view) and from there goes
 * with the vehicle, lane by lane, through the junction and into the road taken; the vehicle is the
 * blue arrow on its lane. [vehAlong]: where the vehicle is (metres of the route).
 */
internal fun DrawScope.drawRealJunction(rj: RealJv, vehAlong: Double, near: Boolean, night: Boolean, signsSpace: Float) {
  val w = size.width.toDouble()
  val h = size.height.toDouble()
  if (w < 10 || h < 10) return
  val gS = rj.nodeS + rj.split
  val vehS = vehAlong - rj.start
  val endS = rj.nodeS + (rj.bc.lastOrNull() ?: 0.0) - 80
  // waits before the junction, then follows the vehicle (30 m behind it)
  val sCam = max(gS - 190, vehS - 30).coerceAtMost(max(endS, gS - 190))
  val cam = rj.along(sCam)
  val a0 = rj.along(sCam - 10)
  val a1 = rj.along(sCam + 60)
  var fx = a1.x - a0.x
  var fy = a1.y - a0.y
  val fl = hypot(fx, fy).takeIf { it > 1e-6 } ?: 1.0
  fx /= fl
  fy /= fl
  val rx = fy
  val ry = -fx
  // the camera over the lane of the vehicle, then over the middle of the road taken
  fun laneR(sAt: Double): Double = if (sAt <= rj.nodeS) -rj.actOff else -rj.actOff * max(0.0, 1 - (sAt - rj.nodeS) / 25.0)
  val camX = laneR(sCam + 30) * 0.5

  // the camera: height, looking down, and the scale that frames the road at the vehicle
  val camH = 20.0
  val th = Math.toRadians(21.0)
  val cosT = kotlin.math.cos(th)
  val sinT = sin(th)
  val tanT = tan(th)
  val horizon = max(h * 0.12, signsSpace + h * 0.03)
  val fT = 30.0
  val dT = fT * cosT + camH * sinT
  val uT = fT * sinT - camH * cosT
  val fVert = (h * 0.93 - horizon) / (tanT - uT / dT)
  val fHor = (w / 2) * dT / 20.0
  val f0 = fVert.coerceIn(fHor * 0.75, fHor * 1.5)
  val cy = horizon + f0 * tanT
  val nearD = 2.0

  // sky, hills, land
  val hz = horizon.toFloat()
  val skyTop = if (night) Color(0xFF0A1322) else Color(0xFF3E86D8)
  val skyLow = if (night) Color(0xFF22324A) else Color(0xFFD6E8F8)
  drawRect(Brush.verticalGradient(listOf(skyTop, skyLow), 0f, hz), size = Size(size.width, hz))
  if (!night && hz > h * 0.10) {
    for ((cxr, cyr, rr) in listOf(Triple(0.16, 0.40, 0.07), Triple(0.55, 0.25, 0.09), Triple(0.84, 0.45, 0.06))) {
      val x0 = (w * cxr).toFloat()
      val y0 = (horizon * cyr).toFloat()
      val r = (w * rr).toFloat()
      drawOval(Color(0xE6FFFFFF), Offset(x0 - r, y0 - r * 0.35f), Size(r * 2, r * 0.7f))
      drawOval(Color(0xE6FFFFFF), Offset(x0 - r * 0.55f, y0 - r * 0.6f), Size(r * 1.1f, r * 0.8f))
    }
  }
  // the far hills: the same for every frame of a panel size (one drawing kept, not rebuilt)
  val hillKey = "${size.width.toInt()}x${size.height.toInt()}:${hz.toInt()}"
  val hills = (rj.hills?.takeIf { rj.hillsKey == hillKey } ?: Path().apply {
    moveTo(0f, hz)
    for (i in 0..100) lineTo((w * i / 100).toFloat(), (horizon - h * (0.012 + 0.007 * sin(i * 0.21) + 0.005 * sin(i * 0.57 + 1.3))).toFloat())
    lineTo(size.width, hz)
    close()
  }.also { rj.hills = it; rj.hillsKey = hillKey })
  drawPath(hills, if (night) Color(0xFF1C2A1F) else Color(0xFF9CB99A))
  val grassFar = if (night) Color(0xFF22321E) else Color(0xFF8CC063)
  val grassNear = if (night) Color(0xFF152014) else Color(0xFF4F9136)
  drawRect(Brush.verticalGradient(listOf(grassFar, grassNear), hz, size.height), Offset(0f, hz), Size(size.width, size.height - hz))

  // a point of the ground (f ahead, r to the right, z up, in metres from the camera) on the screen
  fun depth(f: Double, z: Double) = f * cosT + (camH - z) * sinT
  fun sx(d: Double, r: Double) = (w / 2 + f0 * (r - camX) / d).toFloat()
  fun sy(d: Double, f: Double, z: Double) = (cy - f0 * (f * sinT - (camH - z) * cosT) / d).toFloat()

  val key = "${(sCam * 10).toLong()}:${size.width.toInt()}x${size.height.toInt()}:${signsSpace.toInt()}"
  @Suppress("UNCHECKED_CAST")
  var cached = rj.cache as? List<Pair<Path, Int>>
  if (key != rj.cacheKey || cached == null) {
    // one drawing for all the shapes of a kind in a layer (a few drawings instead of a thousand)
    val out = ArrayList<Pair<Path, Int>>()
    var curPath: Path? = null
    var curKey = -1
    val fs = DoubleArray(4096)
    val rs = DoubleArray(4096)
    val ds = DoubleArray(4096)
    val xs = FloatArray(8200)
    val ys = FloatArray(8200)
    for (sh in rj.drawOrder) {
      val n = sh.pts.size
      if (n < 2 || n > 4000) continue
      // far away or behind the camera as a whole: not even looked at
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
        if (ds[i] >= nearD) anyIn = true
      }
      if (!anyIn) continue
      var m = 0
      if (sh.kind == RealJv.RAIL) {
        // a band between two heights along the line (points behind the camera left out)
        val idx = (0 until n).filter { depth(fs[it], sh.z1) >= nearD && depth(fs[it], sh.z0) >= nearD }
        if (idx.size < 2) continue
        for (i in idx) { val d = depth(fs[i], sh.z1); xs[m] = sx(d, rs[i]); ys[m] = sy(d, fs[i], sh.z1); m++ }
        for (i in idx.reversed()) { val d = depth(fs[i], sh.z0); xs[m] = sx(d, rs[i]); ys[m] = sy(d, fs[i], sh.z0); m++ }
      } else {
        // a polygon on the ground, cut where it comes too close to the camera
        for (i in 0 until n) {
          val j = (i + 1) % n
          val ina = ds[i] >= nearD
          val inb = ds[j] >= nearD
          if (ina) { xs[m] = sx(ds[i], rs[i]); ys[m] = sy(ds[i], fs[i], 0.0); m++ }
          if (ina != inb) {
            val t = (nearD - ds[i]) / (ds[j] - ds[i])
            val r = rs[i] + (rs[j] - rs[i]) * t
            val f = fs[i] + (fs[j] - fs[i]) * t
            xs[m] = sx(nearD, r); ys[m] = sy(nearD, f, 0.0); m++
          }
          if (m >= 8190) break
        }
      }
      if (m < 3) continue
      val groupKey = sh.layer * 16 + sh.kind
      if (groupKey != curKey || curPath == null) {
        curPath = Path()
        curKey = groupKey
        out += curPath to sh.kind
      }
      // all the outlines of a drawing turn the same way, so that two overlapping shapes add up
      // (with opposite turns the overlap would be left empty)
      var area = 0.0
      for (i in 0 until m) {
        val j = if (i + 1 == m) 0 else i + 1
        area += xs[i].toDouble() * ys[j] - xs[j].toDouble() * ys[i]
      }
      val p: Path = curPath ?: continue
      if (area >= 0) {
        p.moveTo(xs[0], ys[0])
        for (i in 1 until m) p.lineTo(xs[i], ys[i])
      } else {
        p.moveTo(xs[m - 1], ys[m - 1])
        for (i in m - 2 downTo 0) p.lineTo(xs[i], ys[i])
      }
      p.close()
    }
    rj.cache = out
    rj.cacheKey = key
    cached = out
  }
  val shoulder = if (night) Color(0xFF1E2023) else Color(0xFF2E3034)
  val asphalt = if (night) Color(0xFF26282C) else Color(0xFF3B3E43)
  val mark = if (night) Color(0xFFD2D5D9) else Color.White
  val rail = if (night) Color(0xFF69707A) else Color(0xFFC7CCD2)
  val arrow = if (night) Color(0xFF8246DA) else Color(0xFF9148EE)
  val arrowLight = if (night) Color(0xFFB891F2) else Color(0xFFD2B4FF)
  for ((p, kind) in cached.orEmpty()) {
    when (kind) {
      RealJv.SHOULDER -> drawPath(p, shoulder)
      RealJv.ASPHALT -> drawPath(p, asphalt)
      RealJv.MARK -> drawPath(p, mark)
      RealJv.RAIL -> drawPath(p, rail)
      RealJv.ARROW_SHADOW -> translate(0f, (h * 0.008).toFloat()) { drawPath(p, Color(0x66000000)) }
      RealJv.ARROW -> {
        drawPath(p, Brush.verticalGradient(listOf(arrowLight, arrow), hz, size.height))
        drawPath(p, arrowLight, style = Stroke((w * 0.004).toFloat()))
      }
    }
  }
  // the vehicle: a blue arrow on its lane, where it really is (it moves at every frame)
  if (vehS > 0 && vehS < endS + 80) {
    val vp = rj.along(vehS)
    val vq = rj.along(vehS + 6)
    val lr = laneR(vehS)
    fun toScreen(px: Double, py: Double, r0: Double, ahead: Double): Offset? {
      val vx = px - cam.x
      val vy = py - cam.y
      val f = vx * fx + vy * fy + ahead
      val r = vx * rx + vy * ry + r0
      val d = depth(f, 0.0)
      if (d < nearD) return null
      return Offset(sx(d, r), sy(d, f, 0.0))
    }
    // the arrow points the way the road goes there
    var dx = vq.x - vp.x
    var dy = vq.y - vp.y
    val dl = hypot(dx, dy).takeIf { it > 1e-6 } ?: 1.0
    dx /= dl
    dy /= dl
    val nx = dy
    val ny = -dx
    val len = 5.5
    val half = 1.4
    val tip = toScreen(vp.x + dx * len / 2, vp.y + dy * len / 2, lr, 0.0)
    val left = toScreen(vp.x - dx * len / 2 - nx * half, vp.y - dy * len / 2 - ny * half, lr, 0.0)
    val back = toScreen(vp.x - dx * len * 0.22, vp.y - dy * len * 0.22, lr, 0.0)
    val right = toScreen(vp.x - dx * len / 2 + nx * half, vp.y - dy * len / 2 + ny * half, lr, 0.0)
    if (tip != null && left != null && back != null && right != null) {
      val car = Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(back.x, back.y); lineTo(right.x, right.y); close() }
      drawPath(car, Color(0xFF1E88E5))
      drawPath(car, Color.White, style = Stroke((w * 0.005).toFloat().coerceAtLeast(2f)))
    }
  }
}
