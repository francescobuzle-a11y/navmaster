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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    /** A split of two roads (both bend away), not an exit. */
    val fork: Boolean = false,
    /** The signs over the lanes, left to right (OSM destination:lanes), when mapped. */
    val laneSigns: List<app.navmaster.truck.routing.LaneDest>? = null,
    /** Which of those lanes the route takes (same size as [laneSigns]), when known. */
    val laneSignsActive: List<Boolean>? = null,
    /** The lane the vehicle is really in (0 = leftmost of its direction), from the satellites; null when not known. */
    val vehicleLane: Int? = null,
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
  val (bg, fg) = refPlateColors(ref, country)
  Text(
      r, color = nightSign(fg, night), fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(nightSign(bg, night))
          .border(1.5.dp, nightSign(fg, night).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
          .padding(horizontal = 6.dp, vertical = 1.dp),
  )
}

/** Background and text colour of a road-number plate, the way the country paints it. */
fun refPlateColors(ref: String, country: String?): Pair<Color, Color> {
  val r = ref.uppercase()
  val c = country?.uppercase()
  return when {
    r.startsWith("E") && r.drop(1).firstOrNull()?.isDigit() == true -> SignGreen to Color.White
    r.startsWith("A") && r.drop(1).firstOrNull()?.isDigit() == true ->
      (if (c == "DE" || c == "FR" || c == "AT" || c == "ES" || c == "PT" || c == "NL" || c == "BE" || c == "LU" || c == "PL") SignBlue
      else if (c in GREEN_MOTORWAY) SignGreen else SignBlue) to Color.White
    c == "DE" && r.startsWith("B") -> SignYellow to Color.Black
    c == "FR" && (r.startsWith("N") || r.startsWith("D")) -> (if (r.startsWith("N")) Color(0xFFD7263D) else SignYellow) to
        (if (r.startsWith("N")) Color.White else Color.Black)
    else -> SignBlue to Color.White
  }
}

/**
 * The junction view, as on the dedicated truck navigators: the road seen from the driver's seat,
 * the lanes with the ones to take lit up and an arrow on them, and above them the direction signs
 * with the real road numbers and towns. The fork comes closer as the vehicle does, smoothly.
 */
