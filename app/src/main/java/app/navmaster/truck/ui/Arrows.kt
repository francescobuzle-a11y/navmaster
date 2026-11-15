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
 * The same arrow along a curve: a short stem up, then round a circle for [turnDeg] degrees (right
 * positive, up to a loop of 270°), the same head at its end. Fitted in the current cell.
 */
fun DrawScope.drawCurveArrow(turnDeg: Float, color: Color) {
  val a = turnDeg.coerceIn(-270f, 270f)
  val sgn = if (a >= 0) 1f else -1f
  val total = Math.toRadians(abs(a).toDouble())
  // in units of the circle's radius: the stem's thickness and the head in the arrow's own proportions
  val st = 0.46f
  val headLen = st * 1.75f
  val headHalf = st * 1.375f
  val pts = ArrayList<Offset>()
  pts += Offset(0f, 0.9f)
  val steps = 36
  for (k in 0..steps) {
    val th = total * k / steps
    pts += Offset(sgn * (1f - cos(th).toFloat()), -sin(th).toFloat())
  }
  val tx = sgn * sin(total).toFloat()
  val ty = -cos(total).toFloat()
  val end = pts.last()
  val headPts = listOf(Offset(end.x + tx * headLen, end.y + ty * headLen), Offset(end.x - ty * headHalf, end.y + tx * headHalf),
      Offset(end.x + ty * headHalf, end.y - tx * headHalf))
  val all = pts + headPts
  val minX = all.minOf { it.x } - st / 2; val maxX = all.maxOf { it.x } + st / 2
  val minY = all.minOf { it.y } - st / 2; val maxY = all.maxOf { it.y } + st / 2
  val k = minOf(size.width / (maxX - minX), size.height / (maxY - minY))
  val ox = (size.width - (maxX - minX) * k) / 2 - minX * k
  val oy = (size.height - (maxY - minY) * k) / 2 - minY * k
  fun m(o: Offset) = Offset(ox + o.x * k, oy + o.y * k)
  val path = Path().apply { pts.forEachIndexed { i, o -> val q = m(o); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) } }
  drawPath(path, color, style = Stroke(width = st * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
  arrowHead(m(end), tx, ty, headLen * k, headHalf * k, color)
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
