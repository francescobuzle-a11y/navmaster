package app.navmaster.truck.routing

import app.navmaster.truck.core.XY
import app.navmaster.truck.vehicle.VehicleProfile
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Can this vehicle make this turn? Two checks, both driving the real vehicle geometry
 * (SweptPath) through the road geometry of the map:
 *  - junction: the vehicle comes in on one road and leaves on another; the driver may use the full
 *    width of both roads and the best arc is searched; the inner rear wheels must stay off the
 *    inner corner (with the usual rounded kerb);
 *  - curve: the vehicle follows the centre line of one road (exit ramps, hairpin bends) and the
 *    wheels must stay within the road plus a little shoulder.
 */
object TurnCheck {
  enum class Verdict { OK, TIGHT, NO }

  /** What was found, with everything needed to draw it (metres on a local plane). */
  data class Scene(
      val verdict: Verdict,
      /** Road strips: centre line and width. */
      val roads: List<Pair<List<XY>, Double>>,
      val front: List<XY>,
      val rear: List<XY>,
      val outlines: List<List<List<XY>>>,
      /** How far the wheels go past the edge / over the kerb in the best case (metres). */
      val overrunM: Double,
      /** Radius followed by the front axle (metres). */
      val radiusM: Double,
      /** Smallest radius this vehicle can steer (front axle centre). */
      val vehicleMinRadiusM: Double,
      val angleDeg: Double,
  )

  fun minFrontRadius(v: VehicleProfile): Double {
    val r = sqrt(max(v.turnRadiusM * v.turnRadiusM - v.frontOverhangM * v.frontOverhangM, 1.0)) - v.widthM / 2
    return r.coerceAtLeast(3.0)
  }

  private fun cross(a: XY, b: XY) = a.x * b.y - a.y * b.x

  private fun dot(a: XY, b: XY) = a.x * b.x + a.y * b.y

  /**
   * Junction at the origin: [inDir] direction of travel on the road left, [outDir] on the road
   * taken; widths of the two carriageways and radius of the inner kerb.
   */
  fun junction(inDir: XY, outDir: XY, wIn: Double, wOut: Double, kerbR: Double, v: VehicleProfile): Scene? {
    val a = inDir.unit()
    val b = outDir.unit()
    val theta = Math.toDegrees(acos(dot(a, b).coerceIn(-1.0, 1.0)))
    if (theta < 35 || theta > 170) return null
    val sgn = if (cross(a, b) > 0) 1.0 else -1.0
    val nA = a.normal() * sgn // towards the inside of the turn
    val nB = b.normal() * sgn
    val half = v.widthM / 2
    // the corner point C of the rounded kerb: sA = sB = kerbR
    val c = solve(nA, wIn / 2 + kerbR, nB, wOut / 2 + kerbR) ?: return null

    fun depth(p: XY): Double {
      val sA = dot(p, nA) - wIn / 2
      val sB = dot(p, nB) - wOut / 2
      if (sA <= 0 || sB <= 0) return 0.0
      if (sA < kerbR && sB < kerbR) {
        val d = (p - c).len()
        return if (d > kerbR) 0.0 else kerbR - d
      }
      return min(sA, sB)
    }

    // the driver keeps to the outside before and after the turn, using the whole carriageway
    val oA = max(0.0, wIn / 2 - half - 0.3)
    val oB = max(0.0, wOut / 2 - half - 0.3)
    val pa = nA * (-oA)
    val pb = nB * (-oB)
    val x = intersect(pa, a, pb, b) ?: return null
    val phi = Math.toRadians(180 - theta)
    val rMin = minFrontRadius(v)
    val candidates = listOf(40.0, 32.0, 26.0, 22.0, 19.0, 17.0, 15.0, 13.5, 12.0, 11.0, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0, 4.0)
        .filter { it >= rMin * 0.98 }
    var best: Triple<Double, Double, SweptPath.Run>? = null // depth, radius, run
    var bestPath: List<XY> = emptyList()
    for (r in candidates) {
      val t = r / tan(phi / 2)
      if (t > 80) continue
      val t1 = x - a * t
      val t2 = x + b * t
      val centre = t1 + nA * r
      val path = mutableListOf<XY>()
      path += t1 - a * (v.lengthM + 12)
      // arc from t1 to t2 around centre
      val start = t1 - centre
      val end = t2 - centre
      val sweep = Math.atan2(cross(start, end), dot(start, end))
      val n = max(8, (abs(sweep) * r / 0.5).toInt())
      for (i in 0..n) {
        val ang = sweep * i / n
        val cs = cos(ang)
        val sn = sin(ang)
        path += centre + XY(start.x * cs - start.y * sn, start.x * sn + start.y * cs)
      }
      path += t2 + b * (v.lengthM + 8)
      val run = SweptPath.drive(path, v)
      var worst = 0.0
      for (pose in run.poses) {
        for (w in SweptPath.sideWheels(pose, v, sgn)) worst = max(worst, depth(w))
      }
      if (best == null || worst < best.first - 0.05 || (abs(worst - best.first) <= 0.05 && r > best.second)) {
        best = Triple(worst, r, run)
        bestPath = path
      }
    }
    val (d, r, run) = best ?: return null
    val verdict = when {
      d > 0.8 -> Verdict.NO
      d > 0.15 || r <= rMin * 1.12 -> Verdict.TIGHT
      else -> Verdict.OK
    }
    val roads = listOf(
        listOf(a * -60.0, XY(0.0, 0.0)) to wIn,
        listOf(XY(0.0, 0.0), b * 60.0) to wOut,
    )
    return Scene(verdict, roads, bestPath, run.rear, snapshots(run, v), d, r, rMin, theta)
  }

