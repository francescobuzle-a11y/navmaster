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
 * Draws [rj]: the vehicle is at [vehAlong] metres of the route; [near]: the junction view (camera
 * a little before the gore), else the lane guidance from farther (a higher camera).
 */
internal fun DrawScope.drawRealJunction(rj: RealJv, vehAlong: Double, near: Boolean, night: Boolean, signsSpace: Float) {
  val w = size.width.toDouble()
  val h = size.height.toDouble()
  if (w < 10 || h < 10) return
  val gS = rj.nodeS + rj.split
  val vehS = vehAlong - rj.start
  val back = if (near) 40.0 else 420.0
  val camH = if (near) 8.0 else 16.0
  val kz = if (near) 0.6 else 0.32
  val sCam = if (near) max(gS - back, min(vehS, gS - 15)) else max(gS - back, min(vehS, gS - 120))
  val cam = rj.along(sCam)
  var fx: Double
  var fy: Double
  if (sCam >= rj.nodeS - 40) {
    val a0 = rj.along(rj.nodeS - 40)
    fx = rj.node.x - a0.x
    fy = rj.node.y - a0.y
  } else {
    val a0 = rj.along(sCam - 15)
    val a1 = rj.along(sCam + 25)
    fx = a1.x - a0.x
    fy = a1.y - a0.y
  }
  val fl = hypot(fx, fy).takeIf { it > 1e-6 } ?: 1.0
  fx /= fl
  fy /= fl
  val rx = fy
  val ry = -fx
  val camX = if (sCam <= rj.nodeS || !rj.takeBranch) -rj.actOff * 0.6 else rj.side * (rj.halfIn - RealJv.LANE / 2) * 0.4
  val horizon = max(h * (if (near) 0.34 else 0.30), signsSpace + h * 0.035)
  val f0 = w / (2 * tan(Math.toRadians(31.0)))
  val nearPlane = 3.0

  // sky, hills, land
  val hz = horizon.toFloat()
  val skyTop = if (night) Color(0xFF0A1322) else Color(0xFF3E86D8)
  val skyLow = if (night) Color(0xFF22324A) else Color(0xFFD6E8F8)
  drawRect(Brush.verticalGradient(listOf(skyTop, skyLow), 0f, hz), size = Size(size.width, hz))
  if (!night) {
    for ((cxr, cyr, rr) in listOf(Triple(0.16, 0.30, 0.07), Triple(0.55, 0.18, 0.09), Triple(0.84, 0.36, 0.06))) {
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
    for (i in 0..100) lineTo((w * i / 100).toFloat(), (horizon - h * (0.016 + 0.01 * sin(i * 0.21) + 0.007 * sin(i * 0.57 + 1.3))).toFloat())
    lineTo(size.width, hz)
    close()
  }.also { rj.hills = it; rj.hillsKey = hillKey })
  drawPath(hills, if (night) Color(0xFF1C2A1F) else Color(0xFF9CB99A))
  val grassFar = if (night) Color(0xFF22321E) else Color(0xFF8CC063)
  val grassNear = if (night) Color(0xFF152014) else Color(0xFF4F9136)
  drawRect(Brush.verticalGradient(listOf(grassFar, grassNear), hz, size.height), Offset(0f, hz), Size(size.width, size.height - hz))

  // the picture changes only when the camera moves by 20 cm (it stands still most of the time:
  // before the last 40 m it looks at the gore from a fixed point)
  val key = "${(sCam * 5).toInt()}:${size.width.toInt()}x${size.height.toInt()}:$near:${signsSpace.toInt()}"
  @Suppress("UNCHECKED_CAST")
  var cached = rj.cache as? List<Pair<Path, Int>>
  if (key != rj.cacheKey || cached == null) {
    fun sx(f: Double, r: Double) = (w / 2 + f0 * (r - camX) / (f * kz)).toFloat()
    fun sy(f: Double, z: Double) = (horizon + f0 * (camH - z) / (f * kz)).toFloat()
    // one drawing for all the shapes of a kind in a layer (a few drawings instead of a thousand)
    val out = ArrayList<Pair<Path, Int>>()
    var curPath: Path? = null
    var curKey = -1
    val fs = DoubleArray(4096)
    val rs = DoubleArray(4096)
    val xs = FloatArray(8200)
    val ys = FloatArray(8200)
    val maxF = if (near) 700.0 else 1100.0
    for (sh in rj.drawOrder) {
      val n = sh.pts.size
      if (n < 2 || n > 4000) continue
      var anyIn = false
      var minF = Double.MAX_VALUE
      for (i in 0 until n) {
        val vx = sh.pts[i].x - cam.x
        val vy = sh.pts[i].y - cam.y
        fs[i] = vx * fx + vy * fy
        rs[i] = vx * rx + vy * ry
        if (fs[i] >= nearPlane) anyIn = true
        if (fs[i] < minF) minF = fs[i]
      }
      // behind the camera, or so far that it is a dot on the horizon
      if (!anyIn || minF > maxF) continue
      var m = 0
      if (sh.kind == RealJv.RAIL) {
        // a band between two heights along the line (points behind the camera left out)
        val idx = (0 until n).filter { fs[it] >= nearPlane }
        if (idx.size < 2) continue
        for (i in idx) { xs[m] = sx(fs[i], rs[i]); ys[m] = sy(fs[i], sh.z1); m++ }
        for (i in idx.reversed()) { xs[m] = sx(fs[i], rs[i]); ys[m] = sy(fs[i], sh.z0); m++ }
      } else {
        // a polygon on the ground, cut at the near plane
        for (i in 0 until n) {
          val j = (i + 1) % n
          val ina = fs[i] >= nearPlane
          val inb = fs[j] >= nearPlane
          if (ina) { xs[m] = sx(fs[i], rs[i]); ys[m] = sy(fs[i], 0.0); m++ }
          if (ina != inb) {
            val t = (nearPlane - fs[i]) / (fs[j] - fs[i])
            val r = rs[i] + (rs[j] - rs[i]) * t
            xs[m] = sx(nearPlane, r); ys[m] = sy(nearPlane, 0.0); m++
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
}
