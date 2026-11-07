package app.navmaster.truck.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The eye of the junction view, on the three made-up junctions and on three picture sizes (the
 * tablet's right-hand panel, a wide low strip, a large picture): the horizon where the driver's
 * view puts it, the lanes wide in front of the eye, the node and the end of the ribbon in the
 * picture, a sign always there. Pure Kotlin, no Robolectric.
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
      // the eye: a truck driver's height, before the node, looking the way the road goes
      val cam = scene.camera
      if (abs(cam[2] - EYE_HEIGHT) > 1e-9) problems += "${c.name}: occhio a ${cam[2]} m"
      val exitLike = c.scene.junction.kind != app.navmaster.truck.nav.LanePlan.Kind.JUNCTION
      val back = sqrt(cam[0] * cam[0] + cam[1] * cam[1])
      val wanted = if (exitLike) EYE_BACK_EXIT else EYE_BACK_JUNCTION
      if (abs(back - wanted) > 6) problems += "${c.name}: occhio a ${"%.0f".format(back)} m dal nodo invece di $wanted"
      if (cam[0] > -wanted + 6) problems += "${c.name}: occhio non prima del nodo (x ${"%.0f".format(cam[0])})"
      if (scene.forward[0] < 0.9) problems += "${c.name}: sguardo non nel senso di marcia ${scene.forward.toList()}"
      if (scene.gantry == null) problems += "${c.name}: nessun cartello"
      if (scene.guideX.size < 5) problems += "${c.name}: nastro di guida di ${scene.guideX.size} punti"
      for ((sizeName, wh) in sizes) {
        val (w, h) = wh
        val p = Projector(scene, w.toFloat(), h.toFloat())
        val tag = "${c.name}/$sizeName"
        // the horizon around 40 % of the height
        val hf = p.horizon / h
        if (hf < 0.36 || hf > 0.44) problems += "$tag: orizzonte al ${"%.0f".format(hf * 100)} % dell'altezza"
        // the eye's lane 10 m ahead: wide on the screen (at least a sixth of the width)
        val lane = c.scene.vehicleLane?.takeIf { it in scene.laneY.indices } ?: scene.laneY.indices.last
        val yC = scene.laneY[lane]
        val ax = cam[0] + scene.forward[0] * 10; val ay = cam[1] + scene.forward[1] * 10
        val nx = -scene.forward[1]; val ny = scene.forward[0]
        val l = p.project(ax + nx * (yC + scene.laneW / 2), ay + ny * (yC + scene.laneW / 2), 0.0)
        val r = p.project(ax + nx * (yC - scene.laneW / 2), ay + ny * (yC - scene.laneW / 2), 0.0)
        if (l == null || r == null) problems += "$tag: corsia dell'occhio fuori dall'inquadratura"
        else {
          val wl = abs(r[0] - l[0])
          if (wl < w / 6f) problems += "$tag: corsia larga ${"%.0f".format(wl)} px su $w a 10 m"
          if (l[1] < p.horizon || l[1] > h) problems += "$tag: corsia a 10 m fuori dal quadro (y ${"%.0f".format(l[1])})"
        }
        // the node and the end of the ribbon in the picture, under the horizon
        val node = p.project(0.0, 0.0, 0.0)
        if (node == null || node[0] < 0 || node[0] > w || node[1] < p.horizon || node[1] > h) problems += "$tag: nodo fuori dal quadro ${node?.toList()}"
        // the ribbon a few metres past the node still in the picture (a sharp turn leaves it later)
        val k = (scene.guideNode + 3).coerceAtMost(scene.guideX.size - 1)
        val past = p.project(scene.guideX[k], scene.guideY[k], 0.0)
        if (past == null || past[0] < 0 || past[0] > w || past[1] < p.horizon - 1 || past[1] > h) problems += "$tag: nastro oltre il nodo fuori dal quadro ${past?.toList()}"
        // the painted arrows: in front of the eye, in the lower half of the picture, not tiny
        val painted = scene.paint.mapNotNull { (poly, _) -> poly.map { p.project(it[0], it[1], 0.0) }.takeIf { pts -> pts.all { it != null } } }
        if (c.scene.junction.painted) {
          if (painted.isEmpty()) problems += "$tag: nessuna freccia dipinta davanti all'occhio"
          val lowest = painted.maxOfOrNull { pts -> pts.maxOf { it!![1] } } ?: 0f
          if (lowest < h * 0.5f) problems += "$tag: frecce dipinte tutte nella metà alta (y ${"%.0f".format(lowest)})"
        }
      }
    }
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }
}
