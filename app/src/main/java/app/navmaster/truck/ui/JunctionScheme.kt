package app.navmaster.truck.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * The junction as the dedicated navigators draw it (a clear drawing, not a photo of the real
 * place): the carriageway with its lanes, the road that leaves on its side with the white gore
 * between the two, crash barriers, and a big arrow on the lanes to take. All drawn here; the
 * facts (lanes, which ones to take, side and angle of the branch) come from the route and the graph.
 */
internal class JvModel(
    /** Lanes of the carriageway before the junction, 0 = the leftmost. */
    val n: Int,
    /** Where the other road leaves: -1 left, 1 right. */
    val side: Int,
    /** Lanes that go into the branch (the outer ones on [side]). */
    val branchLanes: Int,
    /** The route leaves on the branch (else it keeps the main road). */
    val takeBranch: Boolean,
    /** A split of two main roads (both bend away) rather than an exit. */
    val fork: Boolean,
    /** How much the branch turns away, degrees (widened for clarity). */
    val angle: Double,
    /** The lanes to take. */
    val active: List<Int>,
)

private fun dirsOf(l: uniffi.ferrostar.LaneInfo) = l.directions.map { it.lowercase().replace('_', ' ') }

internal fun jvModelOf(scene: JunctionScene): JvModel {
  val lanes = scene.lanes.take(8)
  val a = scene.analysis
  val js = a?.junctionShapeNear(scene.maneuverAtM, 90.0)
  val graphLanes = a?.edgeAt((scene.maneuverAtM - 40).coerceAtLeast(0.0))?.lanes ?: 0
  fun signed(h1: Double, h2: Double) = ((h1 - h2 + 540.0) % 360.0) - 180.0
  val ref = js?.inHeading ?: js?.outHeading
  val other = js?.branches?.filter { b -> js.outHeading == null || abs(signed(b.heading, js.outHeading)) > 6 }
      ?.minByOrNull { b -> ref?.let { abs(signed(b.heading, it)) } ?: 0.0 }
  val takeBranch = scene.side != 0
  val side = when {
    scene.side != 0 -> scene.side
    other != null && ref != null -> if (signed(other.heading, ref) >= 0) 1 else -1
    else -> 1
  }
  val realAngle = when {
    takeBranch && js?.inHeading != null && js.outHeading != null -> abs(signed(js.outHeading, js.inHeading))
    other != null && ref != null -> abs(signed(other.heading, ref))
    else -> 18.0
  }
  val angle = (realAngle * 1.8).coerceIn(20.0, 42.0)
  val n = (if (lanes.size >= 2) lanes.size
           else (if (graphLanes >= 1) graphLanes else if (scene.motorway) 3 else 2) + (if (takeBranch && !scene.fork) 1 else 0))
      .coerceIn(2, 8)
  val sideWords = if (side > 0) setOf("right", "slight right", "sharp right") else setOf("left", "slight left", "sharp left")
  var b = 0
  if (lanes.size == n) {
    val order = if (side > 0) (n - 1 downTo 0) else (0 until n)
    for (i in order) {
      val d = dirsOf(lanes[i])
      if (d.any { it in sideWords } || (d.isEmpty() && takeBranch && lanes[i].active)) b++ else break
    }
  }
  if (b == 0) b = if (scene.fork) n / 2 else if (takeBranch) (lanes.count { it.active }.takeIf { lanes.size == n && it > 0 } ?: 1) else 1
  b = b.coerceIn(1, n - 1)
  val branchSet = if (side > 0) (n - b until n).toList() else (0 until b).toList()
  val mainSet = (0 until n).filter { it !in branchSet }
  val fromLanes = if (lanes.size == n) lanes.indices.filter { lanes[it].active } else emptyList()
  val active = when {
    fromLanes.isNotEmpty() && !fromLanes.containsAll(0 until n) -> fromLanes
    takeBranch -> branchSet
    else -> mainSet.sortedBy { abs(it - (if (side > 0) n - b - 0.5 else b - 0.5)) }.take(3).sorted()
  }.take(4)
  return JvModel(n, side, b, takeBranch, scene.fork, angle, active)
}

