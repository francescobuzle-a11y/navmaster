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
 * The lane guidance for the next manoeuvre, or null (nav.LaneModel: the lanes of the graph, the
 * same ones the voice and the junction view use): from 1.5 km before a motorway exit or fork, from
 * 300 m before a junction in town where only some lanes go the route's way (600 m on fast roads).
 */
fun laneGuideOf(instruction: VisualInstruction?, distanceM: Double?, a: RouteAnalysis?, traveled: Double, lht: Boolean = false): LaneGuide? {
  if (instruction == null || distanceM == null || distanceM < 0 || a == null) return null
  val type = instruction.primaryContent.maneuverType?.name ?: return null
  if (type.contains("ARRIVE") || type.contains("DEPART") || type.contains("ROUNDABOUT") || type.contains("ROTARY")) return null
  val at = traveled + distanceM
  val j = app.navmaster.truck.nav.LaneModel.at(a, at, lht) ?: return null
  val fast = a.edgeAt((at - 40).coerceAtLeast(0.0))?.roadClass in FAST
  val range = when (j.kind) {
    LanePlan.Kind.EXIT, LanePlan.Kind.FORK -> 1500.0
    else -> if (fast) 600.0 else 300.0
  }
  if (distanceM > range) return null
  // at a junction in town: only when the lanes say something
  if (j.kind == LanePlan.Kind.JUNCTION && j.lanes.isEmpty()) return null
  return LaneGuide(j.plan, distanceM, range)
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
fun LaneStrip(guide: LaneGuide, modifier: Modifier = Modifier, vehicleLane: Int? = null) {
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
          // in a lane that does not go the route's way: what to do instead of where to be
          val lanesNoMerge = plan.lanes.filter { !it.merge }
          val wrong = vehicleLane != null && vehicleLane in lanesNoMerge.indices && !lanesNoMerge[vehicleLane].take
          val hint = if (wrong) {
            val nearest = lanesNoMerge.indices.filter { lanesNoMerge[it].take }.minByOrNull { kotlin.math.abs(it - vehicleLane!!) }
            if (nearest == null) plan.hint else "Spostati " + (if (nearest > vehicleLane!!) "a destra" else "a sinistra")
          } else plan.hint
          if (hint.isNotEmpty()) {
            // one line always: the bar keeps its height, nothing under it jumps
            Text(hint, color = if (wrong) NmAmber else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
          }
        }
        if (n > 0) {
          Spacer(Modifier.width(8.dp))
          Canvas(Modifier.width(lanesW).height(60.dp)) { drawLanes(plan, vehicleLane) }
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

/**
 * The lanes seen from above, across the road: edges, dashed lines between lanes, arrows; under them
 * the vehicle (a blue mark) in the lane the satellites put it in, when known.
 */
internal fun DrawScope.drawLanes(plan: LanePlan.Plan, vehicleLane: Int?) {
  val lanes = plan.lanes
  val n = lanes.size
  if (n == 0) return
  val w = size.width
  val carH = if (vehicleLane != null) size.height * 0.16f else 0f
  val h = size.height - carH
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
  // lines: solid at the edges, dashed between lanes (a merge lane and a lane opening for the exit
  // with short thick dashes, as they are painted)
  val lw = (unit * 0.06f).coerceIn(2f, 5f)
  drawLine(Color.White, Offset(lw / 2, 0f), Offset(lw / 2, h), lw)
  drawLine(Color.White, Offset(w - lw / 2, 0f), Offset(w - lw / 2, h), lw)
  for (i in 1 until n) {
    val mergeLine = lanes[i].merge || lanes[i - 1].merge || lanes[i].added != lanes[i - 1].added
    val dash = if (mergeLine) floatArrayOf(h * 0.12f, h * 0.10f) else floatArrayOf(h * 0.22f, h * 0.18f)
    drawLine(Color.White, Offset(xs[i], 0f), Offset(xs[i], h), if (mergeLine) lw * 1.4f else lw * 0.8f,
        pathEffect = PathEffect.dashPathEffect(dash, 0f))
  }
  // the arrows painted on each lane (all of them, the one of the route bright on the lanes to
  // take); lanes known from their number only get no arrows: none are painted on the map
  for (i in 0 until n) {
    if (!lanes[i].painted) continue
    val cw = xs[i + 1] - xs[i]
    val cell = min(cw * 0.86f, h * 0.9f)
    translate(xs[i] + (cw - cell) / 2, (h - cell) / 2) {
      for (ar in lanes[i].arrows.distinct().sortedBy { if (it == lanes[i].arrow && lanes[i].take) 1 else 0 }) {
        val bright = lanes[i].take && ar == lanes[i].arrow
        drawIntoCell(cell, directionAngle(ar), if (bright) Color.White else Color(0x66FFFFFF), cw)
      }
    }
  }
  // the vehicle under its lane (the lanes drawn are the carriageway's, merge lanes aside)
  if (vehicleLane != null) {
    val real = lanes.indices.filter { !lanes[it].merge }
    val idx = real.getOrNull(vehicleLane) ?: return
    val cx = (xs[idx] + xs[idx + 1]) / 2
    val ok = lanes[idx].take
    drawRect(if (ok) LaneTake else Color(0xFFFFB300), Offset(xs[idx] + 2f, h), Size(xs[idx + 1] - xs[idx] - 4f, carH))
    val tri = androidx.compose.ui.graphics.Path().apply {
      moveTo(cx, h + carH * 0.1f); lineTo(cx - carH * 0.55f, h + carH * 0.95f); lineTo(cx + carH * 0.55f, h + carH * 0.95f); close()
    }
    drawPath(tri, Color.White)
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
