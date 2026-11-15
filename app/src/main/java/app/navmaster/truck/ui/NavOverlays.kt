package app.navmaster.truck.ui

import app.navmaster.truck.vehicle.SpeedRules

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
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
val NmRoute = Color(0xFF2F7CF6)
val NmRed = Color(0xFFE03131)
val NmAmber = Color(0xFFFFB300)

/**
 * Green bar at the top (NavMaster / Garmin layout): distance to the manoeuvre, the manoeuvre, the
 * road or the text of the sign, and on the right the lane assist with every lane of the road.
 */
@Composable
fun TopManeuverBar(instruction: VisualInstruction?, distanceM: Double?, modifier: Modifier = Modifier, showLanes: Boolean = true, roundabout: RoundaboutShape? = null,
                   /** Far from the manoeuvre: only its sign and the distance, in a small tab (a touch opens it for a while). */
                   compact: Boolean = false, onTap: (() -> Unit)? = null) {
  if (instruction == null) return
  val primary = instruction.primaryContent
  val lanes = instruction.subContent?.laneInfo ?: primary.laneInfo
  if (compact) {
    Row(
        modifier
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(NmGreenTop, NmGreenBottom)))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      val t = primary.maneuverType?.name?.uppercase() ?: ""
      if (roundabout != null && (t.contains("ROUNDABOUT") || t.contains("ROTARY"))) {
        RoundaboutGlyph(roundabout, Color.White, Modifier.size(44.dp))
      } else {
        ManeuverGlyph(type = primary.maneuverType?.name, modifier = primary.maneuverModifier?.name, color = Color.White, mod = Modifier.size(40.dp))
      }
      if (distanceM != null) {
        Spacer(Modifier.width(10.dp))
        val d = Fmt.distance(distanceM)
        Row(verticalAlignment = Alignment.Bottom) {
          Text(d.value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
          Text(" " + d.unit, color = Color(0xCCFFFFFF), fontSize = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
      }
    }
    return
  }
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
    // a roundabout drawn as it really is (its roads, the exit taken), else the glyph of the manoeuvre
    val t = primary.maneuverType?.name?.uppercase() ?: ""
    if (roundabout != null && (t.contains("ROUNDABOUT") || t.contains("ROTARY"))) {
      RoundaboutGlyph(roundabout, Color.White, Modifier.size(60.dp))
    } else {
      ManeuverGlyph(
          type = primary.maneuverType?.name,
          modifier = primary.maneuverModifier?.name,
          color = Color.White,
          mod = Modifier.size(52.dp),
      )
    }
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
          primary.text.trim().trimEnd('.'),
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
    // the lanes only where keeping one matters (motorway junctions, complicated junctions)
    if (showLanes && !lanes.isNullOrEmpty() && lanesMatter(lanes)) {
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
            ARROW_STROKE,
            size.width,
        )
      }
    }
  }
}

/**
 * Next height / weight / width / length limit or ban on the route. The round sign carries the value;
 * amber, with the vehicle's own measure next to it, when the limit is under the vehicle's: a notice
 * to look at the signs, never an alarm (the data of the map can be wrong or old).
 */