private class Palette(night: Boolean) {
  val skyTop = if (night) Color(0xFF0A1322) else Color(0xFF3E86D8)
  val skyLow = if (night) Color(0xFF22324A) else Color(0xFFD6E8F8)
  val hills = if (night) Color(0xFF1C2A1F) else Color(0xFF9CB99A)
  val grassFar = if (night) Color(0xFF22321E) else Color(0xFF8CC063)
  val grassNear = if (night) Color(0xFF152014) else Color(0xFF4F9136)
  val asphalt = if (night) Color(0xFF26282C) else Color(0xFF3B3E43)
  val shoulder = if (night) Color(0xFF1E2023) else Color(0xFF2E3034)
  val gore = if (night) Color(0xFF2E3136) else Color(0xFF474B51)
  val mark = if (night) Color(0xFFD2D5D9) else Color.White
  val rail = if (night) Color(0xFF69707A) else Color(0xFFC7CCD2)
  val post = if (night) Color(0xFF4A5058) else Color(0xFF7D848C)
  val arrow = if (night) Color(0xFF8246DA) else Color(0xFF9148EE)
  val arrowLight = if (night) Color(0xFFB891F2) else Color(0xFFD2B4FF)
  val cloud = if (night) Color(0x00FFFFFF) else Color(0xE6FFFFFF)
}

/**
 * Draws the junction. [distM] metres to the junction (animated), [near]: the junction view (low
 * camera, sky, barriers, big arrow) or the lane guidance from farther (high camera, lanes lit).
 */