@Composable
fun JunctionView(scene: JunctionScene, night: Boolean, modifier: Modifier = Modifier, fill: Boolean = false,
                 height: androidx.compose.ui.unit.Dp = 260.dp, vehAlong: androidx.compose.runtime.State<Double>? = null) {
  // the same manoeuvre keeps the same key while its point wobbles with the GPS by a few metres (a
  // key that changed at every 25 m boundary rebuilt the whole junction from the map again)
  val anchor = androidx.compose.runtime.remember { DoubleArray(1) { scene.maneuverAtM } }
  if (kotlin.math.abs(scene.maneuverAtM - anchor[0]) > 60) anchor[0] = scene.maneuverAtM
  val mKey = anchor[0].toLong()
  // the driver can close the panel for this manoeuvre (×, top left, as on the reference device)
  var closed by androidx.compose.runtime.remember(mKey) { androidx.compose.runtime.mutableStateOf(false) }
  if (closed) return
  // the distance glides between two GPS fixes (one a second), so the drawing moves continuously
  val dist by animateFloatAsState(scene.distanceM.toFloat(), tween(1000, easing = androidx.compose.animation.core.LinearEasing), label = "dist")
  // the shape of the junction is worked out once per manoeuvre
  val model = androidx.compose.runtime.remember(mKey, scene.lanes.size, scene.side) { jvModelOf(scene) }
  // the real junction, from the roads of the graph or the map (worked out once, away from the
  // screen); while it is being worked out the last one, or the drawing of a standard junction,
  // stays: the picture never flickers between the two
  val real = androidx.compose.runtime.remember(mKey) { androidx.compose.runtime.mutableStateOf<RealJv?>(null) }
  val analysisKey = System.identityHashCode(scene.analysis)
  androidx.compose.runtime.LaunchedEffect(mKey, analysisKey) {
    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      runCatching { realJunctionOf(scene, model) }.onFailure { android.util.Log.w("NavMasterJV", "real junction: $it") }.getOrNull()
    }
    if (r != null || real.value == null) real.value = r
  }
  // the signs: once the junction has some, the room for them stays for this manoeuvre (no jumps)
  val laneGantry = !scene.turn && scene.laneSigns != null
  val signsNow = laneGantry || scene.sign != null || scene.otherSign != null || (!scene.turn && scene.mainRefs.isNotEmpty())
  val signsSeen = androidx.compose.runtime.remember(mKey) { androidx.compose.runtime.mutableStateOf(false) }
  if (signsNow) signsSeen.value = true
  val hasSigns = signsSeen.value
  // the lanes at the gore, as a row under the picture: the arrows of every lane, the ones to take lit
  val rowLanes = real.value?.geo?.let { g -> g.arrowsAtNode.ifEmpty { List(g.lanesAtNode) { "through" } } to g.active }
      ?: (scene.lanes.takeIf { it.size >= 2 }?.let { l -> l.map { it.directions.firstOrNull() ?: "straight" } to l.indices.filter { l[it].active } })
  Column(
      modifier.shadow(10.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0C1117)),
  ) {
    BoxWithConstraints(if (fill) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(height)) {
      // the signs take a part of the picture that suits its height (small phones, big tablets)
      val signsDp = if (hasSigns) (maxHeight.value * 0.36f).coerceIn(72f, 110f).dp else 0.dp
      val signsPx = with(androidx.compose.ui.platform.LocalDensity.current) { signsDp.toPx() }
      Canvas(Modifier.fillMaxSize()) {
        val rj = real.value
        // the vehicle exactly where the map's arrow is, at every frame (the satellites' position
        // carried on at the vehicle's speed); the guidance's distance, glided, only when that is not there
        val along = vehAlong?.value?.takeIf { it >= 0 && kotlin.math.abs(it - (scene.maneuverAtM - scene.distanceM)) < 400 }
            ?: (scene.maneuverAtM - dist.toDouble())
        if (rj != null) drawRealJunction(rj, along, night, signsPx, scene.vehicleLane)
        else drawJunctionScheme(model, dist.toDouble().coerceAtMost(400.0), true, night, signsPx)
        // the gantry the signs hang on: posts at the sides of the road, a lattice beam across
        if (hasSigns) drawGantry(signsPx, night)
      }
      Text("×", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
              .clickable { closed = true }.padding(horizontal = 10.dp, vertical = 0.dp))
      // the distance to the junction, bottom left
      Text(if (scene.distanceM < 30) "ora" else "tra " + Fmt.distanceText(scene.distanceM), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
              .padding(horizontal = 8.dp, vertical = 2.dp))
      // the signs over the lanes (destination:lanes): one panel over each group of lanes
      // the lines of the panels fit the band: as many towns as the height allows, never cut
      val townSp = if (signsDp.value >= 100f) 17 else if (signsDp.value >= 86f) 15 else 14
      val lineDp = townSp * 1.32f
      val maxTowns = ((signsDp.value - 16f - 28f - 2f) / lineDp).toInt().coerceIn(1, 3)
      val maxLaneTowns = ((signsDp.value - 16f - 22f - 24f) / (lineDp * 0.9f)).toInt().coerceIn(1, 2)
      if (hasSigns && laneGantry) LaneGantry(scene, night,
          Modifier.align(Alignment.TopCenter).fillMaxWidth().height(signsDp).clipToBounds().padding(start = 50.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
          maxLaneTowns, townSp - 2)
      // the signs, on a gantry above the road
      else if (hasSigns && signsNow) Row(
          Modifier.align(Alignment.TopCenter).fillMaxWidth().height(signsDp).clipToBounds().padding(start = 50.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
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
                taken = scene.side == 0 && scene.exit.not(), country = scene.country, night = night, maxTowns = maxTowns, townSp = townSp)
          }
        }
        val branch: @Composable (Modifier) -> Unit = { m ->
          val sign = scene.sign
          val (sBg, sFg) = sign?.colour?.let { osmSignColors(it) } ?: (brBg to brFg)
          SignPanel(m, sBg, sFg, arrow = if (scene.side < 0) -45f else if (scene.side > 0) 45f else 0f,
              exitNumber = sign?.exitNumbers?.firstOrNull(),
              refs = (sign?.branches.orEmpty() + scene.branchRefs).distinct().take(3),
              towns = (sign?.towards.orEmpty() + sign?.exitNames.orEmpty()).distinct().take(3),
              taken = true, country = scene.country, night = night, maxTowns = maxTowns, townSp = townSp)
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
    // the lanes at the gore: one arrow per lane, the ones to take lit, the vehicle's lane marked
    if (rowLanes != null && rowLanes.first.size >= 2) {
      LaneRow(rowLanes.first, rowLanes.second, scene.vehicleLane, Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp, vertical = 4.dp))
    }
  }
}

