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

/** Stem up, then a bend towards the direction and an arrow head: the glyph used for lanes. */
fun DrawScope.drawTurnArrow(angleDeg: Float, color: Color, strokeFrac: Float = 0.16f, cell: Float = size.width) {
  val w = size.width
  val h = size.height
  val s = minOf(w, h)
  val rad = Math.toRadians(angleDeg.toDouble())
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
  val bottom = h * 0.95f
  val bendY = if (abs(angleDeg) < 5f) h * 0.40f else h * 0.55f
  val tipX = cx + (sin(rad) * reach).toFloat()
  val tipY = if (abs(angleDeg) < 5f) h * 0.40f else bendY - (cos(rad) * reach).toFloat()
  val stroke = Stroke(width = s * strokeFrac, cap = StrokeCap.Round, join = StrokeJoin.Round)
  val shaft = Path().apply {
    moveTo(cx, bottom)
    lineTo(cx, bendY)
    if (abs(angleDeg) >= 5f) lineTo(tipX, tipY)
  }
  drawPath(shaft, color, style = stroke)
  val dx = sin(rad).toFloat()
  val dy = -cos(rad).toFloat()
  val headPath = Path().apply {
    moveTo(tipX + dx * head, tipY + dy * head)
    lineTo(tipX - dy * hw, tipY + dx * hw)
    lineTo(tipX + dy * hw, tipY - dx * hw)
    close()
  }
  drawPath(headPath, color)
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
      drawCircle(color, size.minDimension * 0.09f, Offset(ex, ey))
    } else if (t.contains("ARRIVE")) {
      val c = Offset(size.width / 2f, size.height * 0.45f)
      drawCircle(color, size.minDimension * 0.30f, c, style = Stroke(width = size.minDimension * 0.10f))
      drawCircle(color, size.minDimension * 0.12f, c)
    } else {
      drawTurnArrow(directionAngle(modifier), color, 0.15f, size.width * 2)
    }
  }
}
