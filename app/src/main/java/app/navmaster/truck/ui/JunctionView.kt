package app.navmaster.truck.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.routing.EdgeSign
import kotlin.math.max
import kotlin.math.min
import uniffi.ferrostar.LaneInfo

/**
 * What the junction view needs: which way the route leaves ([side] -1 left, 1 right, 0 straight
 * on at a fork), the lanes (from the graph's turn lanes), the direction sign of the branch taken
 * and the number of the road the vehicle is on.
 */
data class JunctionScene(
    val side: Int,
    val lanes: List<LaneInfo>,
    val distanceM: Double,
    val rangeM: Double,
    val sign: EdgeSign?,
    val branchRefs: List<String>,
    val mainRefs: List<String>,
    val motorway: Boolean,
    val country: String?,
    val exit: Boolean,
)

/** Countries whose motorway signs are green (the others use blue). */
private val GREEN_MOTORWAY = setOf("IT", "CH", "SI", "HR", "CZ", "SK", "RS", "BA", "ME", "MK", "GR", "RO", "BG", "AL", "XK", "DK", "SE")

private val SignBlue = Color(0xFF0B4EA2)
private val SignGreen = Color(0xFF00794A)
private val SignYellow = Color(0xFFF5C400)
private val SignWhite = Color(0xFFF7F7F7)

/** Background and text colour of a direction sign, the way the country paints it. */
fun signColors(country: String?, motorway: Boolean): Pair<Color, Color> {
  val c = country?.uppercase()
  return when {
    motorway -> (if (c in GREEN_MOTORWAY) SignGreen else SignBlue) to Color.White
    c == "DE" -> SignYellow to Color.Black
    c == "FR" || c == "GB" || c == "PL" || c == "IE" -> SignGreen to Color.White
    c in GREEN_MOTORWAY -> SignBlue to Color.White
    else -> SignBlue to Color.White
  }
}

/** The small plate of a road number, coloured like on the signs ("A1" green in Italy, "E45" green, "SS16" blue). */
@Composable
fun RefPlate(ref: String, country: String?, big: Boolean = false) {
  val r = ref.uppercase()
  val c = country?.uppercase()
  val (bg, fg) = when {
    r.startsWith("E") && r.drop(1).firstOrNull()?.isDigit() == true -> SignGreen to Color.White
    r.startsWith("A") && r.drop(1).firstOrNull()?.isDigit() == true ->
      (if (c == "DE" || c == "FR" || c == "AT" || c == "ES" || c == "PT" || c == "NL" || c == "BE" || c == "LU" || c == "PL") SignBlue
      else if (c in GREEN_MOTORWAY) SignGreen else SignBlue) to Color.White
    c == "DE" && r.startsWith("B") -> SignYellow to Color.Black
    c == "FR" && (r.startsWith("N") || r.startsWith("D")) -> (if (r.startsWith("N")) Color(0xFFD7263D) else SignYellow) to
        (if (r.startsWith("N")) Color.White else Color.Black)
    else -> SignBlue to Color.White
  }
  Text(
      r, color = fg, fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).border(1.5.dp, fg.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
          .padding(horizontal = 6.dp, vertical = 1.dp),
  )
}

/**
 * The junction view, as on the dedicated truck navigators: the road seen from the driver's seat,
 * the lanes with the ones to take lit up and an arrow on them, and above them the direction signs
 * with the real road numbers and towns. The fork comes closer as the vehicle does, smoothly.
 */