internal fun DrawScope.drawJunctionScheme(md: JvModel, distM: Double, near: Boolean, night: Boolean, signsSpace: Float) {
  val w = size.width.toDouble()
  val h = size.height.toDouble()
  if (w < 10 || h < 10) return
  val c = Palette(night)
  val laneW = 3.6
  val n = md.n
  val s = md.side
  val b = md.branchLanes
  val m = n - b
  val roadW = n * laneW
  val horizon = if (near) max(h * 0.42, signsSpace + h * 0.05).coerceAtMost(h * 0.56) else h * 0.08
  val zNear = if (near) 6.0 else 32.0
  // a drawn perspective, not a photographic one: the depth is spread (exponent < 1) so that the
  // junction sits in the middle of the picture and both roads are seen leaving it, as in the
  // drawings of the dedicated navigators; parallel lines still meet on the horizon
  val gam = if (near) 0.62 else 0.75
  val kx = if (near) w * 0.95 / roadW else w * 0.55 / roadW
  val camX = s * laneW * (if (near) 0.9 else 0.6)
  val zf = if (near) (14.0 + distM * 0.08).coerceIn(14.0, 46.0) else (60.0 + (distM - 400.0) * 0.1).coerceIn(60.0, 120.0)
  val zFar = zf + 20_000.0

  fun q(z: Double) = (zNear / z).pow(gam)
  fun sx(x: Double, z: Double) = w / 2 + kx * (x - camX) * q(z)
  fun sy(z: Double, y: Double = 0.0) = horizon + (h - horizon) * q(z) - kx * q(z) * y
  fun p(x: Double, z: Double, y: Double = 0.0) = Offset(sx(x, z).toFloat(), sy(z, y).toFloat())

  // ---- the shape of the roads (metres): the edge k (0..n) of the lanes, before and after the fork
  val radius = if (near) 45.0 else 110.0
  fun bend(u: Double, deg: Double): Double {
    if (u <= 0 || deg <= 0) return 0.0
    val t = tan(Math.toRadians(deg))
    val ut = radius * t
    return if (u < ut) u * u / (2 * radius) else ut * ut / (2 * radius) + (u - ut) * t
  }
  val angB = if (md.fork) md.angle * 0.6 else md.angle
  val angM = if (md.fork) md.angle * 0.4 else 0.0
  val kb = if (s > 0) m else b
  val branchEdges = if (s > 0) (m..n) else (0..b)
  val mainEdges = if (s > 0) (0..m) else (b..n)
  fun e(k: Int) = -roadW / 2 + k * laneW
  fun edgeX(k: Int, z: Double, onBranch: Boolean) =
      e(k) + if (z <= zf) 0.0 else if (onBranch) s * bend(z - zf, angB) else -s * bend(z - zf, angM)

  // depths: dense near the viewer and around the fork
  val zs = run {
    val base = (0..60).map { i -> zNear * 0.85 * (zFar / (zNear * 0.85)).pow(i / 60.0) }
    val extra = listOf(0.0, 1.5, 3.0, 5.0, 7.0, 10.0, 13.0, 17.0, 22.0, 28.0, 35.0, 45.0, 60.0, 80.0, 110.0, 150.0).map { zf + it }
    (base + extra + listOf(zf - 0.01)).sorted().distinct()
  }
  val before = zs.filter { it <= zf }
  val after = zs.filter { it >= zf }

  fun strip(list: List<Double>, left: (Double) -> Double, right: (Double) -> Double, y: Double = 0.0): Path? {
    if (list.size < 2) return null
    return Path().apply {
      val f = list.first()
      moveTo(sx(left(f), f).toFloat(), sy(f, y).toFloat())
      for (z in list.drop(1)) lineTo(sx(left(z), z).toFloat(), sy(z, y).toFloat())
      for (z in list.reversed()) lineTo(sx(right(z), z).toFloat(), sy(z, y).toFloat())
      close()
    }
  }

  // ---- sky, land
  val hz = horizon.toFloat()
  if (near) {
    drawRect(Brush.verticalGradient(listOf(c.skyTop, c.skyLow), 0f, hz), size = Size(size.width, hz))
    if (!night) {
      for ((cx, cy, r) in listOf(Triple(0.16, 0.30, 0.07), Triple(0.55, 0.18, 0.09), Triple(0.84, 0.36, 0.06))) {
        val x0 = (w * cx).toFloat()
        val y0 = (horizon * cy).toFloat()
        val rr = (w * r).toFloat()
        drawOval(c.cloud, Offset(x0 - rr, y0 - rr * 0.35f), Size(rr * 2, rr * 0.7f))
        drawOval(c.cloud, Offset(x0 - rr * 0.55f, y0 - rr * 0.6f), Size(rr * 1.1f, rr * 0.8f))
      }
    }
    val hills = Path().apply {
      moveTo(0f, hz)
      for (i in 0..100) {
        val y = hz - h * (0.018 + 0.012 * kotlin.math.sin(i * 0.21) + 0.008 * kotlin.math.sin(i * 0.57 + 1.3))
        lineTo((w * i / 100).toFloat(), y.toFloat())
      }
      lineTo(size.width, hz)
      close()
    }
    drawPath(hills, c.hills)
    drawRect(Brush.verticalGradient(listOf(c.grassFar, c.grassNear), hz, size.height), Offset(0f, hz), Size(size.width, size.height - hz))
  } else {
    drawRect(Brush.verticalGradient(listOf(c.skyLow.copy(alpha = 0.6f), c.grassFar), 0f, hz), size = Size(size.width, hz))
    drawRect(Brush.verticalGradient(listOf(c.grassFar, c.grassNear), hz, size.height), Offset(0f, hz), Size(size.width, size.height - hz))
  }

  // ---- asphalt: the carriageway before the fork, then the main road and the branch
  val sh = 1.0
  strip(before, { e(0) - sh }, { e(n) + sh })?.let { drawPath(it, c.shoulder) }
  strip(after, { edgeX(mainEdges.first, it, false) - sh }, { edgeX(mainEdges.last, it, false) + sh })?.let { drawPath(it, c.shoulder) }
  strip(after, { edgeX(branchEdges.first, it, true) - sh }, { edgeX(branchEdges.last, it, true) + sh })?.let { drawPath(it, c.shoulder) }
  strip(before, { e(0) }, { e(n) })?.let { drawPath(it, c.asphalt) }
  strip(after, { edgeX(mainEdges.first, it, false) }, { edgeX(mainEdges.last, it, false) })?.let { drawPath(it, c.asphalt) }
  strip(after, { edgeX(branchEdges.first, it, true) }, { edgeX(branchEdges.last, it, true) })?.let { drawPath(it, c.asphalt) }

  // ---- the gore between the two roads: white edges and chevrons, then grass
  val zGore = after.firstOrNull { abs(edgeX(kb, it, true) - edgeX(kb, it, false)) > 7.5 } ?: (zf + 60)
  val goreZs = after.filter { it <= zGore }
  strip(goreZs, { edgeX(kb, it, false) }, { edgeX(kb, it, true) })?.let { drawPath(it, c.gore) }

  // ---- markings, drawn as flat strips on the road so they narrow with the distance
  fun line(k: Int, onBranch: Boolean?, z0: Double, z1: Double, width: Double) {
    val list = zs.filter { it in z0..z1 }.let { if (it.size < 2) listOf(z0, z1) else listOf(z0) + it + listOf(z1) }.distinct()
    fun x(z: Double) = if (onBranch == null) e(k) else edgeX(k, z, onBranch)
    strip(list, { x(it) - width / 2 }, { x(it) + width / 2 })?.let { drawPath(it, c.mark) }
  }
  // dashes move towards the viewer as the vehicle goes on
  val phase = (distM % 15.0 + 15.0) % 15.0
  fun dashes(k: Int, onBranch: Boolean?, z0: Double, z1: Double, width: Double, on: Double, period: Double) {
    var z = z0 - ((z0 - phase) % period + period) % period
    while (z < z1) {
      val a0 = max(z, z0)
      val a1 = min(z + on, z1)
      if (a1 > a0) {
        fun x(zz: Double) = if (onBranch == null) e(k) else edgeX(k, zz, onBranch)
        val q = Path().apply {
          moveTo(sx(x(a0) - width / 2, a0).toFloat(), sy(a0).toFloat())
          lineTo(sx(x(a1) - width / 2, a1).toFloat(), sy(a1).toFloat())
          lineTo(sx(x(a1) + width / 2, a1).toFloat(), sy(a1).toFloat())
          lineTo(sx(x(a0) + width / 2, a0).toFloat(), sy(a0).toFloat())
          close()
        }
        drawPath(q, c.mark)
      }
      z += period
    }
  }
  val lw = if (near) 0.18 else 0.25
  val zMark = zNear * 0.85
  val zEnd = min(zFar, zf + 600)
  // outer edges
  line(0, null, zMark, zf, lw)
  line(n, null, zMark, zf, lw)
  line(if (s > 0) 0 else b, false, zf, zEnd, lw)
  line(if (s > 0) m else n, false, zf, zEnd, lw)
  line(if (s > 0) m else 0, true, zf, zEnd, lw)
  line(if (s > 0) n else b, true, zf, zEnd, lw)
  // lane lines
  for (k in 1 until n) {
    if (k == kb) continue
    val onBranch = k in branchEdges
    dashes(k, null, zMark, zf, lw, 5.0, 15.0)
    dashes(k, onBranch, zf, zEnd, lw, 5.0, 15.0)
  }
  // the line between the lanes that leave and the others: thick short dashes before the gore
  dashes(kb, null, zMark, zf, lw * 2.4, 3.0, 6.0)
  // chevrons in the gore
  val goreLen = zGore - zf
  if (goreLen > 8) {
    for (j in 1..4) {
      val zc = zf + goreLen * j / 5.0
      val xm = edgeX(kb, zc, false)
      val xb = edgeX(kb, zc, true)
      val mid = (xm + xb) / 2
      val tip = zc - goreLen / 7.0
      val path = Path().apply {
        moveTo(sx(xm, zc).toFloat(), sy(zc).toFloat())
        lineTo(sx(mid, tip).toFloat(), sy(tip).toFloat())
        lineTo(sx(xb, zc).toFloat(), sy(zc).toFloat())
      }
      val sw = (kx * 0.3 * q(zc)).toFloat().coerceAtLeast(1f)
      drawPath(path, c.mark, style = Stroke(sw, join = StrokeJoin.Miter))
    }
  }

  // ---- crash barriers along the outer edges and between the roads after the gore (near view)
  fun barrier(x: (Double) -> Double, z0: Double, z1: Double) {
    val list = zs.filter { it in z0..z1 }
    if (list.size < 2) return
    // the rail: a band between 0.55 and 0.8 m
    val band = Path().apply {
      val f = list.first()
      moveTo(sx(x(f), f).toFloat(), sy(f, 0.8).toFloat())
      for (z in list.drop(1)) lineTo(sx(x(z), z).toFloat(), sy(z, 0.8).toFloat())
      for (z in list.reversed()) lineTo(sx(x(z), z).toFloat(), sy(z, 0.55).toFloat())
      close()
    }
    drawPath(band, c.rail)
    var z = z0 - ((z0 - phase) % 4.0 + 4.0) % 4.0 + 4.0
    while (z < min(z1, z0 + 160)) {
      val a = p(x(z), z, 0.0)
      val bb = p(x(z), z, 0.7)
      drawLine(c.post, a, bb, strokeWidth = (kx * 0.12 * q(z)).toFloat().coerceAtLeast(1f))
      z += 4.0
    }
  }
  if (near) {
    barrier({ z -> (if (z <= zf) e(0) else edgeX(0, z, s < 0)) - sh - 0.3 }, zMark, zEnd)
    barrier({ z -> (if (z <= zf) e(n) else edgeX(n, z, s > 0)) + sh + 0.3 }, zMark, zEnd)
    barrier({ z -> edgeX(kb, z, false) + (if (s > 0) sh + 0.3 else -sh - 0.3) }, zGore, zEnd)
    barrier({ z -> edgeX(kb, z, true) + (if (s > 0) -sh - 0.3 else sh + 0.3) }, zGore, zEnd)
  }

  // ---- the lanes to take
  val branchLaneSet = if (s > 0) (m until n) else (0 until b)
  for (i in md.active) {
    val onBranch = i in branchLaneSet
    fun cx(z: Double) = edgeX(i, z, onBranch) + laneW / 2
    if (near) {
      // one big arrow on the lane, through the junction, its head on the road to take
      val z0 = zNear * 1.5
      val z1 = zf + if (onBranch) 20.0 else 16.0
      val head = 11.0
      val half = laneW * 0.3
      val headHalf = laneW * 0.68
      val body = zs.filter { it in z0..z1 }.let { listOf(z0) + it + listOf(z1) }.distinct()
      val arrow = Path().apply {
        moveTo(sx(cx(z0) - half, z0).toFloat(), sy(z0).toFloat())
        for (z in body.drop(1)) lineTo(sx(cx(z) - half, z).toFloat(), sy(z).toFloat())
        lineTo(sx(cx(z1) - headHalf, z1).toFloat(), sy(z1).toFloat())
        lineTo(sx(cx(z1 + head), z1 + head).toFloat(), sy(z1 + head).toFloat())
        lineTo(sx(cx(z1) + headHalf, z1).toFloat(), sy(z1).toFloat())
        for (z in body.reversed()) lineTo(sx(cx(z) + half, z).toFloat(), sy(z).toFloat())
        close()
      }
      // a shadow under it, the arrow, a light edge: it stands out from the road like a painted 3D arrow
      val drop = (h * 0.012).toFloat()
      drawPath(Path().apply { addPath(arrow, Offset(0f, drop)) }, Color(0x66000000))
      drawPath(arrow, Brush.verticalGradient(listOf(c.arrowLight, c.arrow), hz, size.height))
      drawPath(arrow, c.arrowLight, style = Stroke((w * 0.005).toFloat(), join = StrokeJoin.Round))
    } else {
      // lane guidance: the lanes to take lit all along, as far as the road to take
      val z1 = zf + if (onBranch) 160.0 else 200.0
      // (far: the whole lane lit, up to well beyond the junction)
      val list = zs.filter { it in zMark..z1 }
      strip(list, { cx(it) - laneW * 0.42 }, { cx(it) + laneW * 0.42 })?.let { drawPath(it, c.arrow) }
      strip(list, { cx(it) - laneW * 0.16 }, { cx(it) + laneW * 0.16 })?.let { drawPath(it, c.arrowLight.copy(alpha = 0.55f)) }
    }
  }
}