  /** The vehicle follows the centre line of one road ([centre] local metres) of width [width]. */
  fun curve(centre: List<XY>, width: Double, v: VehicleProfile, shoulderM: Double = 0.5): Scene? {
    if (centre.size < 3) return null
    val dir0 = (centre[1] - centre[0]).unit()
    val path = listOf(centre[0] - dir0 * (v.lengthM + 10)) + centre
    val run = SweptPath.drive(path, v)
    val line = SweptPath.resample(path, 0.5)
    var worst = 0.0
    // every wheel is near the part of the centre line the vehicle is on: from a vehicle length
    // behind the front axle to a little ahead (keeps long ramps fast)
    val back = ((v.lengthM + 12) / 0.5).toInt()
    val ahead = (12 / 0.5).toInt()
    for ((i, pose) in run.poses.withIndex()) {
      if (i * 0.25 < v.lengthM + 10) continue // still on the straight lead-in
      val k = i / 2
      val from = (k - back).coerceAtLeast(0)
      val to = (k + ahead).coerceAtMost(line.size - 1)
      if (to - from < 1) continue
      for (s in listOf(1.0, -1.0)) for (w in SweptPath.sideWheels(pose, v, s)) {
        worst = max(worst, distance(w, line, from, to) - width / 2)
      }
    }
    val rCurve = minRadius(centre)
    val rMin = minFrontRadius(v)
    val over = max(0.0, worst - shoulderM)
    val verdict = when {
      rCurve < rMin * 0.95 || over > 0.7 -> Verdict.NO
      over > 0.0 || rCurve < rMin * 1.2 -> Verdict.TIGHT
      else -> Verdict.OK
    }
    return Scene(verdict, listOf(path to width), path, run.rear, snapshots(run, v), over, rCurve, rMin, 0.0)
  }

  private fun snapshots(run: SweptPath.Run, v: VehicleProfile): List<List<List<XY>>> {
    if (run.poses.isEmpty()) return emptyList()
    val every = max(1, run.poses.size / 7)
    return run.poses.filterIndexed { i, _ -> i % every == 0 && i > run.poses.size / 5 }.map { SweptPath.outline(it, run.bodies, v) }
  }

  fun minRadius(line: List<XY>): Double {
    val pts = SweptPath.resample(line, 5.0)
    var best = Double.MAX_VALUE
    for (i in 2 until pts.size - 2) best = min(best, circumradius(pts[i - 2], pts[i], pts[i + 2]))
    return best
  }

  private fun circumradius(a: XY, b: XY, c: XY): Double {
    val ab = (b - a).len()
    val bc = (c - b).len()
    val ac = (c - a).len()
    val area2 = abs(cross(b - a, c - a))
    return if (area2 < 1e-6) Double.MAX_VALUE else ab * bc * ac / (2 * area2)
  }

  private fun distance(p: XY, line: List<XY>, from: Int = 0, to: Int = line.size - 1): Double {
    var best = Double.MAX_VALUE
    for (i in from until to) {
      val a = line[i]
      val ab = line[i + 1] - a
      val len2 = dot(ab, ab)
      val t = if (len2 <= 0) 0.0 else (dot(p - a, ab) / len2).coerceIn(0.0, 1.0)
      best = min(best, hypot(p.x - (a.x + ab.x * t), p.y - (a.y + ab.y * t)))
    }
    return best
  }

  /** p with dot(p, n1) = d1 and dot(p, n2) = d2. */
  private fun solve(n1: XY, d1: Double, n2: XY, d2: Double): XY? {
    val det = n1.x * n2.y - n1.y * n2.x
    if (abs(det) < 1e-6) return null
    return XY((d1 * n2.y - d2 * n1.y) / det, (n1.x * d2 - n2.x * d1) / det)
  }

  /** Intersection of the lines p1 + s*d1 and p2 + t*d2. */
  private fun intersect(p1: XY, d1: XY, p2: XY, d2: XY): XY? {
    val det = cross(d1, d2)
    if (abs(det) < 1e-9) return null
    val s = cross(p2 - p1, d2) / det
    return p1 + d1 * s
  }
}