/** The lanes at the gore as a row of arrows: every lane of the road, the ones to take blue and lit, the vehicle's lane with a dot. */
@Composable
private fun LaneRow(arrows: List<String>, active: List<Int>, vehicleLane: Int?, modifier: Modifier) {
  Canvas(modifier) {
    val n = arrows.size
    val cell = min(size.width / n, size.height * 1.6f)
    val x0 = (size.width - cell * n) / 2
    for (i in 0 until n) {
      val lit = i in active
      val cx = x0 + (i + 0.5f) * cell
      if (lit) drawRoundRect(Color(0xFF1E88E5), Offset(x0 + i * cell + 2f, 0f), Size(cell - 4f, size.height), androidx.compose.ui.geometry.CornerRadius(6f))
      val angle = when (arrows[i]) {
        "slight_right", "slight right", "merge_to_left" -> 35f
        "right" -> 70f
        "sharp_right", "sharp right" -> 110f
        "slight_left", "slight left", "merge_to_right" -> -35f
        "left" -> -70f
        "sharp_left", "sharp left" -> -110f
        "reverse", "uturn" -> 180f
        else -> 0f
      }
      val h = size.height
      val s = h * 0.72f
      val col = if (lit) Color.White else Color(0x80FFFFFF)
      val rad = Math.toRadians(angle.toDouble())
      val tipX = cx + (kotlin.math.sin(rad) * s * 0.5f).toFloat()
      val tipY = h * 0.9f - s * 0.45f - (kotlin.math.cos(rad) * s * 0.5f).toFloat()
      val path = Path().apply { moveTo(cx, h * 0.9f); lineTo(cx, h * 0.9f - s * 0.45f); lineTo(tipX, tipY) }
      drawPath(path, col, style = Stroke(width = s * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
      // the head
      val hs = s * 0.22f
      val dx = kotlin.math.sin(rad).toFloat(); val dy = -kotlin.math.cos(rad).toFloat()
      val head = Path().apply {
        moveTo(tipX + dx * hs * 0.9f, tipY + dy * hs * 0.9f)
        lineTo(tipX - dy * hs * 0.7f, tipY + dx * hs * 0.7f)
        lineTo(tipX + dy * hs * 0.7f, tipY - dx * hs * 0.7f)
        close()
      }
      drawPath(head, col)
      if (vehicleLane == i) drawCircle(Color(0xFFFFC400), s * 0.12f, Offset(cx, h * 0.93f))
    }
  }
}

/** One panel of the gantry: arrow, exit number, road numbers, towns (as written on the sign). */
@Composable
private fun SignPanel(
    modifier: Modifier, bg: Color, fg: Color, arrow: Float, exitNumber: String?, refs: List<String>, towns: List<String>,
    taken: Boolean, country: String?, night: Boolean = false, maxTowns: Int = 3, townSp: Int = 17,
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
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (r in refs.take(if (exitNumber != null) 2 else 3)) RefPlate(r, country, night = night) }
    }
    // the towns that fit the band; when there are more, the last line carries them together
    val shown = if (towns.size <= maxTowns) towns else towns.take(maxTowns - 1) + towns.drop(maxTowns - 1).joinToString(" · ")
    for (t in shown) {
      Text(t, color = fg, fontSize = townSp.sp, lineHeight = (townSp * 1.25f).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * The gantry over the lanes, as on the motorways before a split: one panel over each group of lanes
 * going to the same place, a down arrow over every lane, the panels of the lanes to take lit.
 */
@Composable
private fun LaneGantry(scene: JunctionScene, night: Boolean, modifier: Modifier, maxTowns: Int = 2, townSp: Int = 15) {
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
        val shown = if (d.towns.size <= maxTowns) d.towns else d.towns.take(maxTowns - 1) + d.towns.drop(maxTowns - 1).joinToString(" · ")
        for (t in shown) {
          Text(t, color = fg, fontSize = townSp.sp, lineHeight = (townSp * 1.25f).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private var jvLogged = -1L

fun junctionSceneOf(
    instruction: uniffi.ferrostar.VisualInstruction?,
    distanceM: Double?,
    a: app.navmaster.truck.routing.RouteAnalysis?,
    traveled: Double,
    countryFallback: String? = null,
    vehicleLane: Int? = null,
    /** From how far the view opens (metres) on motorways and elsewhere, and at least how many seconds before (Settings). */
    motorwayRangeM: Double = 1000.0,
    roadRangeM: Double = 220.0,
    minSeconds: Double = 25.0,
    speedMs: Double = 0.0,
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
  // the junction view from 1 km on motorways (the camera waits before the gore and the vehicle is
  // seen going lane by lane towards it), close to the junction elsewhere
  // (the distances are the driver's settings; at speed the view opens early enough all the same)
  val range = max(if (motorway || motorwayJunction) motorwayRangeM else roadRangeM, speedMs.coerceAtLeast(0.0) * minSeconds)
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
  if (jvLogged != at.toLong() / 50) {
    jvLogged = at.toLong() / 50
    android.util.Log.i("NavMasterJV", "scene at ${at.toInt()} m: ${type} ${mod} lanes ${lanes.size}, graph lanes ${here?.lanes}, " +
        "shape ${a?.junctionShapeNear(at, 90.0)}")
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
      fork = type.contains("FORK"),
      laneSigns = laneDest,
      laneSignsActive = laneActive,
      vehicleLane = vehicleLane,
  )
}
