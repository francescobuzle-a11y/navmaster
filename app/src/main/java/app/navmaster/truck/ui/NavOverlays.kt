package app.navmaster.truck.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.limits.RouteLimit
import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.VisualInstruction

val NmGreenTop = Color(0xFF1B8B47)
val NmGreenBottom = Color(0xFF0D5A2C)
val NmPanel = Color(0xF21B222B)
val NmRoute = Color(0xFFC2189A)
val NmRed = Color(0xFFE03131)
val NmAmber = Color(0xFFFFB300)

/**
 * Green bar at the top (NavMaster / Garmin layout): distance to the manoeuvre, the manoeuvre, the
 * road or the text of the sign, and on the right the lane assist with every lane of the road.
 */
@Composable
fun TopManeuverBar(instruction: VisualInstruction?, distanceM: Double?, modifier: Modifier = Modifier) {
  if (instruction == null) return
  val primary = instruction.primaryContent
  val lanes = instruction.subContent?.laneInfo ?: primary.laneInfo
  Row(
      modifier
          .shadow(8.dp, RoundedCornerShape(16.dp))
          .clip(RoundedCornerShape(16.dp))
          .background(Brush.verticalGradient(listOf(NmGreenTop, NmGreenBottom)))
          .heightIn(min = 72.dp)
          .padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (distanceM != null) {
      val d = Fmt.distance(distanceM)
      Row(verticalAlignment = Alignment.Bottom) {
        Text(d.value, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text(" " + d.unit, color = Color(0xCCFFFFFF), fontSize = 18.sp, modifier = Modifier.padding(bottom = 5.dp))
      }
      Spacer(Modifier.width(12.dp))
    }
    ManeuverGlyph(
        type = primary.maneuverType?.name,
        modifier = primary.maneuverModifier?.name,
        color = Color.White,
        mod = Modifier.size(52.dp),
    )
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      if (primary.exitNumbers.isNotEmpty()) {
        Text(
            "Uscita " + primary.exitNumbers.joinToString(" "),
            color = Color(0xFF111111),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(NmAmber).padding(horizontal = 6.dp, vertical = 1.dp),
        )
      }
      Text(
          primary.text,
          color = Color.White,
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
      instruction.secondaryContent?.text?.let {
        Text(it, color = Color(0xDDFFFFFF), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
    if (!lanes.isNullOrEmpty() && lanes.size >= 2) {
      Spacer(Modifier.width(10.dp))
      LaneAssist(lanes)
    }
  }
}

/** Lane assist: one arrow per lane, the lanes to take bright, the others dim. */
@Composable
fun LaneAssist(lanes: List<LaneInfo>) {
  Row(
      Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x4D000000)).padding(horizontal = 6.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    for (lane in lanes.take(8)) {
      val dir = if (lane.active) lane.activeDirection ?: lane.directions.firstOrNull() else lane.directions.firstOrNull()
      Canvas(Modifier.size(width = 22.dp, height = 40.dp)) {
        drawTurnArrow(
            directionAngle(dir),
            if (lane.active) Color.White else Color(0x59FFFFFF),
            if (lane.active) 0.17f else 0.13f,
            size.width,
        )
      }
    }
  }
}

/**
 * Next height / weight / width / length limit or ban on the route. The round sign carries the value;
 * red with "NON PASSI" when this vehicle does not fit — the core safety warning of the project.
 */
@Composable
fun RestrictionBanner(limit: RouteLimit, distanceM: Double, vehicleValue: String?, modifier: Modifier = Modifier) {
  val bg = if (limit.blocking) Color(0xF2B71C1C) else NmPanel
  Row(
      modifier.shadow(6.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(bg).padding(6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    LimitSign(limit, Modifier.size(52.dp))
    Spacer(Modifier.width(10.dp))
    Column(Modifier.padding(end = 12.dp)) {
      Text(
          if (limit.blocking) "NON PASSI · ${limit.label}" else limit.label,
          color = if (limit.blocking) Color.White else Color(0xFFBFC7CC),
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
      )
      Row(verticalAlignment = Alignment.Bottom) {
        Text(
            if (distanceM < 30) "qui" else "tra " + Fmt.distanceText(distanceM),
            color = if (limit.blocking) Color.White else Color(0xFFFF8A80),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        if (limit.blocking && vehicleValue != null) {
          Text("  il mezzo: $vehicleValue", color = Color(0xFFFFE0E0), fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
      }
      limit.conditional?.let { Text(it, color = Color(0xFFFFE082), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
  }
}

@Composable
fun LimitSign(limit: RouteLimit, modifier: Modifier) {
  Box(modifier, contentAlignment = Alignment.Center) {
    Canvas(Modifier.matchParentSize()) {
      val r = size.minDimension / 2f
      drawCircle(Color.White, r)
      drawCircle(NmRed, r - size.minDimension * 0.07f, style = Stroke(width = size.minDimension * 0.14f))
      if (limit.signValue == null) {
        // a ban: the red bar of the "no entry for ..." sign
        drawLine(NmRed, Offset(size.width * 0.25f, size.height * 0.75f), Offset(size.width * 0.75f, size.height * 0.25f), strokeWidth = size.minDimension * 0.12f)
      }
    }
    val v = limit.signValue
    if (v != null) {
      Text(v, color = Color(0xFF111111), fontSize = if (v.length > 4) 12.sp else 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    } else {
      Text(
          when (limit.kind) {
            "hgv" -> "🚚"
            "bus" -> "🚌"
            "motorhome" -> "🚐"
            else -> "!"
          },
          fontSize = 18.sp,
      )
    }
  }
}

/** Speed limit sign of the road and the current speed; both turn red above the limit. */
@Composable
fun SpeedPanel(speedKmh: Int?, limitKmh: Int?, vehicleMaxKmh: Int, modifier: Modifier = Modifier, toleranceKmh: Int = 3) {
  val effective = limitKmh?.let { minOf(it, vehicleMaxKmh) }
  val over = speedKmh != null && effective != null && speedKmh > effective + toleranceKmh
  val blink by rememberInfiniteTransition(label = "over").animateFloat(
      initialValue = 1f,
      targetValue = 0.35f,
      animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
      label = "blink",
  )
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (limitKmh != null) {
      Box(
          Modifier.size(64.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Color.White).border(7.dp, NmRed, CircleShape),
          contentAlignment = Alignment.Center,
      ) {
        Text(limitKmh.toString(), color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
      }
    }
    if (speedKmh != null) {
      Box(
          Modifier.widthIn(min = 64.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(if (over) NmRed.copy(alpha = blink) else NmPanel)
              .padding(horizontal = 8.dp, vertical = 4.dp),
          contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(speedKmh.toString(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
          Text("km/h", color = Color(0xB3FFFFFF), fontSize = 11.sp)
        }
      }
    }
  }
}

/** Slim bottom bar: current road in the middle, arrival time, km and minutes on the right. */
@Composable
fun BottomTripBar(road: String?, remainingM: Double?, remainingS: Double?, modifier: Modifier = Modifier) {
  Row(
      modifier.shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(NmPanel)
          .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        road ?: "",
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    if (remainingS != null && remainingM != null) {
      Column(horizontalAlignment = Alignment.End) {
        Text(Fmt.eta(remainingS), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("${Fmt.distanceText(remainingM)} · ${Fmt.duration(remainingS)}", color = Color(0xB3FFFFFF), fontSize = 13.sp)
      }
    }
  }
}
