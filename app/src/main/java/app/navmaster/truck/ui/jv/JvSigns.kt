package app.navmaster.truck.ui.jv

import kotlin.math.max
import kotlin.math.min

/**
 * One panel of the gantry over the road, as the junction view hangs it in the scene: which lanes
 * of the gore it stands over, its colours (ARGB), what is written on it. Pure Kotlin: the tablet
 * draws it with Compose, the computer with Java2D, both through [SignLayout].
 */
class SignSpec(
    /** The lanes at the gore (0 = leftmost) the panel hangs over, first and last. */
    val laneFrom: Int,
    val laneTo: Int,
    val bg: Long,
    val fg: Long,
    val exitNumber: String?,
    val refs: List<String>,
    val towns: List<String>,
    /** -1 an arrow to the left, 1 to the right, 0 straight up, 2 one arrow down over each lane. */
    val arrow: Int,
    /** The route goes this way (else the panel is dimmed). */
    val taken: Boolean,
    /** With [arrow] 2: which of the lanes' arrows are lit (the lanes to take). */
    val laneLit: List<Boolean> = emptyList(),
    /** Colours of the road-number plates (bg, fg ARGB), one per ref. */
    val refColors: List<Pair<Long, Long>> = emptyList(),
) {
  val lanes: Int
    get() = laneTo - laneFrom + 1

  /**
   * Metres: the panel spans its lanes and then some, and is taller than a real one, as on the
   * dedicated navigators (a sign of the real size could not be read on the screen).
   */
  val widthM: Double
    get() = max(lanes * JvGeo.LANE * 1.5 + 1.5, 9.0)
  val heightM: Double
    get() = 6.4
}

/** A rectangle of the panel (units), a corner radius and a colour. */
class SignRect(val x: Double, val y: Double, val w: Double, val h: Double, val r: Double, val color: Long, val strokeW: Double = 0.0)

/** A text of the panel: its baseline-less top-left (units), its height (units), bold, colour; [align] -1 left, 0 centre. */
class SignText(val x: Double, val y: Double, val size: Double, val text: String, val color: Long, val align: Int = -1, val maxW: Double = 0.0)

/** An arrow of the panel: -1 left (45°), 1 right, 0 up, 2 down; centre and size (units). */
class SignArrow(val kind: Int, val cx: Double, val cy: Double, val size: Double, val color: Long)

/** What is drawn on a panel, in units (100 per metre), from the top-left corner. */
class SignDrawing(val w: Double, val h: Double, val rects: List<SignRect>, val texts: List<SignText>, val arrows: List<SignArrow>)

/**
 * The layout of a panel's content, the way the signs on the motorways are composed: the exit number
 * top left, the road numbers on their plates, the towns in big letters, the arrow of the way to go.
 */
object SignLayout {
  const val UNITS_PER_M = 100.0

  /** The width of [text] at [size] units, as the bold sans of the tablet roughly measures it. */
  fun textWidth(text: String, size: Double): Double {
    var w = 0.0
    for (c in text) w += when {
      c == ' ' -> 0.30
      c == 'i' || c == 'l' || c == 'j' || c == '\'' || c == '.' -> 0.30
      c == 'm' || c == 'w' || c == 'M' || c == 'W' -> 0.86
      c.isUpperCase() || c.isDigit() -> 0.66
      else -> 0.56
    }
    return w * size
  }

  fun dim(c: Long, taken: Boolean): Long {
    if (taken) return c
    val a = (c ushr 24) and 0xFF
    val r = ((c shr 16) and 0xFF).toInt()
    val g = ((c shr 8) and 0xFF).toInt()
    val b = (c and 0xFF).toInt()
    // towards grey, a little darker
    fun m(v: Int) = ((v * 0.55 + 90 * 0.45) * 0.9).toInt().coerceIn(0, 255)
    return (a shl 24) or (m(r).toLong() shl 16) or (m(g).toLong() shl 8) or m(b).toLong()
  }

