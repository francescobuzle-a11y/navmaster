package app.navmaster.truck.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The eye of the junction view, on the three made-up junctions and on three picture sizes (the
 * tablet's right-hand panel, a wide low strip, a large picture): the horizon where the navigators'
 * view puts it, the carriageway wide in front of the eye, the node and the way past it in the
 * picture, arrow heads in the lanes to take, a sign always there. Pure Kotlin, no Robolectric.
 */
class JunctionCameraTest {
  private val sizes = listOf("tablet" to (540 to 520), "striscia" to (1600 to 600), "grande" to (1400 to 1000))

  @Test
  fun eyeFromTheCab() {
    val problems = mutableListOf<String>()
    for (c in JvCases.all()) {
      jvRoadsNear = { _, _, _ -> c.roads }
      val scene = buildScene(c.scene, false)
      if (scene == null) { problems += "${c.name}: nessuna scena"; continue }
      // the eye: above the road, before the node, looking at it
      val cam = scene.camera
      val exitLike = c.scene.junction.kind != app.navmaster.truck.nav.LanePlan.Kind.JUNCTION
      if (abs(cam[2] - (if (exitLike) EYE_HEIGHT_EXIT else EYE_HEIGHT)) > 1e-9) problems += "${c.name}: occhio a ${cam[2]} m"
      val back = sqrt(cam[0] * cam[0] + cam[1] * cam[1])
      val wanted = if (exitLike) EYE_BACK_EXIT else EYE_BACK_JUNCTION
      if (abs(back - wanted) > 6) problems += "${c.name}: occhio a ${"%.0f".format(back)} m dal nodo invece di $wanted"
      if (cam[0] > -wanted + 6) problems += "${c.name}: occhio non prima del nodo (x ${"%.0f".format(cam[0])})"
      if (scene.forward[0] < 0.9) problems += "${c.name}: sguardo non nel senso di marcia ${scene.forward.toList()}"
      if (scene.gantry == null) problems += "${c.name}: nessun cartello"
      // at exits and forks the road taken leaves its lanes only outwards: its drawn middle never
      // comes back towards the other branch (the old fixed taper made a kink there)
      if (exitLike) {
        val side = c.scene.junction.side
        var prev = Double.NaN
        var m = scene.nodeM
        while (m < scene.nodeM + 200) {
          val fi = (m - scene.routeFrom) / scene.routeStep
          val n = scene.routeX.size - 1
          val k = fi.toInt().coerceIn(0, n - 1)
          val k0 = (k - 1).coerceAtLeast(0); val k1 = (k + 2).coerceAtMost(n)
          val dx = scene.routeX[k1] - scene.routeX[k0]; val dy = scene.routeY[k1] - scene.routeY[k0]
          val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
          val y = scene.routeY[k] + dx / l * scene.routeLaneY[k]
          if (!prev.isNaN() && (if (side > 0) y > prev + 0.05 else y < prev - 0.05))
            problems += "${c.name}: la strada presa torna verso l'altro ramo a ${"%.0f".format(m - scene.nodeM)} m dal nodo (${"%.2f".format(prev)} → ${"%.2f".format(y)})"
          prev = y
          m += scene.routeStep
        }
      }
      if (scene.guideX.size < 5) problems += "${c.name}: via attraverso l'incrocio di ${scene.guideX.size} punti"
      if (scene.paint.size < 3) problems += "${c.name}: ${scene.paint.size} punte di freccia sulle corsie da prendere"
      for ((sizeName, wh) in sizes) {
        val (w, h) = wh
        val p = Projector(scene, w.toFloat(), h.toFloat())
        val tag = "${c.name}/$sizeName"
        // the horizon around 30 % of the height
        val hf = p.horizon / h
        if (hf < 0.26 || hf > 0.34) problems += "$tag: orizzonte al ${"%.0f".format(hf * 100)} % dell'altezza"
        // our lanes 25 m ahead: wide on the screen (at least a quarter of the width), in the lower part
        val yL = scene.laneY.first() + scene.laneW / 2; val yR = scene.laneY.last() - scene.laneW / 2
        val ax = cam[0] + scene.forward[0] * 25; val ay = cam[1] + scene.forward[1] * 25
        val nx = -scene.forward[1]; val ny = scene.forward[0]
        val l = p.project(ax + nx * yL, ay + ny * yL, 0.0)
        val r = p.project(ax + nx * yR, ay + ny * yR, 0.0)
        if (l == null || r == null) problems += "$tag: corsie fuori dall'inquadratura a 25 m"
        else {
          val wl = abs(r[0] - l[0])
          if (wl < minOf(w / 4f, h * 0.6f)) problems += "$tag: corsie larghe ${"%.0f".format(wl)} px su $w×$h a 25 m"
          if (l[1] < p.horizon || l[1] > h) problems += "$tag: corsie a 25 m fuori dal quadro (y ${"%.0f".format(l[1])})"
        }
        // the node and the end of the ribbon in the picture, under the horizon
        val node = p.project(0.0, 0.0, 0.0)
        if (node == null || node[0] < 0 || node[0] > w || node[1] < p.horizon || node[1] > h) problems += "$tag: nodo fuori dal quadro ${node?.toList()}"
        // the ribbon a few metres past the node still in the picture (a sharp turn leaves it later)
        val k = (scene.guideNode + 3).coerceAtMost(scene.guideX.size - 1)
        val past = p.project(scene.guideX[k], scene.guideY[k], 0.0)
        if (past == null || past[0] < 0 || past[0] > w || past[1] < p.horizon - 1 || past[1] > h) problems += "$tag: nastro oltre il nodo fuori dal quadro ${past?.toList()}"
        // the arrow heads: from in front of the eye (the lower half of the picture) onwards
        val heads = scene.paint.mapNotNull { (poly, _) -> poly.map { p.project(it[0], it[1], 0.0) }.takeIf { pts -> pts.all { it != null } } }
        if (heads.isEmpty()) problems += "$tag: nessuna punta di freccia davanti all'occhio"
        val lowest = heads.maxOfOrNull { pts -> pts.maxOf { it!![1] } } ?: 0f
        if (lowest < h * 0.5f) problems += "$tag: punte di freccia tutte nella metà alta (y ${"%.0f".format(lowest)})"
      }
    }
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }
}
