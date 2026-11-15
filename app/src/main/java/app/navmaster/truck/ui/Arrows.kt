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

/**
 * The shape of the arrow in a [w] by [h] cell, before it is drawn: the stem's points (its start,
 * where the bend begins and ends and the bend's corner, the end where the head starts), its
 * thickness and the head. One place makes it, so what is drawn and what is measured (does it fit
 * the lane?) are the same.
 */
internal class ArrowGeom(
    val start: Offset,
    val bendFrom: Offset,
    val corner: Offset,
    val bendTo: Offset,
    val tip: Offset,
    val curved: Boolean,
    val strokeW: Float,
    val dx: Float,
    val dy: Float,
    val headLen: Float,
    val headHalf: Float,
) {
  /** Points covering everything the arrow paints (the stem's thickness round its line, the head's corners and apex). */
  fun outline(): List<Offset> {
    val line = ArrayList<Offset>()
    line += start
    if (curved) {
      line += bendFrom
      for (k in 1..6) {
        val t = k / 6f; val u = 1 - t
        line += Offset(u * u * bendFrom.x + 2 * u * t * corner.x + t * t * bendTo.x, u * u * bendFrom.y + 2 * u * t * corner.y + t * t * bendTo.y)
      }
    }
    line += tip
    val r = strokeW / 2
    val out = ArrayList<Offset>()
    for (q in line) { out += Offset(q.x - r, q.y); out += Offset(q.x + r, q.y); out += Offset(q.x, q.y - r); out += Offset(q.x, q.y + r) }
    out += Offset(tip.x + dx * headLen, tip.y + dy * headLen)
    out += Offset(tip.x - dy * headHalf, tip.y + dx * headHalf)
    out += Offset(tip.x + dy * headHalf, tip.y - dx * headHalf)
    return out
  }
}

/** The arrow's shape for a manoeuvre of [angleDeg] in a [w] by [h] cell, kept within [cell] across. */
internal fun turnArrowGeom(angleDeg: Float, w: Float, h: Float, strokeFrac: Float, cell: Float): ArrowGeom {
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
  // the bend rounded, as wide as the room there is
  val r = if (straight) 0f else minOf(s * 0.16f, reach * 0.7f, (bottom - bendY) * 0.6f)
  return ArrowGeom(
      start = Offset(cx, bottom), bendFrom = Offset(cx, bendY + r), corner = Offset(cx, bendY), bendTo = Offset(cx + dx * r, bendY + dy * r),
      tip = Offset(tipX, tipY), curved = !straight, strokeW = s * strokeFrac, dx = dx, dy = dy, headLen = head, headHalf = hw)
}

/** Stem up, a soft bend towards the direction and the head: the arrow of a lane, a sign, a manoeuvre. */
fun DrawScope.drawTurnArrow(angleDeg: Float, color: Color, strokeFrac: Float = ARROW_STROKE, cell: Float = size.width) {
  val g = turnArrowGeom(angleDeg, size.width, size.height, strokeFrac, cell)
  val shaft = Path().apply {
    moveTo(g.start.x, g.start.y)
    if (g.curved) {
      lineTo(g.bendFrom.x, g.bendFrom.y)
      quadraticTo(g.corner.x, g.corner.y, g.bendTo.x, g.bendTo.y)
    }
    lineTo(g.tip.x, g.tip.y)
  }
  drawPath(shaft, color, style = Stroke(width = g.strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
  arrowHead(g.tip, g.dx, g.dy, g.headLen, g.headHalf, color)
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
