package app.navmaster.truck.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
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
    /** For the live view: the route, where the vehicle is and where the manoeuvre is (metres). */
    val analysis: app.navmaster.truck.routing.RouteAnalysis? = null,
    val traveledM: Double = 0.0,
    val maneuverAtM: Double = 0.0,
    /** An ordinary turn or a roundabout in town (no gantry, a smaller view). */
    val turn: Boolean = false,
    val roundabout: Boolean = false,
    /** Sign of the road not taken at this junction (OSM destination tags): where the other way goes. */
    val otherSign: EdgeSign? = null,
    /** The signs over the lanes, left to right (OSM destination:lanes), when mapped. */
    val laneSigns: List<app.navmaster.truck.routing.LaneDest>? = null,
    /** Which of those lanes the route takes (same size as [laneSigns]), when known. */
    val laneSignsActive: List<Boolean>? = null,
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

/** The colour of a sign as mapped in OSM (destination:colour, first value). */
fun osmSignColors(colour: String): Pair<Color, Color>? = when (colour.substringBefore(';').trim().lowercase()) {
  "green" -> SignGreen to Color.White
  "blue" -> SignBlue to Color.White
  "white" -> SignWhite to Color.Black
  "yellow" -> SignYellow to Color.Black
  "brown" -> Color(0xFF6B3E1E) to Color.White
  else -> null
}

/** The signs at night: a little darker and the white less dazzling, as seen in the headlights. */
private fun nightSign(c: Color, night: Boolean): Color =
    if (!night) c else if (c == Color.White || c == SignWhite) Color(0xFFDCDFE3) else androidx.compose.ui.graphics.lerp(c, Color.Black, 0.25f)

/** A sign of a road not taken: still solid (the gantry must not show through it), but faded. */
private fun dimSign(c: Color, taken: Boolean, night: Boolean): Color =
    if (taken) c else androidx.compose.ui.graphics.lerp(c, if (night) Color(0xFF1C232B) else Color(0xFF8A96A3), 0.45f)

/** The small plate of a road number, coloured like on the signs ("A1" green in Italy, "E45" green, "SS16" blue). */
@Composable
fun RefPlate(ref: String, country: String?, big: Boolean = false, night: Boolean = false) {
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
      r, color = nightSign(fg, night), fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(nightSign(bg, night))
          .border(1.5.dp, nightSign(fg, night).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
          .padding(horizontal = 6.dp, vertical = 1.dp),
  )
}

/**
 * The junction view, as on the dedicated truck navigators: the road seen from the driver's seat,
 * the lanes with the ones to take lit up and an arrow on them, and above them the direction signs
 * with the real road numbers and towns. The fork comes closer as the vehicle does, smoothly.
 */
