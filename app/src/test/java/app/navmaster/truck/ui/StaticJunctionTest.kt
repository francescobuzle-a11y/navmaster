package app.navmaster.truck.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The fixed perspective of the junction view on the tablet's right-hand panel and on a wide strip:
 * the horizon where it is meant to be, lanes wide enough to read from the driver's seat, an exit
 * that leaves on its own side and keeps leaving, a sign for every junction. Pure Kotlin.
 */
class StaticJunctionTest {
  private val sizes = listOf("tablet" to (538f to 575f), "striscia" to (1200f to 450f))

  @Test
  fun fixedPerspective() {
    val problems = mutableListOf<String>()
    for ((name, wh) in sizes) for (n in 1..6) {
      val p = Persp(wh.first, wh.second, n)
      val tag = "$name/$n corsie"
      if (abs(p.hz / wh.second - HORIZON_FRAC) > 1e-3) problems += "$tag: orizzonte al ${p.hz / wh.second}"
      // the road at the bottom edge inside the picture, each lane at least 50 px (about 35 dp)
      val l = p.x(-1.0, 1.0); val r = p.x(1.0, 1.0)
      if (l < 0 || r > wh.first) problems += "$tag: strada fuori dal quadro ($l … $r)"
      val lane = (r - l) / n
      if (lane < 50f) problems += "$tag: corsia larga $lane px in basso"
      // depth back and forth
      val z = 2.7
      if (abs(p.zAt(p.y(z)) - z) > 1e-3) problems += "$tag: profondità non invertibile"
      // the far end of the road near the horizon
      if (p.y(30.0) - p.hz > (wh.second - p.hz) * 0.05) problems += "$tag: la strada finisce lontano dall'orizzonte"
    }
    for (c in JvCases.all()) {
      val lay = Layout(c.scene)
      val sign = signOf(c.scene)
      if (sign.panels.isEmpty()) problems += "${c.name}: nessun cartello"
      if (sign.panels.any { it.l0 < 0 || it.l1 >= maxOf(lay.n, 2) || it.l1 < it.l0 }) problems += "${c.name}: pannello su corsie inesistenti"
      if (lay.active.isEmpty()) problems += "${c.name}: nessuna corsia da prendere"
      // exits and forks: a branch on the manoeuvre's side; turns in town: the crossing road
      val exitLike = c.scene.junction.kind != app.navmaster.truck.nav.LanePlan.Kind.JUNCTION
      val slight = !exitLike && abs(c.scene.junction.taken.angle) < 50
      if ((exitLike || slight) && !lay.branch) problems += "${c.name}: uscita o svolta leggera senza ramo"
      if (!exitLike && !slight && !lay.turn) problems += "${c.name}: svolta senza strada trasversale"
      if (lay.branch) {
        val p = Persp(538f, 575f, lay.n)
        // the branch leaves the road going on only outwards, more and more, from where it splits
        val u = p.boundary(lay.splitB)
        var prev = 0f
        var z = 1.0
        while (z < 10.0) {
          val gap = (pt(p, lay, u, z, true).x - pt(p, lay, u, z, false).x) * lay.side
          if (gap < prev - 0.5f) problems += "${c.name}: il ramo torna verso la strada a profondità $z"
          if (z < 2.2 && abs(gap) > 0.5f) problems += "${c.name}: il ramo si stacca prima della cuspide (a profondità $z)"
          prev = gap
          z += 0.5
        }
        if (prev < 40f) problems += "${c.name}: il ramo non si allontana dalla strada ($prev px)"
      }
    }
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }
}