@Composable
fun RestrictionBanner(limit: RouteLimit, distanceM: Double, vehicleValue: String?, modifier: Modifier = Modifier) {
  val bg = if (limit.blocking) Color(0xF2B26A00) else NmPanel
  Row(
      modifier.shadow(6.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(bg).padding(6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    LimitSign(limit, Modifier.size(52.dp))
    Spacer(Modifier.width(10.dp))
    Column(Modifier.padding(end = 12.dp)) {
      Text(
          if (limit.blocking) "${limit.label} · verifica la segnaletica" else limit.label,
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
          Text("  il tuo mezzo: $vehicleValue", color = Color(0xFFFFF3E0), fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
      }
      limit.conditional?.let { Text(it, color = Color(0xFFFFE082), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
      limit.check?.let { Text(it, color = if (limit.ver == "dubbio") Color(0xFFFFE082) else Color(0xFFB9F6CA), fontSize = 12.sp, maxLines = 1) }
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
            "low_emission_zone" -> "🌱"
            "truck_control" -> "🚛"
            "speed_bump" -> "⛔"
            "danger" -> "⚠️"
            "ztl" -> "ZTL"
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
        // the limit for this vehicle: on a motorway at 130 a lorry still has its own 80
        Text((effective ?: limitKmh).toString(), color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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

/** Fixed speed camera ahead (or a speed "control zone" where only that may be shown). */
@Composable
fun CameraBanner(camera: RouteLimit, distanceM: Double, zoneOnly: Boolean, modifier: Modifier = Modifier, limit: SpeedRules.Limit? = null) {
  Row(
      modifier.shadow(6.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(NmPanel).padding(6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(if (zoneOnly) NmAmber else Color(0xFF1565C0)), contentAlignment = Alignment.Center) {
      Text(if (zoneOnly) "⚠" else "📷", fontSize = 22.sp)
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.padding(end = 12.dp)) {
      // the limit to respect with this vehicle, and the sign's when it is higher (the same two numbers as the sign at the side of the map)
      val speed = when {
        limit != null && limit.signedAbove != null -> " · ${limit.kmh} km/h (cartello ${limit.signedAbove})"
        limit != null -> " · ${limit.kmh} km/h"
        camera.value > 0 -> " · ${camera.value.toInt()} km/h"
        else -> ""
      }
      Text(if (zoneOnly) "Zona di controllo" else (camera.cameraWhat?.replaceFirstChar { it.uppercase() } ?: "Autovelox") + speed,
          color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      if (!zoneOnly) Text(if (distanceM < 30) "qui" else "tra " + Fmt.distanceText(distanceM), color = Color(0xFF90CAF9), fontSize = 20.sp,
          fontWeight = FontWeight.Bold)
    }
  }
}

/**
 * A section under average speed control (Tutor): before it how far it starts, inside it the
 * driver's average since its start against the limit, and how far it ends.
 */
@Composable
fun SectionBanner(section: RouteLimit, traveled: Double, limitKmh: Int?, avgKmh: Int?, modifier: Modifier = Modifier) {
  val inside = traveled >= section.alongM
  val over = inside && avgKmh != null && limitKmh != null && avgKmh > limitKmh
  Row(
      modifier.shadow(6.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(if (over) Color(0xF2B71C1C) else NmPanel)
          .padding(6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF1565C0)), contentAlignment = Alignment.Center) {
      Text(limitKmh?.toString() ?: "T", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.padding(end = 12.dp)) {
      Text(if (inside) "Tutor · media " + (avgKmh?.let { "$it km/h" } ?: "…") else "Tutor", color = Color.White, fontSize = 14.sp,
          fontWeight = FontWeight.Bold)
      Text(if (inside) "fine tra " + Fmt.distanceText((section.endM - traveled).coerceAtLeast(0.0))
           else "inizia tra " + Fmt.distanceText((section.alongM - traveled).coerceAtLeast(0.0)),
          color = if (over) Color.White else Color(0xFF90CAF9), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/**
 * The speed limit as the road sign, always in sight at the side of the map: the limit to RESPECT
 * (app.navmaster.truck.vehicle.SpeedRules: the lowest of the signs, the law for this vehicle and the
 * vehicle's own maximum), amber inside while over it. When it is not what the signs say it is
 * told so, never left to be guessed: a truck badge on the sign and "per il mezzo" (the law) or
 * "tuo limite" (the profile's own maximum) under it, and the number the signs say in a small sign
 * beside ("cartello 130"). The same words and numbers as the speedometer and the speed cameras.
 */
@Composable
fun SpeedLimitSign(limit: SpeedRules.Limit, speedKmh: Int?, toleranceKmh: Int, modifier: Modifier = Modifier, size: Dp = 72.dp) {
  val over = speedKmh != null && speedKmh > limit.kmh + toleranceKmh
  val why = limit.why
  Column(modifier, horizontalAlignment = Alignment.Start) {
    Box {
      Box(
          Modifier.size(size).shadow(6.dp, CircleShape).clip(CircleShape).background(if (over) NmAmber else Color.White)
              .border(size * 0.11f, Color(0xFFD32F2F), CircleShape)
              .semantics { contentDescription = "Limite da rispettare ${limit.kmh} km/h" },
          contentAlignment = Alignment.Center,
      ) {
        Text("${limit.kmh}", color = Color.Black, fontSize = (size.value * (if (limit.kmh >= 100) 0.32f else 0.38f)).sp, fontWeight = FontWeight.Bold)
      }
      if (why != SpeedRules.Why.SIGNED) TruckBadge(size * 0.46f, Modifier.align(Alignment.BottomEnd).offset(x = size * 0.1f, y = size * 0.02f))
    }
    if (why != SpeedRules.Why.SIGNED) {
      Text(if (why == SpeedRules.Why.LEGAL) "per il mezzo" else "tuo limite", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 7.dp, vertical = 2.dp))
    }
    limit.signedAbove?.let { signed ->
      Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size * 0.5f).clip(CircleShape).background(Color(0xFFECEFF1)).border(size * 0.055f, Color(0xFFD32F2F), CircleShape)
                .semantics { contentDescription = "Cartello $signed km/h" },
            contentAlignment = Alignment.Center,
        ) { Text("$signed", color = Color.Black, fontSize = (size.value * (if (signed >= 100) 0.17f else 0.2f)).sp, fontWeight = FontWeight.Bold) }
        Text("cartello", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp))
      }
    }
  }
}

/** A small round badge with a lorry's silhouette: what a limit for lorries carries on a sign. */
@Composable
fun TruckBadge(size: Dp, modifier: Modifier = Modifier) {
  Canvas(modifier.size(size)) {
    val w = this.size.width
    drawCircle(Color(0xFF0B4EA2), w / 2)
    drawCircle(Color.White, w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.06f))
    // the trailer, the cab with its slanted windscreen, the wheels
    drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.17f, w * 0.30f), androidx.compose.ui.geometry.Size(w * 0.38f, w * 0.30f),
        androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
    val cab = androidx.compose.ui.graphics.Path().apply {
      moveTo(w * 0.58f, w * 0.38f); lineTo(w * 0.72f, w * 0.38f); lineTo(w * 0.84f, w * 0.50f); lineTo(w * 0.84f, w * 0.60f); lineTo(w * 0.58f, w * 0.60f); close()
    }
    drawPath(cab, Color.White)
    for (x in listOf(0.28f, 0.46f, 0.72f)) {
      drawCircle(Color(0xFF0B4EA2), w * 0.075f, androidx.compose.ui.geometry.Offset(w * x, w * 0.66f))
      drawCircle(Color.White, w * 0.06f, androidx.compose.ui.geometry.Offset(w * x, w * 0.66f))
    }
  }
}