@Composable
fun JunctionView(scene: JunctionScene, night: Boolean, modifier: Modifier = Modifier, fill: Boolean = false) {
  // the driver can close the panel for this manoeuvre (×, top left, as on the reference device)
  var closed by androidx.compose.runtime.remember((scene.maneuverAtM / 10).toLong()) { androidx.compose.runtime.mutableStateOf(false) }
  if (closed) return
  // far from the junction the lane guidance, close to it the junction view with the signs
  val near = scene.analysis == null || scene.turn || scene.roundabout ||
      scene.distanceM < (if (scene.motorway) 400.0 else 160.0)
  val target = (1.0 - (scene.distanceM / scene.rangeM)).coerceIn(0.0, 1.0).toFloat()
  val progress by animateFloatAsState(target, tween(1000, easing = androidx.compose.animation.core.LinearEasing), label = "junction")
  // the position glides between two GPS fixes (one a second), so the view moves continuously
  val smooth by animateFloatAsState(scene.traveledM.toFloat(), tween(1000, easing = androidx.compose.animation.core.LinearEasing), label = "pos")
  val laneGantry = near && !scene.turn && scene.laneSigns != null
  val hasSigns = near && (laneGantry || scene.sign != null || scene.otherSign != null || (!scene.turn && scene.mainRefs.isNotEmpty()))
  Column(
      modifier.shadow(10.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0C1117)),
  ) {
    BoxWithConstraints(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(if (hasSigns) 280.dp else 240.dp)) {
      val signsPx = with(androidx.compose.ui.platform.LocalDensity.current) { (if (hasSigns) 96.dp else 0.dp).toPx() }
      Canvas(Modifier.fillMaxSize()) {
        val a = scene.analysis
        if (a != null) drawGarmin(scene, a, smooth.toDouble(), night, near, signsPx) else drawRoad(scene, progress, night)
        // the gantry the signs hang on: posts at the sides of the road, a lattice beam across
        if (hasSigns) drawGantry(signsPx, night)
      }
      Text("×", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
              .clickable { closed = true }.padding(horizontal = 10.dp, vertical = 0.dp))
      // the signs over the lanes (destination:lanes): one panel over each group of lanes
      if (hasSigns && laneGantry) LaneGantry(scene, night,
          Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 50.dp, end = 14.dp, top = 8.dp, bottom = 8.dp))
      // the signs, on a gantry above the road
      else if (hasSigns) Row(
          Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 50.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.Top,
      ) {
        val (mainBg, mainFg) = signColors(scene.country, scene.motorway)
        val branchMotorway = scene.branchRefs.any { it.uppercase().let { r -> (r.startsWith("A") || r.startsWith("E")) && r.getOrNull(1)?.isDigit() == true } } ||
            (scene.motorway && !scene.exit)
        val (brBg, brFg) = signColors(scene.country, branchMotorway || scene.motorway)
        val main: @Composable (Modifier) -> Unit = { m ->
          val o = scene.otherSign
          // only a panel with something written on it (an empty blue board says nothing)
          if (scene.mainRefs.isNotEmpty() || o?.branches?.isNotEmpty() == true || o?.towards?.isNotEmpty() == true) {
            val (oBg, oFg) = o?.colour?.let { osmSignColors(it) } ?: (mainBg to mainFg)
            SignPanel(m, oBg, oFg, arrow = 0f, exitNumber = null,
                refs = ((o?.branches ?: emptyList()) + scene.mainRefs).distinct().take(3),
                towns = o?.towards?.take(2) ?: emptyList(),
                taken = scene.side == 0 && scene.exit.not(), country = scene.country, night = night)
          }
        }
        val branch: @Composable (Modifier) -> Unit = { m ->
          val sign = scene.sign
          val (sBg, sFg) = sign?.colour?.let { osmSignColors(it) } ?: (brBg to brFg)
          SignPanel(m, sBg, sFg, arrow = if (scene.side < 0) -45f else if (scene.side > 0) 45f else 0f,
              exitNumber = sign?.exitNumbers?.firstOrNull(),
              refs = (sign?.branches.orEmpty() + scene.branchRefs).distinct().take(3),
              towns = (sign?.towards.orEmpty() + sign?.exitNames.orEmpty()).distinct().take(3),
              taken = true, country = scene.country, night = night)
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
    }
  }
}