@Composable
fun JunctionView(scene: JunctionScene, night: Boolean, modifier: Modifier = Modifier) {
  val target = (1.0 - (scene.distanceM / scene.rangeM)).coerceIn(0.0, 1.0).toFloat()
  val progress by animateFloatAsState(target, tween(900), label = "junction")
  Column(
      modifier.shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0C1117)),
  ) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (scene.sign != null || scene.mainRefs.isNotEmpty()) 250.dp else 190.dp)) {
      Canvas(Modifier.fillMaxSize()) { drawRoad(scene, progress, night) }
      // the signs, on a gantry above the road
      Row(
          Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Top,
      ) {
        val (mainBg, mainFg) = signColors(scene.country, scene.motorway)
        val branchMotorway = scene.branchRefs.any { it.uppercase().let { r -> (r.startsWith("A") || r.startsWith("E")) && r.getOrNull(1)?.isDigit() == true } } ||
            (scene.motorway && !scene.exit)
        val (brBg, brFg) = signColors(scene.country, branchMotorway)
        val main: @Composable (Modifier) -> Unit = { m ->
          if (scene.mainRefs.isNotEmpty() || scene.side != 0) {
            SignPanel(m, mainBg, mainFg, arrow = 0f, exitNumber = null, refs = scene.mainRefs, towns = emptyList(),
                taken = scene.side == 0 && scene.exit.not(), country = scene.country)
          }
        }
        val branch: @Composable (Modifier) -> Unit = { m ->
          val sign = scene.sign
          SignPanel(m, brBg, brFg, arrow = if (scene.side < 0) -45f else if (scene.side > 0) 45f else 0f,
              exitNumber = sign?.exitNumbers?.firstOrNull(),
              refs = (sign?.branches.orEmpty() + scene.branchRefs).distinct().take(3),
              towns = (sign?.towards.orEmpty() + sign?.exitNames.orEmpty()).distinct().take(3),
              taken = true, country = scene.country)
        }
        // the panels in the same order as the roads: the branch on its own side
        if (scene.side < 0) {
          branch(Modifier.weight(1.3f))
          main(Modifier.weight(1f))
        } else {
          main(Modifier.weight(1f))
          branch(Modifier.weight(1.3f))
        }
      }
      // how far: a bar that empties as the junction comes, and the metres
      val left = scene.distanceM
      Column(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(14.dp).height(70.dp).clip(RoundedCornerShape(7.dp)).background(Color(0x66000000))) {
          Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height((70 * (1f - progress)).dp).background(Color.White))
        }
        Spacer(Modifier.height(4.dp))
        Text(Fmt.distanceText(left), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 1.dp))
      }
    }
  }
}

