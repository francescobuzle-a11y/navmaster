package app.navmaster.truck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.nav.LanePlan
import app.navmaster.truck.routing.RouteAnalysis
import uniffi.ferrostar.VisualInstruction
import kotlin.math.min

/** The lane guidance to show now, with how far the manoeuvre is and from how far it is shown. */
data class LaneGuide(val plan: LanePlan.Plan, val distanceM: Double, val rangeM: Double)

private val FAST = setOf("motorway", "trunk")

/**
 * The lane guidance for the next manoeuvre, or null: from 2 km before a motorway exit, fork or
 * merge, from 400 m before a junction where only some lanes go the right way.
 */
fun laneGuideOf(instruction: VisualInstruction?, distanceM: Double?, a: RouteAnalysis?, traveled: Double): LaneGuide? {
  if (instruction == null || distanceM == null || distanceM < 0) return null
  val p = instruction.primaryContent
  val type = p.maneuverType?.name ?: return null
  val at = traveled + distanceM
  val here = a?.edgeAt(traveled)
  val before = a?.edgeAt((at - 60).coerceAtLeast(0.0))
  val after = a?.edgeAt(at + 120)
  val motorway = here?.roadClass in FAST || before?.roadClass in FAST || after?.roadClass in FAST
  val lanes = (instruction.subContent?.laneInfo ?: p.laneInfo).orEmpty().map { LanePlan.In(it.directions, it.active, it.activeDirection) }
  val out = a?.junctionShapeNear(at, 90.0)?.outLanes?.takeIf { it >= 1 } ?: after?.lanes?.takeIf { it >= 1 }
  val plan = LanePlan.of(lanes, type, p.maneuverModifier?.name ?: "", before?.lanes ?: here?.lanes, out, motorway) ?: return null
  val range = when {
    plan.kind == LanePlan.Kind.JUNCTION -> if (motorway) 1000.0 else 400.0
    motorway || plan.kind == LanePlan.Kind.EXIT -> 2000.0
    else -> 600.0
  }
  if (distanceM > range) return null
  return LaneGuide(plan, distanceM, range)
}

private val LaneRoad = Color(0xFF30343A)
private val LaneTake = Color(0xFF1E88E5)
private val LaneTakeEdge = Color(0xFF90CAF9)

/**
 * The lane guidance bar: on the left what to keep in words ("USCITA A DESTRA" / "Corsia di
 * destra"), on the right the lanes of the road as they are at the junction, the ones to take lit
 * blue with their arrows, a merge lane joining from its side; under it, how close the manoeuvre
 * is. It fits any width: the lanes get narrower, the words wrap.
 */
@Composable
fun LaneStrip(guide: LaneGuide, modifier: Modifier = Modifier) {
  val plan = guide.plan
  Column(
      modifier.shadow(8.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Color(0xF2151B22)),
  ) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val n = plan.lanes.size
      val lanesW = if (n == 0) 0.dp else min(maxWidth.value * 0.62f, n * 46f).dp
      Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(plan.label, color = NmAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (plan.hint.isNotEmpty()) {
            Text(plan.hint, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp)
          }
        }
        if (n > 0) {
          Spacer(Modifier.width(8.dp))
          Canvas(Modifier.width(lanesW).height(52.dp)) { drawLanes(plan) }
        } else {
          // the number of lanes is not known: just the side to keep
          Spacer(Modifier.width(8.dp))
          Canvas(Modifier.width(44.dp).height(48.dp)) {
            drawTurnArrow(if (plan.side < 0) -35f else if (plan.side > 0) 35f else 0f, Color.White, 0.17f, size.width)
          }
        }
      }
    }
    // how close: the bar fills as the manoeuvre comes
    val f = (1.0 - guide.distanceM / guide.rangeM).coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF))) {
      Box(Modifier.fillMaxWidth(f).height(4.dp).background(if (guide.distanceM < 300) NmAmber else LaneTake))
    }
  }
}

/** The lanes seen from above, across the road: edges, dashed lines between lanes, arrows. */
private fun DrawScope.drawLanes(plan: LanePlan.Plan) {
  val lanes = plan.lanes
  val n = lanes.size
  if (n == 0) return
  val w = size.width
  val h = size.height
  // a merge lane is narrower (the slip road joining)
  val weights = lanes.map { if (it.merge) 0.8f else 1f }
  val unit = w / weights.sum()
  drawRect(LaneRoad, Offset.Zero, Size(w, h))
  var x = 0f
  val xs = FloatArray(n + 1)
  for (i in 0 until n) {
    xs[i] = x
    x += weights[i] * unit
  }
  xs[n] = w
  // the lanes to take, lit
  for (i in 0 until n) {
    if (!lanes[i].take) continue
    val inset = unit * 0.07f
    drawRect(LaneTake, Offset(xs[i] + inset, 0f), Size(xs[i + 1] - xs[i] - 2 * inset, h))
    drawRect(LaneTakeEdge, Offset(xs[i] + inset, 0f), Size(xs[i + 1] - xs[i] - 2 * inset, h * 0.06f))
  }
  // lines: solid at the edges, dashed between lanes (the merge lane with short thick dashes)
  val lw = (unit * 0.06f).coerceIn(2f, 5f)
  drawLine(Color.White, Offset(lw / 2, 0f), Offset(lw / 2, h), lw)
  drawLine(Color.White, Offset(w - lw / 2, 0f), Offset(w - lw / 2, h), lw)
  for (i in 1 until n) {
    val mergeLine = lanes[i].merge || lanes[i - 1].merge
    val dash = if (mergeLine) floatArrayOf(h * 0.12f, h * 0.10f) else floatArrayOf(h * 0.22f, h * 0.18f)
    drawLine(Color.White, Offset(xs[i], 0f), Offset(xs[i], h), if (mergeLine) lw * 1.4f else lw * 0.8f,
        pathEffect = PathEffect.dashPathEffect(dash, 0f))
  }
  // an arrow in each lane, white on the lanes to take, faint on the others
  for (i in 0 until n) {
    val cw = xs[i + 1] - xs[i]
    val cell = min(cw * 0.86f, h * 0.9f)
    translate(xs[i] + (cw - cell) / 2, (h - cell) / 2) {
      drawIntoCell(cell, directionAngle(lanes[i].arrow), if (lanes[i].take) Color.White else Color(0x66FFFFFF), cw)
    }
  }
}

private fun DrawScope.drawIntoCell(cell: Float, angle: Float, color: Color, laneW: Float) {
  // drawTurnArrow draws in the current size: a square cell of the lane
  val s = Size(cell, cell)
  drawContext.size.let { old ->
    drawContext.size = s
    drawTurnArrow(angle, color, 0.15f, laneW)
    drawContext.size = old
  }
}
