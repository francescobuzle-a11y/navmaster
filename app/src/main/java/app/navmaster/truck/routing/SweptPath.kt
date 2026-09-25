package app.navmaster.truck.routing

import app.navmaster.truck.core.XY
import app.navmaster.truck.vehicle.VehicleProfile

/**
 * Where the wheels of a long vehicle really go when the front follows a path.
 *
 * Each body of the vehicle (tractor, semi-trailer, trailer) is pulled by a point in front of its
 * axle and its axle follows that point at a fixed distance (a tractrix): the rear axles cut the
 * inside of a curve. Doing this step by step along the path gives, at every moment, where each
 * axle is and how the vehicle is bent; the checks for curves, exit ramps and junctions use it.
 */
object SweptPath {
  data class Body(val wheelbase: Double, val couplingBehind: Double)

  /** The vehicle at one moment: front axle, the axle of each body and its direction. */
  data class Pose(val front: XY, val axles: List<XY>, val dirs: List<XY>)

  data class Run(val poses: List<Pose>, val bodies: List<Body>) {
    val rear: List<XY>
      get() = poses.map { it.axles.last() }
  }

  fun bodies(v: VehicleProfile): List<Body> {
    val list = mutableListOf(Body(v.wheelbaseM.coerceAtLeast(1.5), 0.0))
    if (v.trailerWheelbaseM > 0.5) {
      list[0] = list[0].copy(couplingBehind = v.couplingOffsetM)
      list += Body(v.trailerWheelbaseM, 0.0)
    }
    return list
  }

  /** Resample a path at a fixed step. */
  fun resample(path: List<XY>, step: Double): List<XY> {
    if (path.size < 2) return path
    val out = mutableListOf(path[0])
    var need = step
    for (i in 1 until path.size) {
      val a = path[i - 1]
      val b = path[i]
      val seg = (b - a).len()
      var pos = need
      while (pos <= seg && seg > 0) {
        out += a + (b - a) * (pos / seg)
        pos += step
      }
      need = pos - seg
    }
    if (out.last() != path.last()) out += path.last()
    return out
  }

  /**
   * Drives the vehicle with the middle of its front axle on [path] (metres on a local plane). The
   * vehicle starts straight, aligned with the first segment, so the path should begin at least one
   * vehicle length before the curve.
   */
  fun drive(path: List<XY>, v: VehicleProfile, step: Double = 0.25): Run {
    val pts = resample(path, step)
    val bs = bodies(v)
    if (pts.size < 2) return Run(emptyList(), bs)
    val dir0 = (pts[1] - pts[0]).unit()
    val axles = ArrayList<XY>()
    var guide = pts[0]
    for (b in bs) {
      val axle = guide - dir0 * b.wheelbase
      axles += axle
      guide = axle - dir0 * b.couplingBehind
    }
    val poses = ArrayList<Pose>(pts.size)
    for (p in pts) {
      var g = p
      val dirs = ArrayList<XY>(bs.size)
      for ((k, b) in bs.withIndex()) {
        val d = axles[k] - g
        val len = d.len()
        axles[k] = if (len < 1e-6) g - dir0 * b.wheelbase else g + d * (b.wheelbase / len)
        val bodyDir = (g - axles[k]).unit()
        dirs += bodyDir
        g = axles[k] - bodyDir * b.couplingBehind
      }
      poses += Pose(p, ArrayList(axles), dirs)
    }
    return Run(poses, bs)
  }

  /** Corners of every body at one moment (for the drawing): one list of 4 points per body. */
  fun outline(pose: Pose, bodies: List<Body>, v: VehicleProfile): List<List<XY>> {
    val half = v.widthM / 2
    val out = mutableListOf<List<XY>>()
    var g = pose.front
    val used = v.frontOverhangM + bodies.sumOf { it.wheelbase } + bodies.dropLast(1).sumOf { it.couplingBehind }
    for ((k, b) in bodies.withIndex()) {
      val a = pose.axles[k]
      val dir = pose.dirs[k]
      val n = dir.normal()
      val frontEnd = if (k == 0) g + dir * v.frontOverhangM else g + dir * 1.6
      val rearEnd = if (k == bodies.size - 1) a - dir * (v.lengthM - used).coerceIn(0.5, 6.0) else a - dir * 0.8
      out += listOf(frontEnd + n * half, frontEnd - n * half, rearEnd - n * half, rearEnd + n * half)
      g = a - dir * b.couplingBehind
    }
    return out
  }

  /** The wheels on one side (sign +1 = left of the direction of travel) of every axle. */
  fun sideWheels(pose: Pose, v: VehicleProfile, sign: Double): List<XY> {
    val half = v.widthM / 2
    val out = mutableListOf<XY>()
    // front axle: its direction is the tractor's
    out += pose.front + pose.dirs[0].normal() * (half * sign)
    for ((k, a) in pose.axles.withIndex()) out += a + pose.dirs[k].normal() * (half * sign)
    return out
  }
}