/** One panel of the gantry: arrow, exit number, road numbers, towns (as written on the sign). */
@Composable
private fun SignPanel(
    modifier: Modifier, bg: Color, fg: Color, arrow: Float, exitNumber: String?, refs: List<String>, towns: List<String>,
    taken: Boolean, country: String?,
) {
  Column(
      modifier.alpha(if (taken) 1f else 0.55f).shadow(if (taken) 6.dp else 0.dp, RoundedCornerShape(6.dp))
          .clip(RoundedCornerShape(6.dp)).background(bg).border(2.dp, fg, RoundedCornerShape(6.dp))
          .padding(horizontal = 8.dp, vertical = 6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Canvas(Modifier.width(26.dp).height(26.dp)) { drawTurnArrow(arrow, fg, 0.16f) }
      Spacer(Modifier.width(6.dp))
      if (exitNumber != null) {
        Text("USCITA $exitNumber", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(SignYellow).padding(horizontal = 4.dp))
        Spacer(Modifier.width(6.dp))
      }
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (r in refs.take(3)) RefPlate(r, country) }
    }
    for (t in towns.take(3)) {
      Text(t, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * The road in perspective. The carriageway goes to a vanishing point; the branch leaves on its side
 * and bends away. [p] (0 far .. 1 at the junction) moves the split point towards the viewer.
 */
private fun DrawScope.drawRoad(scene: JunctionScene, p: Float, night: Boolean) {
  val w = size.width
  val h = size.height
  val horizon = h * 0.42f
  // sky and ground
  drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF0E1726), Color(0xFF2A3A52)) else listOf(Color(0xFF6FA8DC), Color(0xFFE3EEF6)),
      0f, horizon), size = size.copy(height = horizon))
  drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF22301F), Color(0xFF121A11)) else listOf(Color(0xFF9DB08F), Color(0xFF6F8465)),
      horizon, h), topLeft = Offset(0f, horizon), size = size.copy(height = h - horizon))

  val asphalt = if (night) Color(0xFF3B4047) else Color(0xFF5A6068)
  val marking = if (night) Color(0xFFCFD3D8) else Color.White
  val lanes = scene.lanes.take(8)
  val n = max(lanes.size, 2)
  val side = if (scene.side == 0) 1 else scene.side
  val bottomW = w * 0.92f
  val cx = w / 2f
  val bl = cx - bottomW / 2f
  val br = cx + bottomW / 2f
  val laneW = bottomW / n
  // lanes that go to the branch: the active ones on the branch side, at least one
  val active = lanes.mapIndexedNotNull { i, l -> if (l.active) i else null }
  val branchLanes = if (scene.side == 0) 0 else
    max(1, lanes.indices.count { i -> lanes[i].directions.any { d -> if (side > 0) "right" in d else "left" in d } }.coerceAtMost(n - 1))
  val vpMain = cx - side * w * 0.08f
  val vpBranch = cx + side * w * 0.42f
  val top = w * 0.035f
  // where the branch separates: far away at first, under the vehicle at the end
  val splitY = horizon + (h - horizon) * (0.12f + 0.80f * p)
  fun xAt(xBottom: Float, xTop: Float, y: Float): Float = xBottom + (xTop - xBottom) * ((h - y) / (h - horizon))

  // main carriageway
  val main = Path().apply {
    moveTo(bl, h); lineTo(br, h); lineTo(vpMain + top, horizon); lineTo(vpMain - top, horizon); close()
  }
  drawPath(main, asphalt)
  // branch: starts at the split, on the branch side, then bends away to its own vanishing point
  val sideEdgeBottom = if (side > 0) br else bl
  val sideEdgeTop = vpMain + side * top
  val splitX = xAt(sideEdgeBottom, sideEdgeTop, splitY)
  val laneTopW = (2 * top) / n
  val branchW = laneW * branchLanes.coerceAtLeast(1)
  val branch = Path().apply {
    val innerBottomX = sideEdgeBottom - side * branchW
    moveTo(xAt(innerBottomX, sideEdgeTop - side * laneTopW * branchLanes, splitY), splitY)
    quadraticTo(splitX + side * w * 0.06f, splitY - (splitY - horizon) * 0.35f, vpBranch - side * top * 0.5f, horizon)
    lineTo(vpBranch + side * top * 0.5f, horizon)
    quadraticTo(splitX + side * w * 0.28f, splitY - (splitY - horizon) * 0.10f, splitX + side * w * 0.10f, min(h, splitY + (h - splitY) * 0.9f))
    lineTo(sideEdgeBottom + side * w * 0.2f, h)
    lineTo(sideEdgeBottom, h)
    close()
  }
  if (scene.side != 0 || scene.exit) drawPath(branch, asphalt)

  // lane markings
  for (i in 0..n) {
    val xb = bl + i * laneW
    val xt = vpMain - top + i * (2 * top / n)
    val edge = i == 0 || i == n
    drawLine(marking, Offset(xb, h), Offset(xt, horizon), strokeWidth = if (edge) 5f else 3.5f,
        pathEffect = if (edge) null else PathEffect.dashPathEffect(floatArrayOf(34f, 26f)))
  }

  // the route: a coloured band on the lanes to take, bending into the branch
  val take = if (active.isNotEmpty()) active else if (scene.side > 0) listOf(n - 1) else if (scene.side < 0) listOf(0) else listOf(n / 2)
  val first = take.minOrNull() ?: 0
  val last = take.maxOrNull() ?: first
  val x0 = bl + first * laneW + laneW * 0.18f
  val x1 = bl + (last + 1) * laneW - laneW * 0.18f
  val routeColor = NmRoute.copy(alpha = 0.62f)
  val band = Path().apply {
    moveTo(x0, h); lineTo(x1, h)
    if (scene.side == 0) {
      lineTo(xAt(x1, vpMain - top + (last + 1) * laneTopW, splitY), splitY)
      lineTo(vpMain, horizon); lineTo(xAt(x0, vpMain - top + first * laneTopW, splitY), splitY)
    } else {
      val ex = xAt(if (side > 0) x1 else x0, sideEdgeTop, splitY)
      val ix = xAt(if (side > 0) x0 else x1, vpMain - top + (if (side > 0) first else last + 1) * laneTopW, splitY)
      if (side > 0) {
        lineTo(ex, splitY)
        quadraticTo(splitX + side * w * 0.20f, splitY - (splitY - horizon) * 0.2f, vpBranch, horizon)
        quadraticTo(splitX + side * w * 0.02f, splitY - (splitY - horizon) * 0.4f, ix, splitY)
      } else {
        lineTo(ix, splitY)
        quadraticTo(splitX + side * w * 0.02f, splitY - (splitY - horizon) * 0.4f, vpBranch, horizon)
        quadraticTo(splitX + side * w * 0.20f, splitY - (splitY - horizon) * 0.2f, ex, splitY)
      }
    }
    close()
  }
  drawPath(band, routeColor)

  // big arrow on the lanes to take
  val ax = (x0 + x1) / 2f
  val ay = h * 0.93f
  val tipY = splitY + (h - splitY) * 0.15f
  val arrow = Path().apply {
    moveTo(ax, ay)
    if (scene.side == 0) lineTo(ax, tipY)
    else quadraticTo(ax, (ay + tipY) / 2f, ax + side * w * 0.10f, tipY)
  }
  val stroke = Stroke(width = w * 0.035f, cap = StrokeCap.Round, join = StrokeJoin.Round)
  drawPath(arrow, Color(0x55000000), style = Stroke(width = w * 0.05f, cap = StrokeCap.Round, join = StrokeJoin.Round))
  drawPath(arrow, Color.White, style = stroke)
  // head
  val hx = if (scene.side == 0) ax else ax + side * w * 0.10f
  val headDir = if (scene.side == 0) Offset(0f, -1f) else Offset(side * 0.8f, -0.6f)
  val hs = w * 0.05f
  val nx = -headDir.y
  val ny = headDir.x
  val head = Path().apply {
    moveTo(hx + headDir.x * hs, tipY + headDir.y * hs)
    lineTo(hx + nx * hs * 0.8f, tipY + ny * hs * 0.8f)
    lineTo(hx - nx * hs * 0.8f, tipY - ny * hs * 0.8f)
    close()
  }
  drawPath(head, Color.White)

  // lane arrows at the bottom, lit for the lanes to take
  for ((i, l) in lanes.withIndex()) {
    val dir = if (l.active) l.activeDirection ?: l.directions.firstOrNull() else l.directions.firstOrNull()
    val cxl = bl + (i + 0.5f) * laneW
    val cellW = min(laneW * 0.5f, w * 0.06f)
    shifted(cxl - cellW / 2f, h - cellW * 1.9f) {
      drawIntoLane(directionAngle(dir), if (l.active) Color.White else Color(0x66FFFFFF), cellW)
    }
  }
}