  fun layout(s: SignSpec): SignDrawing {
    val w = s.widthM * UNITS_PER_M
    val h = s.heightM * UNITS_PER_M
    val bg = dim(s.bg, s.taken)
    val fg = dim(s.fg, s.taken)
    val rects = ArrayList<SignRect>()
    val texts = ArrayList<SignText>()
    val arrows = ArrayList<SignArrow>()
    val pad = 26.0
    rects += SignRect(0.0, 0.0, w, h, 18.0, bg)
    rects += SignRect(7.0, 7.0, w - 14, h - 14, 14.0, fg, strokeW = 5.0)
    val inner = w - 2 * pad
    var y = pad
    // the first line: the arrow (left or right panels), the exit number, the road numbers
    val lineH = 104.0
    var x = pad
    if (s.arrow != 2) {
      arrows += SignArrow(s.arrow, x + lineH * 0.45, y + lineH * 0.5, lineH * 0.9, fg)
      x += lineH * 1.05
    }
    if (s.exitNumber != null) {
      val t = "USCITA ${s.exitNumber}"
      val sz = 48.0
      val tw = textWidth(t, sz) + 24
      rects += SignRect(x, y + (lineH - sz * 1.3) / 2, tw, sz * 1.3, 6.0, dim(0xFFF5C400, s.taken))
      texts += SignText(x + 12, y + (lineH - sz * 1.3) / 2 + sz * 0.1, sz, t, dim(0xFF000000, s.taken))
      x += tw + 14
    }
    for ((i, r) in s.refs.take(3).withIndex()) {
      val (pb, pf) = s.refColors.getOrNull(i) ?: (bg to fg)
      val sz = 56.0
      val tw = textWidth(r, sz) + 26
      if (x + tw > w - pad) break
      rects += SignRect(x, y + (lineH - sz * 1.35) / 2, tw, sz * 1.35, 8.0, dim(pb, s.taken))
      rects += SignRect(x + 3, y + (lineH - sz * 1.35) / 2 + 3, tw - 6, sz * 1.35 - 6, 6.0, dim(pf, s.taken), strokeW = 3.0)
      texts += SignText(x + 13, y + (lineH - sz * 1.35) / 2 + sz * 0.12, sz, r, dim(pf, s.taken))
      x += tw + 12
    }
    val firstLine = s.arrow != 2 || s.exitNumber != null || s.refs.isNotEmpty()
    if (firstLine) y += lineH + 8
    // the towns: as big as the panel allows, one per line
    val towns = s.towns.take(if (s.arrow == 2) 2 else 3)
    val roomH = h - y - pad - (if (s.arrow == 2) 90.0 else 0.0)
    val lines = max(1, towns.size)
    var sz = min(170.0, roomH / lines / 1.22)
    for (t in towns) sz = min(sz, 170.0 * inner / max(1.0, textWidth(t, 170.0)))
    sz = max(sz, 34.0)
    for (t in towns) {
      texts += SignText(pad, y, sz, t, fg, align = if (s.arrow == 2) 0 else -1, maxW = inner)
      y += sz * 1.22
    }
    // the gantry over the lanes: one arrow down over each lane of the panel
    if (s.arrow == 2) {
      val n = s.lanes
      val cy = h - pad - 38
      for (i in 0 until n) {
        val lit = s.laneLit.getOrElse(i) { true }
        val c = if (lit) fg else dim(fg, false)
        arrows += SignArrow(2, pad + inner * (i + 0.5) / n, cy, 76.0, c)
      }
    }
    return SignDrawing(w, h, rects, texts, arrows)
  }

  /**
   * The 3x3 homography (row-major a..i: x' = (ax+by+c)/(gx+hy+i)) from the unit rectangle
   * (0,0)-(w,0)-(w,h)-(0,h) to the four screen corners [q] (x0,y0, x1,y1, x2,y2, x3,y3 in that order).
   */
  fun homography(w: Double, h: Double, q: FloatArray): DoubleArray {
    // first the square (0,0),(1,0),(1,1),(0,1) to q (the classic 4-point solution), then scaled
    val x0 = q[0].toDouble(); val y0 = q[1].toDouble()
    val x1 = q[2].toDouble(); val y1 = q[3].toDouble()
    val x2 = q[4].toDouble(); val y2 = q[5].toDouble()
    val x3 = q[6].toDouble(); val y3 = q[7].toDouble()
    val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
    val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
    val den = dx1 * dy2 - dx2 * dy1
    val g = if (den == 0.0) 0.0 else (dx3 * dy2 - dx2 * dy3) / den
    val hh = if (den == 0.0) 0.0 else (dx1 * dy3 - dx3 * dy1) / den
    val a = x1 - x0 + g * x1
    val b = x3 - x0 + hh * x3
    val c = x0
    val d = y1 - y0 + g * y1
    val e = y3 - y0 + hh * y3
    val f = y0
    // units to the square: x/w, y/h
    return doubleArrayOf(a / w, b / h, c, d / w, e / h, f, g / w, hh / h, 1.0)
  }

  /** The point (x, y) of the panel (units) on the screen through the homography [m]. */
  fun map(m: DoubleArray, x: Double, y: Double): DoubleArray {
    val z = m[6] * x + m[7] * y + m[8]
    return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / z, (m[3] * x + m[4] * y + m[5]) / z)
  }

  /** The inverse of a 3x3 homography (for the computer's pixel-by-pixel warp). */
  fun invert(m: DoubleArray): DoubleArray {
    val (a, b, c) = doubleArrayOf(m[0], m[1], m[2])
    val (d, e, f) = doubleArrayOf(m[3], m[4], m[5])
    val (g, h, i) = doubleArrayOf(m[6], m[7], m[8])
    val A = e * i - f * h
    val B = -(d * i - f * g)
    val C = d * h - e * g
    val det = a * A + b * B + c * C
    if (det == 0.0) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    return doubleArrayOf(
        A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
        B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
        C / det, -(a * h - b * g) / det, (a * e - b * d) / det)
  }
}