/** One panel of the gantry: arrow, exit number, road numbers, towns (as written on the sign). */
@Composable
private fun SignPanel(
    modifier: Modifier, bg: Color, fg: Color, arrow: Float, exitNumber: String?, refs: List<String>, towns: List<String>,
    taken: Boolean, country: String?, night: Boolean = false,
) {
  val bg = dimSign(nightSign(bg, night), taken, night)
  val fg = dimSign(nightSign(fg, night), taken, night)
  Column(
      modifier.shadow(if (taken) 6.dp else 0.dp, RoundedCornerShape(6.dp))
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
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (r in refs.take(3)) RefPlate(r, country, night = night) }
    }
    for (t in towns.take(3)) {
      Text(t, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * The gantry over the lanes, as on the motorways before a split: one panel over each group of lanes
 * going to the same place, a down arrow over every lane, the panels of the lanes to take lit.
 */
@Composable
private fun LaneGantry(scene: JunctionScene, night: Boolean, modifier: Modifier) {
  val lanes = scene.laneSigns ?: return
  val active = scene.laneSignsActive
  // consecutive lanes with the same destinations share one panel
  val groups = mutableListOf<MutableList<Int>>()
  for (i in lanes.indices) {
    val g = groups.lastOrNull()
    if (g != null && lanes[g.first()].samePanel(lanes[i])) g += i else groups += mutableListOf(i)
  }
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
    for (g in groups) {
      val d = lanes[g.first()]
      val motorwayRef = d.refs.any { it.uppercase().let { r -> (r.startsWith("A") || r.startsWith("E")) && r.getOrNull(1)?.isDigit() == true } }
      val (bg0, fg0) = d.colour?.let { osmSignColors(it) } ?: signColors(scene.country, motorwayRef)
      val taken = active == null || g.any { active.getOrNull(it) == true }
      val bg = dimSign(nightSign(bg0, night), taken, night)
      val fg = dimSign(nightSign(fg0, night), taken, night)
      Column(
          Modifier.weight(g.size.toFloat()).shadow(if (taken) 6.dp else 0.dp, RoundedCornerShape(5.dp))
              .clip(RoundedCornerShape(5.dp)).background(bg).border(2.dp, fg, RoundedCornerShape(5.dp))
              .padding(horizontal = 6.dp, vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        if (d.refs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
          for (r in d.refs.take(2)) RefPlate(r, scene.country, night = night)
        }
        for (t in d.towns.take(2)) {
          Text(t, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // one arrow over each lane of the group, pointing down on it
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
          for (i in g) {
            val on = active?.getOrNull(i) ?: true
            Canvas(Modifier.width(16.dp).height(18.dp)) { drawDownArrow(if (on) fg else fg.copy(alpha = 0.45f)) }
          }
        }
      }
    }
  }
}

/** The arrow of an overhead lane sign: a short shaft and a head pointing down. */
private fun DrawScope.drawDownArrow(color: Color) {
  val w = size.width
  val h = size.height
  drawLine(color, Offset(w / 2, 0f), Offset(w / 2, h * 0.55f), strokeWidth = w * 0.22f)
  drawPath(Path().apply { moveTo(w * 0.1f, h * 0.45f); lineTo(w * 0.9f, h * 0.45f); lineTo(w / 2, h); close() }, color)
}

/**
 * The gantry drawn in the scene: two posts standing beyond the road edges and a lattice beam across
 * at the height of the signs (the panels hang in front of it). Grey steel by day, darker at night.
 */
private fun DrawScope.drawGantry(signsSpace: Float, night: Boolean) {
  val w = size.width
  val h = size.height
  val steel = if (night) Color(0xFF454B53) else Color(0xFF8B9198)
  val shade = if (night) Color(0xFF2C3137) else Color(0xFF666C73)
  val y1 = signsSpace * 0.20f
  val y2 = signsSpace * 0.46f
  val postW = w * 0.024f
  val left = w * 0.035f
  val right = w - w * 0.03f
  // the posts stand on the ground, just below the horizon of the junction view
  val foot = max(h * 0.40f, signsSpace + h * 0.06f).coerceAtMost(h * 0.55f) + h * 0.015f
  for (x in listOf(left, right)) {
    drawRect(shade, topLeft = Offset(x - postW / 2 + postW * 0.35f, y1), size = androidx.compose.ui.geometry.Size(postW * 0.65f, foot - y1))
    drawRect(steel, topLeft = Offset(x - postW / 2, y1), size = androidx.compose.ui.geometry.Size(postW * 0.65f, foot - y1))
  }
  val sw = w * 0.006f
  drawLine(steel, Offset(left, y1), Offset(right, y1), strokeWidth = sw * 1.4f)
  drawLine(steel, Offset(left, y2), Offset(right, y2), strokeWidth = sw * 1.4f)
  // the lattice between the two chords
  val stepX = (y2 - y1) * 0.9f
  var x = left
  var up = true
  while (x + stepX <= right) {
    drawLine(shade, Offset(x, if (up) y2 else y1), Offset(x + stepX, if (up) y1 else y2), strokeWidth = sw)
    x += stepX
    up = !up
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

/** Only some of the lanes go the right way: the driver has to keep the right one. */
fun lanesMatter(lanes: List<uniffi.ferrostar.LaneInfo>?): Boolean =
    lanes != null && lanes.size >= 2 && lanes.count { it.active } in 1 until lanes.size

private val MOTORWAY_CLASSES = setOf("motorway", "trunk")
private val MINOR_CLASSES = setOf("residential", "service", "living_street", "unclassified", "service_other")

/**
 * When to show the junction view and the lane guidance (the driver's choice of 26/09): only
 * - motorway junctions: exits, forks and slip roads on a motorway (or leading onto one), from 1 km;
 * - complicated junctions of multi-lane roads, where only some lanes lead the right way, from 350 m.
 * Ordinary turns and roundabouts do not open it: the map stays free. Null the rest of the time.
 */

fun junctionSceneOf(
    instruction: uniffi.ferrostar.VisualInstruction?,
    distanceM: Double?,
    a: app.navmaster.truck.routing.RouteAnalysis?,
    traveled: Double,
    countryFallback: String? = null,
): JunctionScene? {
  if (instruction == null || distanceM == null || distanceM < 0) return null
  val p = instruction.primaryContent
  val type = p.maneuverType?.name?.uppercase()?.replace("_", "") ?: ""
  val mod = p.maneuverModifier?.name?.uppercase() ?: ""
  if (type.contains("ARRIVE") || type.contains("DEPART")) return null
  val roundabout = type.contains("ROUNDABOUT") || type.contains("ROTARY")
  val side = when {
    "RIGHT" in mod -> 1
    "LEFT" in mod -> -1
    else -> 0
  }
  val lanes = instruction.subContent?.laneInfo ?: p.laneInfo ?: emptyList()
  val here = a?.edgeAt(traveled)
  val at = traveled + distanceM
  val after = a?.edgeAt(at + 80)
  val motorway = here?.roadClass in MOTORWAY_CLASSES
  val exitLike = type.contains("OFFRAMP") || type.contains("FORK") || (type.contains("ONRAMP") && side != 0)
  // a motorway junction: leaving, splitting or joining a motorway (the analysis missing: trust the
  // instruction, an exit or a fork is almost always one)
  val motorwayJunction = exitLike && (a == null || motorway || after?.roadClass in MOTORWAY_CLASSES ||
      (here?.isRamp == true && (after?.isRamp == true || after?.roadClass in MOTORWAY_CLASSES)))
  // a complicated junction: a road with more lanes where only some go the right way
  val multiLane = (here?.lanes ?: 0) >= 2 || lanes.size >= 3
  val complex = lanesMatter(lanes) && multiLane && here?.roadClass !in MINOR_CLASSES && !roundabout
  if (!motorwayJunction && !complex) return null
  val turnLike = false
  val range = if (motorway || motorwayJunction) 1000.0 else 350.0
  if (distanceM > range) return null
  val sign = a?.signNear(at)
  // the signs mapped in OSM at this junction: the road taken and the one left aside
  val osm = a?.osmSignNear(at)
  val other = osm?.others?.let { o -> o.firstOrNull { it.side == -side && side != 0 } ?: o.firstOrNull { it.side != side } ?: o.firstOrNull() }?.sign
  // without the sign data of the graph, what the instruction says is the sign itself ("Riccione",
  // "A1 / Roma / Firenze"): the road numbers go on plates, the rest are the towns
  val parts = (listOf(p.text) + listOfNotNull(instruction.secondaryContent?.text))
      .flatMap { it.split('/', ',', ';', '·') }.map { it.trim() }.filter { it.isNotEmpty() }
  val refRe = Regex("^[A-Z]{1,3}[ -]?\\d{1,4}[a-z]?$")
  val fallback = app.navmaster.truck.routing.EdgeSign(
      exitNumbers = p.exitNumbers,
      branches = parts.filter { refRe.matches(it) },
      towards = parts.filterNot { refRe.matches(it) },
      exitNames = emptyList(),
  ).takeIf { !it.isEmpty }
  // the signs over the lanes (destination:lanes) and which of those lanes the route takes: the
  // lanes of the graph when they are as many, else the lanes whose sign says where the route goes
  val laneDest = a?.laneSignsNear(at)?.lanes?.takeIf { l -> l.size >= 2 && l.any { !it.isEmpty } }
  val takenSign = sign?.second ?: osm?.taken ?: fallback
  val laneActive = laneDest?.let { ld ->
    if (lanes.size == ld.size && lanes.any { it.active } && !lanes.all { it.active }) lanes.map { it.active }
    else {
      fun norm(t: String) = t.lowercase().replace(" ", "").replace("-", "")
      val want = (takenSign?.towards.orEmpty() + takenSign?.branches.orEmpty() + parts).map { norm(it) }.filter { it.length >= 2 }.toSet()
      val m = ld.map { d -> (d.towns + d.refs).any { norm(it) in want } }
      m.takeIf { it.any { v -> v } && !it.all { v -> v } }
    }
  }
  return JunctionScene(
      side = side,
      lanes = lanes,
      distanceM = distanceM,
      rangeM = range,
      sign = sign?.second ?: osm?.taken ?: fallback,
      branchRefs = (sign?.first?.refs ?: emptyList()) + (after?.refs ?: emptyList()),
      mainRefs = here?.refs ?: emptyList(),
      motorway = motorway,
      // the graph knows the country when its admin data is complete; else the catalogue by position
      country = (here?.country ?: after?.country ?: countryFallback?.takeIf { it.length == 2 }
          ?: a?.pointAt(at)?.let { pt -> app.navmaster.truck.AppGraph.catalog.countryAt(pt.lat, pt.lng)?.iso })?.uppercase()
          // San Marino has no motorways: a graph with cut borders can still put them there
          ?.let { if (it == "SM" && motorway) "IT" else it },
      exit = type.contains("OFFRAMP") || type.contains("FORK"),
      analysis = a,
      traveledM = traveled,
      maneuverAtM = at,
      turn = turnLike,
      roundabout = false,
      otherSign = other,
      laneSigns = laneDest,
      laneSignsActive = laneActive,
  )
}

/**
 * The live view, drawn the way the dedicated navigators draw it (reference: the video of a Garmin
 * nüvi the driver chose). Two looks, from the same real geometry of the route:
 *
 * - lane guidance (still far from the junction): the road seen from high above and behind, on a
 *   green field with no sky, dark asphalt, bold white dashed lane lines and the lanes to take
 *   painted in the route's violet all along the curve;
 * - junction view (close to it): a sky, the ground, the road with its concrete barriers, and a
 *   big violet arrow painted on the lane through the manoeuvre, under the direction signs.
 *
 * Everything is drawn here from OpenStreetMap / Valhalla data: no image of anyone else is used.
 */
private fun DrawScope.drawGarmin(scene: JunctionScene, a: app.navmaster.truck.routing.RouteAnalysis, pos: Double, night: Boolean,
                                 near: Boolean, signsSpace: Float) {
  val w = size.width
  val h = size.height
  val violet = if (night) Color(0xFF8C62D8) else Color(0xFF9C6FE4)
  val violetLight = if (night) Color(0xFFA985E8) else Color(0xFFB896F2)
  val asphalt = if (night) Color(0xFF2A2B2E) else Color(0xFF38393C)
  val marking = if (night) Color(0xFFD8DADD) else Color.White
  val horizon: Float
  if (!near) {
    // lane guidance: a field that fills the panel, only a thin haze at the top
    horizon = h * 0.07f
    drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF1B2433), Color(0xFF26331F)) else listOf(Color(0xFFD9E6D2), Color(0xFF7FB35C)),
        0f, horizon), size = size.copy(height = horizon))
    drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF26331F), Color(0xFF1A2416)) else listOf(Color(0xFF6FA64B), Color(0xFF4F8A35)),
        horizon, h), topLeft = Offset(0f, horizon), size = size.copy(height = h - horizon))
  } else {
    // junction view: sky with the signs, a far strip of land, the ground
    horizon = max(h * 0.40f, signsSpace + h * 0.06f).coerceAtMost(h * 0.55f)
    drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF0B1424), Color(0xFF2B3D5C)) else listOf(Color(0xFF3F86D6), Color(0xFFC7DDF3)),
        0f, horizon), size = size.copy(height = horizon))
    // distant low hills / buildings, flat and hazy
    val hills = Path().apply {
      moveTo(0f, horizon)
      var x = 0f
      var up = true
      while (x < w) {
        val bw = w * (0.08f + ((x * 7) % 13) / 13f * 0.07f)
        lineTo(x, horizon - h * (if (up) 0.035f else 0.018f))
        lineTo(x + bw, horizon - h * (if (up) 0.035f else 0.018f))
        x += bw
        up = !up
      }
      lineTo(w, horizon); close()
    }
    drawPath(hills, if (night) Color(0xFF1F2A3A) else Color(0xFFA9B7C6))
    drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF23301E), Color(0xFF151D12)) else listOf(Color(0xFFB9B28E), Color(0xFF8E9A63)),
        horizon, h), topLeft = Offset(0f, horizon), size = size.copy(height = h - horizon))
  }

  val m = scene.maneuverAtM
  // far: the road ahead up to well beyond the junction; near: framed on the junction
  val lead = if (near) (if (scene.turn) 30.0 else 32.0) else 150.0
  val anchor = max(pos, m - lead).coerceAtMost(a.length)
  val here = a.pointAt(anchor)
  val plane = app.navmaster.truck.core.LocalPlane(here.lat, here.lng)
  val hv = (plane.toXY(a.pointAt(anchor + 12)) - plane.toXY(a.pointAt((anchor - 8).coerceAtLeast(0.0)))).unit()
  if (hv.len() < 0.5) return
  fun loc(c: uniffi.ferrostar.GeographicCoordinate): app.navmaster.truck.core.XY {
    val p = plane.toXY(c)
    return app.navmaster.truck.core.XY(p.x * hv.y - p.y * hv.x, p.x * hv.x + p.y * hv.y)
  }
  // near: the fork sits in the middle of the picture, as in a photo taken just before it
  val zMax = (m - anchor) + if (near) (if (scene.turn) 40.0 else 50.0) else 170.0
  val k = if (near) 0.012 else 0.016
  fun persp(z: Double) = 1.0 / (1.0 + max(z, -10.0) * k)
  val bottomY = h * 1.0
  val topY = horizon + (h - horizon) * 0.01
  val span = 1.0 - persp(zMax)
  // the road fills the bottom of the panel, as seen from the cab of the reference view
  val xs = w / (if (near) (if (scene.turn) 20.0 else 22.0) else 16.0)
  val cx = w / 2.0
  fun proj(p: app.navmaster.truck.core.XY): Offset? {
    if (p.y > zMax + 5 || p.y < -10) return null
    val q = persp(p.y)
    val y = bottomY - (1.0 - q) / span * (bottomY - topY)
    return Offset((cx + p.x * xs * q).toFloat(), y.toFloat())
  }
  val end = min(a.length, anchor + zMax + 5)
  val start = (anchor - 10).coerceAtLeast(0.0)
  if (end - start < 10) return
  val step = if (near) 2.0 else 3.0
  val center = mutableListOf<app.navmaster.truck.core.XY>()
  val along = mutableListOf<Double>()
  var s0 = start
  while (s0 <= end) { center += loc(a.pointAt(s0)); along += s0; s0 += step }
  val lanes = scene.lanes.take(8)
  val n = max(lanes.size, if (scene.motorway) 3 else 2).coerceAtLeast(1)
  val laneW = 3.6
  val taken = lanes.count { it.active }.let { if (it == 0) (if (scene.exit) 1 else n) else it }
  val merge = if (near) 40.0 else 70.0
  fun widthAt(s: Double): Double {
    val before = n * laneW
    val after = (if (scene.exit || scene.turn) max(1, if (scene.turn) min(n, 2) else taken) else n) * laneW
    val t = ((s - m) / merge).coerceIn(0.0, 1.0)
    return before + (after - before) * t
  }
  fun normals(pts: List<app.navmaster.truck.core.XY>): List<app.navmaster.truck.core.XY> = pts.indices.map { i ->
    val d = (pts[min(pts.size - 1, i + 1)] - pts[max(0, i - 1)]).unit()
    app.navmaster.truck.core.XY(d.y, -d.x)
  }
  fun ribbon(pts: List<app.navmaster.truck.core.XY>, off0: (Int) -> Double, off1: (Int) -> Double): Path? {
    val nrm = normals(pts)
    val left = pts.indices.mapNotNull { i -> proj(pts[i] + nrm[i] * off0(i)) }
    val right = pts.indices.mapNotNull { i -> proj(pts[i] + nrm[i] * off1(i)) }
    if (left.size < 2 || right.size < 2) return null
    return Path().apply {
      moveTo(left[0].x, left[0].y); for (o in left.drop(1)) lineTo(o.x, o.y)
      for (o in right.reversed()) lineTo(o.x, o.y); close()
    }
  }
  fun polyline(pts: List<app.navmaster.truck.core.XY>, off: (Int) -> Double): Path? {
    val nrm = normals(pts)
    val o = pts.indices.mapNotNull { i -> proj(pts[i] + nrm[i] * off(i)) }
    if (o.size < 2) return null
    return Path().apply { moveTo(o[0].x, o[0].y); for (q in o.drop(1)) lineTo(q.x, q.y) }
  }
  // line widths scale with the panel, like the thick markings of the reference
  val edgeW = w * 0.012f
  val dashW = w * 0.011f
  val dash = PathEffect.dashPathEffect(floatArrayOf(w * 0.035f, w * 0.05f))

  // the road not taken (the motorway after an exit, the other arm of a fork)
  if (!scene.roundabout) {
    val mi = along.indexOfFirst { it >= m }.let { if (it < 0) along.size - 1 else it }
    if (mi in 2 until center.size) {
      val inDir = (center[mi] - center[max(0, mi - 6)]).unit()
      if (inDir.len() > 0.5) {
        val gw = if (scene.exit) n * laneW else n * laneW
        val ghost = (0..60).map { j -> center[mi] + inDir * (j * 4.0) }
        ribbon(ghost, { -gw / 2 }, { gw / 2 })?.let { drawPath(it, asphalt) }
        polyline(ghost) { -gw / 2 }?.let { drawPath(it, marking, style = Stroke(edgeW)) }
        polyline(ghost) { gw / 2 }?.let { drawPath(it, marking, style = Stroke(edgeW)) }
        for (kk in 1 until n) polyline(ghost) { -gw / 2 + kk * laneW }?.let { drawPath(it, marking, style = Stroke(dashW, pathEffect = dash)) }
      }
    }
  }
  // concrete barriers along the road, near only (the photographic look)
  if (near && !scene.turn) {
    val barrier = if (night) Color(0xFF5D6166) else Color(0xFFCBC8BE)
    ribbon(center, { i -> -widthAt(along[i]) / 2 - 1.6 }, { i -> -widthAt(along[i]) / 2 - 0.9 })?.let { drawPath(it, barrier) }
    ribbon(center, { i -> widthAt(along[i]) / 2 + 0.9 }, { i -> widthAt(along[i]) / 2 + 1.6 })?.let { drawPath(it, barrier) }
  }
  // the route's road with its shoulders
  ribbon(center, { i -> -widthAt(along[i]) / 2 - 0.9 }, { i -> widthAt(along[i]) / 2 + 0.9 })?.let { drawPath(it, asphalt.copy(alpha = 0.9f)) }
  ribbon(center, { i -> -widthAt(along[i]) / 2 }, { i -> widthAt(along[i]) / 2 })?.let { drawPath(it, asphalt) }
  polyline(center) { i -> -widthAt(along[i]) / 2 }?.let { drawPath(it, marking, style = Stroke(edgeW)) }
  polyline(center) { i -> widthAt(along[i]) / 2 }?.let { drawPath(it, marking, style = Stroke(edgeW)) }
  for (kk in 1 until n) {
    polyline(center.filterIndexed { i, _ -> along[i] < m - 5 }) { i -> -widthAt(along[i]) / 2 + kk * laneW }?.let {
      drawPath(it, marking, style = Stroke(dashW, pathEffect = dash))
    }
  }
  // the lanes to take
  val active = lanes.mapIndexedNotNull { i, l -> if (l.active) i else null }
  val firstLane = active.minOrNull() ?: if (scene.side > 0) n - taken else if (scene.side < 0) 0 else (n - taken) / 2
  val lastLane = active.maxOrNull() ?: (firstLane + taken - 1)
  fun bandCenter(i: Int): Double {
    val c0 = -(n * laneW) / 2 + (firstLane + lastLane + 1) * laneW / 2
    val t = ((along[i] - m) / merge).coerceIn(0.0, 1.0)
    return c0 * (1 - t)
  }
  val nrm = normals(center)
  if (!near) {
    // lane guidance: the lanes to take painted violet all along, a lighter core like a lit lane
    val bw = (lastLane - firstLane + 1) * laneW * 0.86
    ribbon(center, { i -> bandCenter(i) - bw / 2 }, { i -> bandCenter(i) + bw / 2 })?.let { drawPath(it, violet) }
    ribbon(center, { i -> bandCenter(i) - bw / 4 }, { i -> bandCenter(i) + bw / 4 })?.let { drawPath(it, violetLight.copy(alpha = 0.55f)) }
  } else {
    // junction view: one big violet arrow painted on the lane, through the manoeuvre
    val sel0 = center.indices.filter { along[it] >= max(pos + 2, m - 45) && along[it] <= m + 55 }
    // the arrow ends where the path turns away from the view (past 50°): beyond that it would be
    // a flat line on the horizon; its head there points the way to go, like on the reference
    val cut = sel0.indexOfFirst { i ->
      along[i] > m && i + 1 < center.size && (center[i + 1] - center[i]).let { d -> kotlin.math.abs(d.x) > kotlin.math.abs(d.y) * 1.2 }
    }
    val sel = if (cut > 0) sel0.take(cut + 1) else sel0
    val aw = laneW * 0.62
    // the head is 9 m long, so it reads well even far in the picture
    val headSamples = (9.0 / step).toInt().coerceAtLeast(2)
    if (sel.size > headSamples + 1) {
      val body = sel.dropLast(headSamples)
      val pts = body.map { center[it] }
      val offs = body.map { bandCenter(it) }
      val left = pts.indices.mapNotNull { j -> proj(pts[j] + nrm[body[j]] * (offs[j] - aw / 2)) }
      val right = pts.indices.mapNotNull { j -> proj(pts[j] + nrm[body[j]] * (offs[j] + aw / 2)) }
      val tipI = sel.last()
      val baseI = body.last()
      val tip = proj(center[tipI] + nrm[tipI] * bandCenter(tipI))
      val hl = proj(center[baseI] + nrm[baseI] * (bandCenter(baseI) - aw * 1.8))
      val hr = proj(center[baseI] + nrm[baseI] * (bandCenter(baseI) + aw * 1.8))
      if (left.size >= 2 && right.size >= 2 && tip != null && hl != null && hr != null) {
        val arrow = Path().apply {
          moveTo(left[0].x, left[0].y); for (o in left.drop(1)) lineTo(o.x, o.y)
          lineTo(hl.x, hl.y); lineTo(tip.x, tip.y); lineTo(hr.x, hr.y)
          for (o in right.reversed()) lineTo(o.x, o.y); close()
        }
        drawPath(arrow, violet)
        drawPath(arrow, violetLight, style = Stroke(w * 0.006f, join = StrokeJoin.Round))
      }
    }
  }
}