private inline fun DrawScope.shifted(x: Float, y: Float, block: DrawScope.() -> Unit) {
  drawContext.transform.translate(x, y)
  block()
  drawContext.transform.translate(-x, -y)
}

private fun DrawScope.drawIntoLane(angle: Float, color: Color, cell: Float) {
  // a small arrow of cell x 1.6 cell drawn at the current origin
  val s = cell
  val rad = Math.toRadians(angle.toDouble())
  val cx = s / 2f
  val bottom = s * 1.6f
  val bend = s * 0.8f
  val tipX = cx + (kotlin.math.sin(rad) * s * 0.45f).toFloat()
  val tipY = bend - (kotlin.math.cos(rad) * s * 0.45f).toFloat()
  val path = Path().apply { moveTo(cx, bottom); lineTo(cx, bend); lineTo(tipX, tipY) }
  drawPath(path, color, style = Stroke(width = s * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
  drawCircle(color, s * 0.12f, Offset(tipX, tipY))
}

/**
 * When to show the junction view: exits and forks (on motorways from 1 km, elsewhere from 350 m),
 * and turns where only some of three or more lanes go the right way. Null the rest of the time,
 * so the map stays free.
 */
fun junctionSceneOf(
    instruction: uniffi.ferrostar.VisualInstruction?,
    distanceM: Double?,
    a: app.navmaster.truck.routing.RouteAnalysis?,
    traveled: Double,
): JunctionScene? {
  if (instruction == null || distanceM == null || distanceM < 0) return null
  val p = instruction.primaryContent
  val type = p.maneuverType?.name?.uppercase()?.replace("_", "") ?: ""
  val mod = p.maneuverModifier?.name?.uppercase() ?: ""
  if (type.contains("ARRIVE") || type.contains("DEPART") || type.contains("ROUNDABOUT") || type.contains("ROTARY")) return null
  val side = when {
    "RIGHT" in mod -> 1
    "LEFT" in mod -> -1
    else -> 0
  }
  val lanes = instruction.subContent?.laneInfo ?: p.laneInfo ?: emptyList()
  val here = a?.edgeAt(traveled)
  val motorway = here?.roadClass in setOf("motorway", "trunk")
  val exitLike = type.contains("OFFRAMP") || type.contains("FORK") || (type.contains("ONRAMP") && side != 0)
  val lanesMatter = lanes.size >= 3 && lanes.count { it.active } in 1 until lanes.size
  if (!exitLike && !lanesMatter) return null
  val range = if (motorway) 1000.0 else 350.0
  if (distanceM > range) return null
  val at = traveled + distanceM
  val sign = a?.signNear(at)
  val after = a?.edgeAt(at + 80)
  return JunctionScene(
      side = side,
      lanes = lanes,
      distanceM = distanceM,
      rangeM = range,
      sign = sign?.second,
      branchRefs = (sign?.first?.refs ?: emptyList()) + (after?.refs ?: emptyList()),
      mainRefs = here?.refs ?: emptyList(),
      motorway = motorway,
      country = here?.country ?: after?.country,
      exit = type.contains("OFFRAMP") || type.contains("FORK"),
  )
}
