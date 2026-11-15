package app.navmaster.truck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Angle of an OSRM lane / maneuver direction, 0 = straight on, negative = left. */
fun directionAngle(direction: String?): Float =
    when (direction?.lowercase()?.replace('_', ' ')) {
      "slight left" -> -35f
      "left" -> -80f
      "sharp left" -> -125f
      "slight right" -> 35f
      "right" -> 80f
      "sharp right" -> 125f
      "uturn", "u turn" -> -170f
      else -> 0f
    }

/*
 * The one arrow of the app, wherever a direction is shown (the lanes of the strip and of the
 * junction view, the signs, the manoeuvre bar, the way a slip road curves on): a stem with round
 * ends as thick as 16% of its cell, a soft bend, a head 1.75 times the stem long and 2.75 times
 * wide with softened corners. One shape, one weight: never a second style beside it.
 */

/** The stem's thickness as a share of the arrow's cell. */
const val ARROW_STROKE = 0.16f

/** The head at the end of a stem: its base at [tip], pointing along ([dx], [dy]). */
private fun DrawScope.arrowHead(tip: Offset, dx: Float, dy: Float, len: Float, half: Float, color: Color) {
  val path = Path().apply {
    moveTo(tip.x + dx * len, tip.y + dy * len)
    lineTo(tip.x - dy * half, tip.y + dx * half)
    lineTo(tip.x + dy * half, tip.y - dx * half)
    close()
  }
  drawPath(path, color)
  // the corners softened like the stem's ends
  drawPath(path, color, style = Stroke(width = half * 0.2f, join = StrokeJoin.Round))
}

/** Stem up, a soft bend towards the direction and the head: the arrow of a lane, a sign, a manoeuvre. */
fun DrawScope.drawTurnArrow(angleDeg: Float, color: Color, strokeFrac: Float = ARROW_STROKE, cell: Float = size.width) {
  val w = size.width
  val h = size.height
  val s = minOf(w, h)
  val rad = Math.toRadians(angleDeg.toDouble())
  val straight = abs(angleDeg) < 5f
  var reach = s * 0.38f
  var head = s * 0.28f
  var hw = s * 0.22f
  // keep a turn arrow inside its own cell (NavMaster rule: never over the neighbouring lane)
  val lateral = abs(sin(rad)).toFloat() * (reach + head) + hw * abs(cos(rad)).toFloat()
  val maxSide = cell * 0.46f
  if (lateral > maxSide) {
    val f = (maxSide / lateral).coerceAtLeast(0.35f)
    reach *= f
    head *= f
    hw *= f.coerceAtLeast(0.6f)
  }
  val cx = w / 2f
  val bottom = h * 0.95f - s * strokeFrac / 2
  val bendY = if (straight) h * 0.40f else h * 0.55f
  val dx = sin(rad).toFloat()
  val dy = -cos(rad).toFloat()
  val tipX = cx + dx * reach
  val tipY = if (straight) h * 0.40f else bendY + dy * reach
  val stroke = Stroke(width = s * strokeFrac, cap = StrokeCap.Round, join = StrokeJoin.Round)
  val shaft = Path().apply {
    moveTo(cx, bottom)
    if (straight) lineTo(cx, tipY) else {
      // the bend rounded, as wide as the room there is
      val r = minOf(s * 0.16f, reach * 0.7f, (bottom - bendY) * 0.6f)
      lineTo(cx, bendY + r)
      quadraticTo(cx, bendY, cx + dx * r, bendY + dy * r)
      lineTo(tipX, tipY)
    }
  }
  drawPath(shaft, color, style = stroke)
  arrowHead(Offset(tipX, tipY), dx, dy, head, hw, color)
}

/**
 * The same arrow laid along a course on the screen (a lane of the junction view, flat on the road):
 * the stem along [centre], as thick at each point as [widths] says (it thins into the distance
 * with the road), a round end at its start and the same head at its end, in the arrow's own
 * proportions to the stem there.
 */
fun DrawScope.drawPathArrow(centre: List<Offset>, widths: List<Float>, color: Color) {
  val n = centre.size
  if (n < 2 || widths.size != n) return
  val left = ArrayList<Offset>(n)
  val right = ArrayList<Offset>(n)
  for (i in 0 until n) {
    val a = centre[maxOf(i - 1, 0)]
    val b = centre[minOf(i + 1, n - 1)]
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
    val hw = widths[i] / 2
    left += Offset(centre[i].x + dy / len * hw, centre[i].y - dx / len * hw)
    right += Offset(centre[i].x - dy / len * hw, centre[i].y + dx / len * hw)
  }
  val stem = Path().apply {
    left.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
    right.asReversed().forEach { lineTo(it.x, it.y) }
    close()
  }
  drawPath(stem, color)
  drawCircle(color, widths[0] / 2, centre[0])
  // the head along the last stretch of the course
  val e = centre[n - 1]
  val q = centre[maxOf(n - 3, 0)]
  val len = kotlin.math.hypot(e.x - q.x, e.y - q.y).coerceAtLeast(1e-3f)
  val w = widths[n - 1]
  arrowHead(e, (e.x - q.x) / len, (e.y - q.y) / len, w * (0.28f / ARROW_STROKE), w * (0.22f / ARROW_STROKE), color)
}

/** Maneuver glyph for the green bar: modifier gives the direction, roundabouts get a ring. */
@Composable
fun ManeuverGlyph(type: String?, modifier: String?, color: Color, mod: Modifier) {
  Canvas(mod) {
    val t = type?.uppercase() ?: ""
    if (t.contains("ROUNDABOUT") || t.contains("ROTARY")) {
      val r = size.minDimension * 0.22f
      val c = Offset(size.width / 2f, size.height * 0.42f)
      drawCircle(color, r, c, style = Stroke(width = size.minDimension * 0.10f))
      drawLine(color, Offset(c.x, size.height * 0.95f), Offset(c.x, c.y + r), strokeWidth = size.minDimension * 0.13f, cap = StrokeCap.Round)
      val a = Math.toRadians(directionAngle(modifier).toDouble() - 90.0)
      val ex = c.x + (cos(a) * r * 1.9f).toFloat()
      val ey = c.y + (sin(a) * r * 1.9f).toFloat()
      drawLine(color, Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat()), Offset(ex, ey),
          strokeWidth = size.minDimension * 0.13f, cap = StrokeCap.Round)
      arrowHead(Offset(ex, ey), cos(a).toFloat(), sin(a).toFloat(), size.minDimension * 0.2f, size.minDimension * 0.16f, color)
    } else if (t.contains("ARRIVE")) {
      val c = Offset(size.width / 2f, size.height * 0.45f)
      drawCircle(color, size.minDimension * 0.30f, c, style = Stroke(width = size.minDimension * 0.10f))
      drawCircle(color, size.minDimension * 0.12f, c)
    } else {
      drawTurnArrow(directionAngle(modifier), color, ARROW_STROKE, size.width * 2)
    }
  }
}
