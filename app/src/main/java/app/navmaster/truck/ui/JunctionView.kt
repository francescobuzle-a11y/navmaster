package app.navmaster.truck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.RoadsIndex
import app.navmaster.truck.nav.LaneModel
import app.navmaster.truck.nav.LanePlan
import app.navmaster.truck.routing.EdgeSign
import app.navmaster.truck.routing.RouteAnalysis
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ferrostar.LaneInfo

/**
 * The junction view, drawn the way the dedicated navigators (Garmin's junction view) draw it: the
 * road seen from a point a little above it and some way before the junction, so that the lanes
 * stay wide and readable up to the horizon; only the carriageway and the roads of the junction
 * (the one taken and the other branch), the lanes to take coloured for their whole width with
 * arrow heads inside them (the same lanes as in the lane strip and the voice: nav.LaneModel), the
 * sign over the lanes (the map's direction signs when it has them, else a sign made from the road
 * taken: its number, its name, the turn), a bar with the distance. The picture is drawn once per
 * junction; only the vehicle's mark moves on it, in its lane.
 */
data class JunctionScene(
    val junction: LaneModel.Junction,
    val distanceM: Double,
    val rangeM: Double,
    val route: JvRoute,
    val traveledM: Double,
    val maneuverAtM: Double,
    val country: String?,
    /** The lane the vehicle is really in (0 = leftmost of its direction), from the satellites; null when not known. */
    val vehicleLane: Int?,
    /** The road taken leads to the entry booth of a motorway (the sign shows the booth). */
    val tollEntry: Boolean = false,
    /** The number of the motorway entered at the booth ("A21"), when the graph has it. */
    val tollRef: String? = null,
)

/** What the junction view needs of the route: its line and its roads (from RouteAnalysis, or made up in the tests). */
class JvRoute(
    /** The same object for the same route (the picture is kept while it does not change). */
    val key: Any,
    val length: Double,
    val pointAt: (Double) -> uniffi.ferrostar.GeographicCoordinate,
    val edgeAt: (Double) -> app.navmaster.truck.routing.EdgeInfo?,
    val edges: List<app.navmaster.truck.routing.EdgeInfo>,
) {
  companion object {
    fun of(a: RouteAnalysis) = JvRoute(a, a.length, a::pointAt, a::edgeAt, a.edges)
  }
}

/** The roads of the graph around a point (the tests put their own). */
internal var jvRoadsNear: (Double, Double, Double) -> List<RoadsIndex.Road> = { lat, lon, r -> AppGraph.roads.near(lat, lon, r) }

/** Countries whose motorway signs are green (the others use blue). */
private val GREEN_MOTORWAY = setOf("IT", "CH", "SI", "HR", "CZ", "SK", "RS", "BA", "ME", "MK", "GR", "RO", "BG", "AL", "XK", "DK", "SE")
private val LEFT_TRAFFIC = setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")

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

/** Background and text colour of a road-number plate, the way the country paints it. */
fun refPlateColors(ref: String, country: String?): Pair<Color, Color> {
  val r = ref.uppercase()
  val c = country?.uppercase()
  return when {
    r.startsWith("E") && r.drop(1).firstOrNull()?.isDigit() == true -> SignGreen to Color.White
    r.startsWith("A") && r.drop(1).firstOrNull()?.isDigit() == true ->
      (if (c in setOf("DE", "FR", "AT", "ES", "PT", "NL", "BE", "LU", "PL")) SignBlue else if (c in GREEN_MOTORWAY) SignGreen else SignBlue) to Color.White
    c == "DE" && r.startsWith("B") -> SignYellow to Color.Black
    c == "FR" && r.startsWith("N") -> Color(0xFFD7263D) to Color.White
    c == "FR" && r.startsWith("D") -> SignYellow to Color.Black
    else -> SignBlue to Color.White
  }
}

/** The small plate of a road number, coloured like on the signs ("A1" green in Italy, "E45" green, "SS16" blue). */
@Composable
fun RefPlate(ref: String, country: String?, big: Boolean = false, night: Boolean = false) {
  val (bg, fg) = refPlateColors(ref, country)
  Text(
      ref.uppercase(), color = fg, fontSize = if (big) 18.sp else 14.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (night) androidx.compose.ui.graphics.lerp(bg, Color.Black, 0.2f) else bg)
          .border(1.5.dp, fg.copy(alpha = 0.9f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
  )
}

/** Some lanes go the route's way and some do not (the lanes are worth showing). */
fun lanesMatter(lanes: List<LaneInfo>): Boolean = lanes.size >= 2 && lanes.any { it.active } && lanes.any { !it.active }

/**
 * The junction view for the next manoeuvre, or null: at motorway exits and forks from the distance
 * chosen in the settings (or [minSeconds] at the present speed, whichever is longer), at junctions
 * in town only where three lanes or more are painted and only some go the route's way.
 */
fun junctionSceneOf(
    instruction: uniffi.ferrostar.VisualInstruction?,
    distanceM: Double?,
    a: RouteAnalysis?,
    traveled: Double,
    countryFallback: String?,
    vehicleLane: Int?,
    motorwayRangeM: Double,
    roadRangeM: Double,
    minSeconds: Double,
    speedMs: Double,
): JunctionScene? {
  if (instruction == null || distanceM == null || a == null || distanceM < 0) return null
  val type = instruction.primaryContent.maneuverType?.name ?: return null
  if (type.contains("ARRIVE") || type.contains("DEPART") || type.contains("ROUNDABOUT") || type.contains("ROTARY")) return null
  val at = traveled + distanceM
  val country = (a.edgeAt(traveled)?.country ?: countryFallback)?.uppercase()
  val j = LaneModel.at(a, at, country in LEFT_TRAFFIC) ?: return null
  val exitLike = j.kind == LanePlan.Kind.EXIT || j.kind == LanePlan.Kind.FORK
  if (!exitLike && !(j.painted && j.lanes.size >= 3 && j.active.size < j.lanes.size)) return null
  val range = if (exitLike) max(motorwayRangeM, speedMs * minSeconds) else roadRangeM
  if (distanceM > range) return null
  // the entry booth of a motorway just past the junction (the booth's node is on the link, a
  // little before or after the turn): the sign of the booth, with the motorway's number
  val booth = a.tollBooths.firstOrNull { it.alongM >= at - 300 && it.alongM <= at + 800 }
  val tollEntry = booth != null && a.boothRole(booth) == "entrata"
  // the motorway's number: from the motorway's own stretches (the first ones may carry no name),
  // else the signs of the slip road (destination:ref), else the map's ref of the motorway's way
  val tollRef = if (!tollEntry) null else run {
    val mw = a.edges.filter { it.roadClass == "motorway" && !it.isRamp && it.startM >= at - 50 && it.startM <= at + 4000 }
    mw.firstNotNullOfOrNull { e -> e.refs.firstOrNull { isMotorwayRef(it) } }
        ?: a.edges.filter { it.startM >= at - 50 && it.startM <= at + 4000 }.firstNotNullOfOrNull { e -> e.sign?.branches?.firstOrNull { isMotorwayRef(it) } }
        ?: mw.firstOrNull()?.let { e ->
          val q = a.pointAt((e.startM + 20).coerceAtMost(a.length))
          runCatching { jvRoadsNear(q.lat, q.lng, 40.0) }.getOrDefault(emptyList())
              .firstOrNull { it.osm == e.wayId }?.ref?.split(';')?.map { it.trim() }?.firstOrNull { isMotorwayRef(it) }
        }
  }
  return JunctionScene(j, distanceM, range, JvRoute.of(a), traveled, at, country, vehicleLane, tollEntry, tollRef)
}

// ---------------------------------------------------------------------------------- the picture

internal class Palette(night: Boolean) {
  val skyTop = if (night) Color(0xFF0B1220) else Color(0xFF7FB0E4)
  val skyLow = if (night) Color(0xFF1C2635) else Color(0xFFDCE9F6)
  val groundFar = if (night) Color(0xFF1A2026) else Color(0xFFCBD6BE)
  val groundNear = if (night) Color(0xFF151A1F) else Color(0xFFB9CBA3)
  val asphalt = if (night) Color(0xFF3A3F47) else Color(0xFF596069)
  val asphaltFar = if (night) Color(0xFF2B3038) else Color(0xFF8E959D)
  /** The roads around that the route does not take: lighter, without markings, under the others. */
  val side = if (night) Color(0xFF2A2F36) else Color(0xFFA9ADB3)
  val shoulder = if (night) Color(0xFF30353C) else Color(0xFF8E9298)
  val marking = if (night) Color(0xFFC9CED4) else Color(0xFFFFFFFF)
  val lit = Color(0xFF2F7CF6)
  val litEdge = Color(0xFF9CC4FF)
  /** The arrow heads inside the lanes to take. */
  val paint = if (night) Color(0xFFE6EAEE) else Color(0xFFFFFFFF)
  val post = if (night) Color(0xFF6A7078) else Color(0xFF8D949C)
  val beam = if (night) Color(0xFF4A5058) else Color(0xFF5C636B)
}

/** A strip of ground along a line: the centre line in the junction's frame and, at each point, its left and right edges (metres, left positive). */
internal class Strip(val x: DoubleArray, val y: DoubleArray, val left: DoubleArray, val right: DoubleArray, val color: Color, val z: Double = 0.0)

/** What is drawn, in metres: x along the road arriving at the junction (the node at 0), y to the left, z up. */
internal class Scene(
    val asphalt: List<Strip>,
    val markings: List<Strip>,
    val lit: List<Strip>,
    /** The arrow heads inside the lanes to take: polygons on the ground with their colour. */
    val paint: List<Pair<List<DoubleArray>, Color>>,
    /** The way through the junction: the centre line of the lanes to take (a point every few metres). */
    val guideX: DoubleArray,
    val guideY: DoubleArray,
    /** The first point of the way past the node. */
    val guideNode: Int,
    val gantry: Gantry?,
    /** The eye: over the middle of our lanes, some way before the node, above the road. */
    val camera: DoubleArray,
    /** The line of sight in the plane (unit vector): from the eye towards the node. */
    val forward: DoubleArray,
    val hfovDeg: Double,
    /** Where the horizon is, as a fraction of the picture's height from the top. */
    val horizonFrac: Double,
    /** The route's line in the frame, a point every [routeStep] metres from [routeFrom] (route metres), with the lateral position of the lanes to take. */
    val routeFrom: Double,
    val routeStep: Double,
    val nodeM: Double,
    /** From here on (route metres) the vehicle is in front of the camera. */
    val visibleFromM: Double,
    val routeX: DoubleArray,
    val routeY: DoubleArray,
    val routeLaneY: DoubleArray,
    /** Lateral position of each lane before the junction (its middle), for the vehicle in its lane. */
    val laneY: DoubleArray,
    val laneW: Double,
    val palette: Palette,
    /** The country of the junction (the colours of the road-number plates). */
    val country: String?,
)

/**
 * One panel of the sign: over the lanes from [yLeft] to [yRight] (metres, left positive), the road
 * numbers as plates, the towns, the exit number, its colours, the arrow of its lanes.
 */
internal class Panel(val yLeft: Double, val yRight: Double, val refs: List<String>, val towns: List<String>, val exit: String?,
                     val bg: Color, val fg: Color, val arrowDeg: Float, val taken: Boolean) {
  /** The lines as the old tests read them: the numbers on one line, then the towns. */
  val lines: List<String> get() = listOfNotNull(refs.joinToString("  ").takeIf { it.isNotBlank() }) + towns
}

/**
 * The sign over the lanes at the route's point ([px], [py]), its offsets along the normal ([nx], [ny])
 * of the road there; [fromMap] when the texts are the map's (OSM destination tags), false when the
 * sign is made from the road taken (its number and name).
 */
internal class Gantry(val px: Double, val py: Double, val nx: Double, val ny: Double, val yLeft: Double, val yRight: Double,
                      val panels: List<Panel>, val fromMap: Boolean = true) {
  fun at(o: Double, z: Double, p: Projector): FloatArray? = p.project(px + nx * o, py + ny * o, z)
}

private const val LANE_MOTORWAY = 3.5
private const val LANE_MAIN = 3.4
private const val LANE_LOCAL = 3.0

/**
 * The eye, as the dedicated navigators place it: above the road (a few metres higher than a cab,
 * so that the lanes stay wide far ahead) and some way before the node, farther on a motorway
 * where the exit lane leaves gradually.
 */
internal const val EYE_HEIGHT = 9.0
internal const val EYE_HEIGHT_EXIT = 11.0
internal const val EYE_BACK_JUNCTION = 50.0
internal const val EYE_BACK_EXIT = 90.0
internal const val HFOV_DEG = 55.0
/** Never less than this vertically (a wide, low picture would see only a slice of the road). */
internal const val MIN_VFOV_DEG = 34.0
internal const val HORIZON_FRAC = 0.30

private fun laneWidth(cls: String, link: Boolean): Double = when {
  link || cls == "motorway" || cls == "trunk" -> LANE_MOTORWAY
  cls == "primary" || cls == "secondary" -> LANE_MAIN
  else -> LANE_LOCAL
}

private fun defaultLanes(cls: String, oneway: Boolean, link: Boolean): Int = when {
  link -> 1
  cls == "motorway" || cls == "trunk" -> if (oneway) 2 else 4
  cls == "service" -> if (oneway) 1 else 2
  else -> if (oneway) 1 else 2
}

/** The junction's frame: the node at the origin, x along the road arriving. */
private class Frame(lat0: Double, private val lon0: Double, bearingDeg: Double, private val lat00: Double = lat0) {
  private val kx = 111_320.0 * cos(Math.toRadians(lat0))
  private val ky = 110_540.0
  private val s = sin(Math.toRadians(bearingDeg))
  private val c = cos(Math.toRadians(bearingDeg))
  fun local(lat: Double, lon: Double): DoubleArray {
    val dx = (lon - lon0) * kx
    val dy = (lat - lat00) * ky
    return doubleArrayOf(dx * s + dy * c, -dx * c + dy * s)
  }
}

private fun smooth(t: Double): Double { val u = t.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }

/** Points of a polyline every [step] metres (and its last point). */
private fun resample(xs: List<Double>, ys: List<Double>, step: Double): Pair<DoubleArray, DoubleArray> {
  val ox = ArrayList<Double>(); val oy = ArrayList<Double>()
  if (xs.size < 2) return DoubleArray(0) to DoubleArray(0)
  ox += xs[0]; oy += ys[0]
  var carry = 0.0
  for (i in 1 until xs.size) {
    val dx = xs[i] - xs[i - 1]; val dy = ys[i] - ys[i - 1]
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-6) continue
    var d = step - carry
    while (d < len) { ox += xs[i - 1] + dx * d / len; oy += ys[i - 1] + dy * d / len; d += step }
    carry = len - (d - step)
  }
  if (ox.size < 2 || abs(ox.last() - xs.last()) + abs(oy.last() - ys.last()) > 0.5) { ox += xs.last(); oy += ys.last() }
  return ox.toDoubleArray() to oy.toDoubleArray()
}

/** The strip of a line with edges at [left]/[right] metres from it (constant), cut where [keep] says no. */
private fun stripOf(x: DoubleArray, y: DoubleArray, left: Double, right: Double, color: Color): Strip =
    Strip(x, y, DoubleArray(x.size) { left }, DoubleArray(x.size) { right }, color)

/** Dashes of a line at offset [o]: [on] metres painted, [off] not. */
private fun dashes(x: DoubleArray, y: DoubleArray, o: Double, w: Double, on: Double, off: Double, color: Color, out: MutableList<Strip>) {
  var along = 0.0
  var startI = -1
  for (i in x.indices) {
    if (i > 0) along += sqrt((x[i] - x[i - 1]).let { it * it } + (y[i] - y[i - 1]).let { it * it })
    val phase = along % (on + off)
    val painted = phase < on
    if (painted && startI < 0) startI = i
    if ((!painted || i == x.size - 1) && startI >= 0) {
      val endI = i
      if (endI > startI) out += Strip(x.copyOfRange(startI, endI + 1), y.copyOfRange(startI, endI + 1),
          DoubleArray(endI - startI + 1) { o + w / 2 }, DoubleArray(endI - startI + 1) { o - w / 2 }, color)
      startI = -1
    }
  }
}

/** An arrow head on the road at [cx],[cy] pointing along ([ux],[uy]): a chevron [len] metres long and [wid] wide. */
private fun arrowHead(cx: Double, cy: Double, ux: Double, uy: Double, len: Double, wid: Double, color: Color, out: MutableList<Pair<List<DoubleArray>, Color>>) {
  val nx = -uy; val ny = ux
  val tip = doubleArrayOf(cx + ux * len * 0.6, cy + uy * len * 0.6)
  val l = doubleArrayOf(cx - ux * len * 0.4 + nx * wid / 2, cy - uy * len * 0.4 + ny * wid / 2)
  val r = doubleArrayOf(cx - ux * len * 0.4 - nx * wid / 2, cy - uy * len * 0.4 - ny * wid / 2)
  val inner = doubleArrayOf(cx - ux * len * 0.05, cy - uy * len * 0.05)
  out += listOf(tip, l, inner, r) to color
}

/** Builds the junction's picture (off the main thread): roads, lanes, markings, signs, camera. */
internal fun buildScene(s: JunctionScene, night: Boolean): Scene? {
  val a = s.route
  val j = s.junction
  val pal = Palette(night)
  val node = j.atM
  val lht = s.country in LEFT_TRAFFIC
  val p0 = a.pointAt(node)
  val pIn = a.pointAt((node - 60).coerceAtLeast(0.0))
  val bearing = app.navmaster.truck.core.Geo.bearing(pIn, p0)
  val f = Frame(p0.lat, p0.lng, bearing)
  val exitLike = j.kind == LanePlan.Kind.EXIT || j.kind == LanePlan.Kind.FORK
  // farther back on a motorway (the exit lane leaves gradually), nearer on a ramp or an ordinary
  // road (a bend just before the junction would take the junction out of view)
  val fastApproach = exitLike && !j.approachRamp && j.approachClass in setOf("motorway", "trunk")
  val camBack = if (fastApproach) EYE_BACK_EXIT else EYE_BACK_JUNCTION
  val camH = if (fastApproach) EYE_HEIGHT_EXIT else EYE_HEIGHT
  val back = camBack + 40.0
  val ahead = if (exitLike) 260.0 else 120.0

  // the route in the frame, every 2 m
  val from = (node - back).coerceAtLeast(0.0)
  val to = (node + ahead).coerceAtMost(a.length)
  val n = ((to - from) / 2.0).toInt().coerceAtLeast(2)
  val rx = DoubleArray(n + 1); val ry = DoubleArray(n + 1)
  for (i in 0..n) { val p = a.pointAt(from + (to - from) * i / n); val l = f.local(p.lat, p.lng); rx[i] = l[0]; ry[i] = l[1] }
  val routeAlong = DoubleArray(n + 1) { from + (to - from) * it / n }

  // the lanes before the junction: those of the lane model, else the map's, else the class's
  val approachRoad = runCatching { jvRoadsNear(p0.lat, p0.lng, 60.0) }.getOrDefault(emptyList()).firstOrNull { it.osm == j.approachWay }
  val approachOneway = approachRoad?.let { it.oneway != 0 } ?: (j.approachClass in setOf("motorway", "trunk") || j.approachRamp)
  val laneW = laneWidth(j.approachClass, j.approachRamp)
  val edgeLanes = a.edgeAt((node - 5).coerceAtLeast(0.0))?.lanes?.takeIf { it > 0 }
  val nLanes = when {
    j.lanes.isNotEmpty() -> j.lanes.size
    approachOneway -> edgeLanes ?: approachRoad?.lanes ?: defaultLanes(j.approachClass, true, j.approachRamp)
    else -> ((approachRoad?.lanes ?: edgeLanes ?: 2) / 2).coerceAtLeast(1)
  }
  // our direction: the whole carriageway on a one-way road, the right half (left where traffic keeps left) of a two-way one
  val oppLanes = if (approachOneway) 0 else ((approachRoad?.lanes ?: (2 * nLanes)) - nLanes).coerceAtLeast(1)
  val totalW = (nLanes + oppLanes) * laneW
  val ourLeft = if (approachOneway || lht) totalW / 2 else totalW / 2 - oppLanes * laneW
  // lane i (0 = leftmost of our direction): its middle, left positive
  val laneY = DoubleArray(nLanes) { i -> ourLeft - (i + 0.5) * laneW }
  val active = j.active.ifEmpty { if (j.side > 0) listOf(nLanes - 1) else if (j.side < 0) listOf(0) else emptyList() }
  val activeY = if (active.isEmpty()) 0.0 else active.map { laneY[it.coerceIn(0, nLanes - 1)] }.average()
  val otherIdx = (0 until nLanes).filter { it !in active }
  val otherY = if (otherIdx.isEmpty()) activeY else otherIdx.map { laneY[it] }.average()

  // after the junction the road taken starts where its lanes are, then follows its own line
  val taper = if (exitLike) 110.0 else 25.0
  val routeLaneY = DoubleArray(n + 1)
  val routeShift = DoubleArray(n + 1)
  for (i in 0..n) {
    val d = routeAlong[i] - node
    routeShift[i] = if (d <= 0 || !exitLike) 0.0 else activeY * (1 - smooth(d / taper))
    routeLaneY[i] = if (d <= 0) activeY else routeShift[i]
  }

  val asphalt = ArrayList<Strip>()
  val markings = ArrayList<Strip>()
  val lit = ArrayList<Strip>()
  val paint = ArrayList<Pair<List<DoubleArray>, Color>>()

  // the normal (to the left) and the tangent of the route's line at point i
  fun tangent(i: Int): DoubleArray {
    val i0 = (i - 2).coerceAtLeast(0); val i1 = (i + 2).coerceAtMost(n)
    val dx = rx[i1] - rx[i0]; val dy = ry[i1] - ry[i0]
    val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
    return doubleArrayOf(dx / l, dy / l)
  }
  fun normal(i: Int): DoubleArray { val t = tangent(i); return doubleArrayOf(-t[1], t[0]) }
  fun indexAt(along: Double): Int = (0..n).minByOrNull { abs(routeAlong[it] - along) } ?: 0

  // the road arriving: the route's line before the node, its lanes and markings
  val beforeIdx = (0..n).filter { routeAlong[it] <= node + 0.01 }
  val takenIdx = (0..n).filter { routeAlong[it] >= node - 0.01 }
  fun sub(idx: List<Int>, arr: DoubleArray) = DoubleArray(idx.size) { arr[idx[it]] }
  if (beforeIdx.size >= 2) {
    val bx = sub(beforeIdx, rx); val by = sub(beforeIdx, ry)
    val carriageLeft = totalW / 2
    val carriageRight = -totalW / 2
    asphalt += stripOf(bx, by, carriageLeft + 0.8, carriageRight - 0.8, pal.asphalt)
    // edge lines, lane lines (dashed), the centre line of a two-way road
    markings += stripOf(bx, by, carriageRight + 0.45, carriageRight + 0.15, pal.marking)
    markings += stripOf(bx, by, carriageLeft - 0.15, carriageLeft - 0.45, pal.marking)
    for (k in 1 until nLanes) dashes(bx, by, ourLeft - k * laneW, 0.3, if (exitLike) 4.5 else 3.0, if (exitLike) 9.0 else 6.0, pal.marking, markings)
    if (!approachOneway) dashes(bx, by, if (lht) ourLeft - nLanes * laneW else ourLeft, 0.3, 3.0, 6.0, pal.marking, markings)
    // the lanes to take, lit from 250 m before the node
    val litFrom = beforeIdx.filter { routeAlong[it] >= node - 250 }
    if (litFrom.size >= 2 && active.isNotEmpty()) {
      val lx = sub(litFrom, rx); val ly = sub(litFrom, ry)
      val l0 = active.minOf { it.coerceIn(0, nLanes - 1) }; val l1 = active.maxOf { it.coerceIn(0, nLanes - 1) }
      lit += stripOf(lx, ly, ourLeft - l0 * laneW - 0.25, ourLeft - (l1 + 1) * laneW + 0.25, pal.lit)
    }
  }
  // the road taken: from the lanes it starts from to its own line (at exits and forks); a two-way
  // road lit on our half
  val takenRoad = runCatching { a.pointAt((node + 20).coerceAtMost(a.length)).let { q -> jvRoadsNear(q.lat, q.lng, 40.0) } }
      .getOrDefault(emptyList()).firstOrNull { it.osm == j.taken.wayId }
  val takenOneway = takenRoad?.let { it.oneway != 0 } ?: (j.taken.ramp || j.taken.roadClass in setOf("motorway", "trunk"))
  val takenLw = laneWidth(j.taken.roadClass, j.taken.ramp)
  val takenLanes = when {
    takenOneway -> (if (j.takenLanes > 0) j.takenLanes else takenRoad?.lanes ?: active.size).coerceAtLeast(1)
    else -> (takenRoad?.lanes ?: 2).coerceAtLeast(2)
  }
  val takenW = takenLanes * takenLw
  if (takenIdx.size >= 2) {
    val tx = sub(takenIdx, rx); val ty = sub(takenIdx, ry)
    val shift = sub(takenIdx, routeShift)
    asphalt += Strip(tx, ty, DoubleArray(tx.size) { shift[it] + takenW / 2 + 0.8 }, DoubleArray(tx.size) { shift[it] - takenW / 2 - 0.8 }, pal.asphalt)
    markings += Strip(tx, ty, DoubleArray(tx.size) { shift[it] + takenW / 2 - 0.15 }, DoubleArray(tx.size) { shift[it] + takenW / 2 - 0.45 }, pal.marking)
    markings += Strip(tx, ty, DoubleArray(tx.size) { shift[it] - takenW / 2 + 0.45 }, DoubleArray(tx.size) { shift[it] - takenW / 2 + 0.15 }, pal.marking)
    if (takenOneway) {
      for (k in 1 until takenLanes) dashes(tx, ty, takenW / 2 - k * takenLw, 0.3, 4.5, 9.0, pal.marking, markings)
      lit += Strip(tx, ty, DoubleArray(tx.size) { shift[it] + takenW / 2 - 0.25 }, DoubleArray(tx.size) { shift[it] - takenW / 2 + 0.25 }, pal.lit)
    } else {
      dashes(tx, ty, 0.0, 0.3, 3.0, 6.0, pal.marking, markings)
      val l = if (lht) takenW / 2 - 0.25 else -0.25
      val r = if (lht) 0.25 else -takenW / 2 + 0.25
      lit += Strip(tx, ty, DoubleArray(tx.size) { shift[it] + l }, DoubleArray(tx.size) { shift[it] + r }, pal.lit)
    }
  }

  if (!exitLike && !takenOneway) for (i in 0..n) if (routeAlong[i] > node) routeLaneY[i] = if (lht) takenW / 4 else -takenW / 4

  // the other roads of the graph around the junction (the other main road from the lanes it starts from)
  val routeWays = a.edges.filter { it.endM > node - back - 20 && it.startM < node + ahead + 20 }.map { it.wayId }.toSet()
  val roads = runCatching { jvRoadsNear(p0.lat, p0.lng, 420.0) }.getOrDefault(emptyList())
  val otherWay = j.other?.wayId ?: 0L
  val otherW = (if (j.otherLanes > 0) j.otherLanes else otherIdx.size.coerceAtLeast(1)) * laneW
  // (the roads around that are not the junction's made the picture busy: only the other branch)
  for (r in roads) {
    if (r.pts.size < 2 || r.tunnel) continue
    val isOther = r.osm == otherWay && otherWay != 0L
    if (!isOther || r.osm in routeWays) continue
    val local = r.pts.map { f.local(it.lat, it.lng) }
    val xs = local.map { it[0] }; val ys = local.map { it[1] }
    // only after the node, starting where its lanes are
    val keep = xs.indices.map { xs[it] >= -1.0 }
    for ((px, py) in splitRuns(xs, ys, keep)) addRoad(r, px, py, true, otherY, taper, asphalt, markings, pal, lht, forcedWidth = otherW, main = true)
  }

  // the camera: over the middle of our lanes, [camBack] metres before the node and [camH] above
  // the road, looking at the node (on a bend the tangent at the eye would look past the junction)
  val ci = indexAt(node - camBack)
  val camY = ourLeft - nLanes * laneW / 2
  val cn = normal(ci)
  val camera = doubleArrayOf(rx[ci] + cn[0] * camY, ry[ci] + cn[1] * camY, camH)
  val ni = indexAt(node)
  val forward = run {
    val nn = normal(ni)
    val dx = rx[ni] + nn[0] * camY - camera[0]; val dy = ry[ni] + nn[1] * camY - camera[1]
    val l = sqrt(dx * dx + dy * dy)
    if (l < 5.0) tangent(ci) else doubleArrayOf(dx / l, dy / l)
  }

  // the way through the junction: the centre of the lanes to take, from in front of the eye to
  // well past the node, with an arrow head every 14 m
  val gIdx = (0..n).filter { routeAlong[it] >= routeAlong[ci] + 6.0 && routeAlong[it] <= node + (if (exitLike) 160.0 else 90.0) }
  val guideX = DoubleArray(gIdx.size); val guideY = DoubleArray(gIdx.size)
  val guideNode = gIdx.indexOfFirst { routeAlong[it] >= node }.coerceAtLeast(0)
  for ((q, i) in gIdx.withIndex()) {
    val nn = normal(i)
    guideX[q] = rx[i] + nn[0] * routeLaneY[i]; guideY[q] = ry[i] + nn[1] * routeLaneY[i]
  }
  if (active.isNotEmpty() || exitLike) {
    var next = routeAlong[ci] + 10.0
    for ((q, i) in gIdx.withIndex()) {
      if (routeAlong[i] < next || q == 0) continue
      val dx = guideX[q] - guideX[q - 1]; val dy = guideY[q] - guideY[q - 1]
      val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
      arrowHead(guideX[q], guideY[q], dx / l, dy / l, 2.6, laneW * 0.62, pal.paint, paint)
      next = routeAlong[i] + 14.0
    }
  }

  // the sign over the lanes, a little in front of the eye (its posts at the road's edges there)
  val gi = indexAt(node - camBack + (if (exitLike) 30.0 else 16.0))
  val gantry = signOf(j, s, ourLeft, laneW, nLanes, active, totalW)?.let { g ->
    val nn = normal(gi)
    Gantry(rx[gi], ry[gi], nn[0], nn[1], g.yLeft, g.yRight, g.panels, g.fromMap)
  }

  return Scene(asphalt, markings, lit, paint, guideX, guideY, guideNode, gantry, camera, forward, HFOV_DEG, HORIZON_FRAC,
      from, (to - from) / n, node, routeAlong[ci] + 9.0, rx.copyOf(), ry.copyOf(), routeLaneY, laneY, laneW, pal, s.country)
}

private fun splitRuns(xs: List<Double>, ys: List<Double>, keep: List<Boolean>): List<Pair<List<Double>, List<Double>>> {
  val out = ArrayList<Pair<List<Double>, List<Double>>>()
  var cx = ArrayList<Double>(); var cy = ArrayList<Double>()
  for (i in xs.indices) {
    if (keep[i]) { cx += xs[i]; cy += ys[i] } else { if (cx.size >= 2) out += cx to cy; cx = ArrayList(); cy = ArrayList() }
  }
  if (cx.size >= 2) out += cx to cy
  return out
}

/** A road of the graph: its carriageway, edge lines and lane lines; [shiftY] at the node going to 0 over [taper] metres. */
private fun addRoad(r: RoadsIndex.Road, xs0: List<Double>, ys0: List<Double>, shifted: Boolean, shiftY: Double, taper: Double,
                    asphalt: MutableList<Strip>, markings: MutableList<Strip>, pal: Palette, lht: Boolean, forcedWidth: Double? = null,
                    /** A road of the junction (the other branch): as dark as the route's, with its markings. */
                    main: Boolean = false) {
  val cls = r.roadClass.let { if (it == "service_other") (if (r.hw.startsWith("service")) "service" else "minor") else it }
  if (cls in setOf("track", "path", "footway", "cycleway", "steps")) return
  val (x, y) = resample(xs0, ys0, 4.0)
  if (x.size < 2) return
  val oneway = r.oneway != 0
  val lw = laneWidth(cls, r.isLink)
  val lanes = (if (oneway) (r.lanes ?: (if (r.oneway > 0) r.lanesF else r.lanesB)) else (r.lanes ?: ((r.lanesF ?: 0) + (r.lanesB ?: 0)).takeIf { it > 0 }))
      ?.takeIf { it > 0 } ?: defaultLanes(cls, oneway, r.isLink)
  val w = forcedWidth ?: (lanes * lw)
  val shift = DoubleArray(x.size) { i -> if (!shifted) 0.0 else shiftY * (1 - smooth(x[i].coerceAtLeast(0.0) / taper.coerceAtLeast(1.0))) }
  asphalt += Strip(x, y, DoubleArray(x.size) { shift[it] + w / 2 + 0.6 }, DoubleArray(x.size) { shift[it] - w / 2 - 0.6 }, if (main) pal.asphalt else pal.side)
  // the roads around: only their surface (their lines made the picture busy)
  if (!main) return
  if (cls in setOf("motorway", "trunk", "primary") || r.isLink) {
    markings += Strip(x, y, DoubleArray(x.size) { shift[it] + w / 2 - 0.15 }, DoubleArray(x.size) { shift[it] + w / 2 - 0.35 }, pal.marking)
    markings += Strip(x, y, DoubleArray(x.size) { shift[it] - w / 2 + 0.35 }, DoubleArray(x.size) { shift[it] - w / 2 + 0.15 }, pal.marking)
  }
  val n = (w / lw).toInt().coerceAtLeast(1)
  if (n >= 2 && !shifted) {
    for (k in 1 until n) {
      val o = w / 2 - k * lw
      // the centre of a two-way road
      val centre = !oneway && k == (if (lht) (r.lanesF ?: n / 2) else (r.lanesB ?: n / 2))
      dashes(x, y, o, 0.15, if (centre) 3.0 else 4.5, if (centre) 4.5 else 7.5, pal.marking, markings)
    }
  } else if (n >= 2) {
    for (k in 1 until n) {
      val o = w / 2 - k * lw
      val strips = ArrayList<Strip>()
      dashes(x, y, o, 0.15, 4.5, 7.5, pal.marking, strips)
      // the lane lines follow the shift of their road
      for (st in strips) {
        val sh = DoubleArray(st.x.size) { i -> shiftY * (1 - smooth(st.x[i].coerceAtLeast(0.0) / taper.coerceAtLeast(1.0))) }
        markings += Strip(st.x, st.y, DoubleArray(st.x.size) { st.left[it] + sh[it] }, DoubleArray(st.x.size) { st.right[it] + sh[it] }, pal.marking)
      }
    }
  }
}

private fun isMotorwayRef(r: String): Boolean = r.uppercase().let { (it.startsWith("A") || it.startsWith("E")) && it.drop(1).firstOrNull()?.isDigit() == true }

/** The arrow of the sign for the road taken: the exit's side at exits and forks, else the road's real direction. */
private fun signArrow(j: LaneModel.Junction): Float {
  val exitLike = j.kind == LanePlan.Kind.EXIT || j.kind == LanePlan.Kind.FORK
  if (exitLike) return if (j.side > 0) 45f else if (j.side < 0) -45f else 0f
  val a = j.taken.angle
  return if (abs(a) < 12) 0f else a.coerceIn(-90.0, 90.0).toFloat()
}

/**
 * The sign over the lanes: one panel per destination from the map's signs (destination:lanes, or
 * the signs of the road taken and of the other main road) when it has them; otherwise a sign made
 * from the road taken, its number and its name (at the entry of a motorway: the booth, with the
 * motorway's number). Never without a sign.
 */
private fun signOf(j: LaneModel.Junction, s: JunctionScene, ourLeft: Double, laneW: Double, nLanes: Int, active: List<Int>, totalW: Double): Gantry? {
  val country = s.country
  val panels = ArrayList<Panel>()
  fun laneLeft(i: Int) = ourLeft - i * laneW
  val motorwayCtx = j.approachClass == "motorway"
  fun colorsFor(sign: EdgeSign?, toMotorway: Boolean): Pair<Color, Color> =
      sign?.colour?.let { osmSignColors(it) } ?: signColors(country, toMotorway)
  val sideArrow = signArrow(j)
  val l0 = active.minOrNull() ?: 0
  val l1 = active.maxOrNull() ?: (nLanes - 1)
  var fromMap = true
  // signs over the lanes (destination:lanes): one panel per run of lanes with the same destination
  val ls = j.laneSigns
  if (ls != null && ls.size == nLanes) {
    var i = 0
    while (i < nLanes) {
      var k = i
      while (k + 1 < nLanes && ls[k + 1].towns == ls[i].towns && ls[k + 1].refs == ls[i].refs) k++
      val d = ls[i]
      if (!d.isEmpty) {
        val taken = (i..k).any { it in active }
        val toMotorway = d.refs.any { isMotorwayRef(it) }
        val (bg, fg) = d.colour?.let { osmSignColors(it) } ?: signColors(country, toMotorway)
        panels += Panel(laneLeft(i) - 0.2, laneLeft(k + 1) + 0.2, d.refs.take(3), d.towns.take(3),
            if (taken) j.sign?.exitNumbers?.firstOrNull() else null, bg, fg, if (taken) sideArrow else 0f, taken)
      }
      i = k + 1
    }
  }
  if (panels.isEmpty()) {
    val takenSign = j.sign?.takeIf { !it.isEmpty }
    val otherSign = j.otherSign?.takeIf { !it.isEmpty }
    val others = (0 until nLanes).filter { it !in active }
    if (takenSign != null) {
      val toMotorway = s.tollEntry || !j.taken.ramp && j.taken.roadClass == "motorway" || takenSign.branches.any { isMotorwayRef(it) }
      val (bg, fg) = colorsFor(takenSign, toMotorway)
      // at the entry of a motorway: its number first and the booth named, with the map's destinations
      val refs = (listOfNotNull(s.tollRef.takeIf { s.tollEntry }) + takenSign.branches).distinct().take(3)
      val towns = if (s.tollEntry) (listOf("Casello") + takenSign.towards.filter { !isMotorwayRef(it) }).take(3) else takenSign.towards.take(3)
      panels += Panel(laneLeft(l0) - 0.2, laneLeft(l1 + 1) + 0.2, refs, towns, takenSign.exitNumbers.firstOrNull(), bg, fg, sideArrow, true)
    }
    if (otherSign != null && others.isNotEmpty()) {
      val (bg, fg) = colorsFor(otherSign, motorwayCtx)
      panels += Panel(laneLeft(others.min()) - 0.2, laneLeft(others.max() + 1) + 0.2, otherSign.branches.take(3), otherSign.towards.take(3), null, bg, fg, 0f, false)
    }
  }
  if (panels.isEmpty()) {
    // no sign on the map: the road taken, as the graph knows it (number, name), with the turn;
    // at the entry of a motorway the booth and the motorway's number. Nothing made up.
    fromMap = false
    // the road taken, or (a link without name) the first named or numbered road within 600 m after it
    val takenEdge = s.route.edges.firstOrNull { it.startM >= j.atM - 5 && it.startM <= j.atM + 600 && (it.refs.isNotEmpty() || it.names.any { n -> !RefLike.matches(n.trim()) }) }
        ?: s.route.edgeAt((j.atM + 15).coerceAtMost(s.route.length))
    val refs = (listOfNotNull(s.tollRef.takeIf { s.tollEntry }) + (takenEdge?.refs ?: emptyList())).distinct().take(2)
    val name = takenEdge?.names?.firstOrNull { it !in refs && it.isNotBlank() && !isMotorwayRef(it) && !RefLike.matches(it.trim()) }
    val towns = when {
      s.tollEntry -> listOfNotNull("Casello", name)
      name != null -> listOf(name)
      refs.isNotEmpty() -> emptyList()
      else -> listOf(if (j.side < 0) "A sinistra" else if (j.side > 0) "A destra" else "Dritto")
    }
    val motorway = s.tollEntry || (!j.taken.ramp && j.taken.roadClass == "motorway") || refs.any { isMotorwayRef(it) }
    val (bg, fg) = signColors(country, motorway)
    panels += Panel(laneLeft(l0) - 0.2, laneLeft(l1 + 1) + 0.2, refs, towns, null, bg, fg, sideArrow, true)
  }
  // a real panel is wider than one lane: at least 6.5 m, side by side without overlapping
  val wide = panels.map { pn ->
    val w = pn.yLeft - pn.yRight
    if (w >= 6.5) pn else { val c = (pn.yLeft + pn.yRight) / 2; Panel(c + 3.25, c - 3.25, pn.refs, pn.towns, pn.exit, pn.bg, pn.fg, pn.arrowDeg, pn.taken) }
  }.sortedByDescending { it.yLeft }.toMutableList()
  for (i in 1 until wide.size) {
    val prev = wide[i - 1]; val cur = wide[i]
    if (cur.yLeft > prev.yRight - 0.3) {
      val shift = cur.yLeft - (prev.yRight - 0.3)
      wide[i] = Panel(cur.yLeft - shift, cur.yRight - shift, cur.refs, cur.towns, cur.exit, cur.bg, cur.fg, cur.arrowDeg, cur.taken)
    }
  }
  // the posts at the edges of the carriageway
  val left = totalW / 2 + 1.0
  val right = -totalW / 2 - 1.0
  return Gantry(0.0, 0.0, 0.0, 1.0, left, right, wide, fromMap)
}

private val RefLike = Regex("^[A-Z]{1,3}[ -]?\\d{1,4}[a-z]?$")

// ------------------------------------------------------------------------------------- projection

/**
 * The eye's projection: the line of sight is [Scene.forward] tilted down so that the horizon is at
 * [Scene.horizonFrac] of the height, the focal length from the horizontal field of view (never
 * narrower than [MIN_VFOV_DEG] vertically).
 */
internal class Projector(scene: Scene, val w: Float, val h: Float) {
  private val c = scene.camera
  private val fx: DoubleArray; private val rx: DoubleArray; private val ux: DoubleArray
  val focal: Double
  private val pitch: Double
  init {
    val fh = (w / 2.0) / tan(Math.toRadians(scene.hfovDeg / 2))
    val fv = (h / 2.0) / tan(Math.toRadians(MIN_VFOV_DEG / 2))
    focal = min(fh, fv)
    pitch = atan((0.5 - scene.horizonFrac) * h / focal)
    val d = scene.forward
    val cp = cos(pitch); val sp = sin(pitch)
    fx = doubleArrayOf(d[0] * cp, d[1] * cp, -sp)
    // right = forward × up (y is to the left), up' = right × forward
    rx = doubleArrayOf(d[1], -d[0], 0.0)
    ux = doubleArrayOf(rx[1] * fx[2] - rx[2] * fx[1], rx[2] * fx[0] - rx[0] * fx[2], rx[0] * fx[1] - rx[1] * fx[0])
  }
  /** Screen x, y and depth of a point, or null behind the camera. */
  fun project(x: Double, y: Double, z: Double): FloatArray? {
    val dx = x - c[0]; val dy = y - c[1]; val dz = z - c[2]
    val zc = dx * fx[0] + dy * fx[1] + dz * fx[2]
    if (zc < 1.5) return null
    val xc = dx * rx[0] + dy * rx[1] + dz * rx[2]
    val yc = dx * ux[0] + dy * ux[1] + dz * ux[2]
    return floatArrayOf((w / 2 + focal * xc / zc).toFloat(), (h / 2 - focal * yc / zc).toFloat(), zc.toFloat())
  }
  /** Where the horizon is (screen y). */
  val horizon: Float get() = (h / 2 - focal * tan(pitch)).toFloat()
}

/** The quads of a strip, with their depth, for the painter (far ones first). */
private fun quads(st: Strip, p: Projector, out: MutableList<Pair<Float, Path>>, maxDepth: Float = 900f) {
  for (i in 0 until st.x.size - 1) {
    val dx = st.x[i + 1] - st.x[i]; val dy = st.y[i + 1] - st.y[i]
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-6) continue
    val nx = -dy / len; val ny = dx / len
    val a = p.project(st.x[i] + nx * st.left[i], st.y[i] + ny * st.left[i], st.z) ?: continue
    val b = p.project(st.x[i + 1] + nx * st.left[i + 1], st.y[i + 1] + ny * st.left[i + 1], st.z) ?: continue
    val c = p.project(st.x[i + 1] + nx * st.right[i + 1], st.y[i + 1] + ny * st.right[i + 1], st.z) ?: continue
    val d = p.project(st.x[i] + nx * st.right[i], st.y[i] + ny * st.right[i], st.z) ?: continue
    val depth = (a[2] + b[2] + c[2] + d[2]) / 4
    if (depth > maxDepth) continue
    out += depth to Path().apply { moveTo(a[0], a[1]); lineTo(b[0], b[1]); lineTo(c[0], c[1]); lineTo(d[0], d[1]); close() }
  }
}

/** The fixed part of the picture, drawn once into a bitmap. */
internal fun renderStatic(scene: Scene, w: Int, h: Int, density: Density): ImageBitmap {
  val img = ImageBitmap(w, h)
  val canvas = ComposeCanvas(img)
  val ds = CanvasDrawScope()
  ds.draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
    val p = Projector(scene, size.width, size.height)
    val hy = p.horizon.coerceIn(0f, size.height)
    val pal = scene.palette
    drawRect(Brush.verticalGradient(listOf(pal.skyTop, pal.skyLow), 0f, hy.coerceAtLeast(1f)), Offset.Zero, Size(size.width, hy))
    drawRect(Brush.verticalGradient(listOf(pal.groundFar, pal.groundNear), hy, size.height), Offset(0f, hy), Size(size.width, size.height - hy))
    // the roads, the far ones first, fading into the distance
    for (layer in listOf(scene.asphalt.filter { it.color == pal.side }, scene.asphalt.filter { it.color != pal.side })) {
      val ground = ArrayList<Triple<Float, Path, Color>>()
      for (st in layer) { val q = ArrayList<Pair<Float, Path>>(); quads(st, p, q); for ((d, path) in q) ground += Triple(d, path, st.color) }
      ground.sortByDescending { it.first }
      for ((d, path, c) in ground) drawPath(path, androidx.compose.ui.graphics.lerp(c, pal.asphaltFar, (d / 600f).coerceIn(0f, 0.7f)))
    }
    // the lanes to take, the markings over them, then the arrow heads in the lanes to take
    val lit = ArrayList<Pair<Float, Path>>()
    for (st in scene.lit) quads(st, p, lit, 700f)
    lit.sortByDescending { it.first }
    for ((_, path) in lit) drawPath(path, pal.lit.copy(alpha = 0.85f))
    val lines = ArrayList<Pair<Float, Path>>()
    for (st in scene.markings) quads(st, p, lines, 450f)
    lines.sortByDescending { it.first }
    for ((d, path) in lines) drawPath(path, pal.marking.copy(alpha = (1f - d / 500f).coerceIn(0.25f, 1f)))
    for ((poly, color) in scene.paint) {
      val pts = poly.map { p.project(it[0], it[1], 0.02) ?: return@map null }
      if (pts.any { it == null } || pts.size < 3) continue
      drawPath(Path().apply { moveTo(pts[0]!![0], pts[0]!![1]); for (k in 1 until pts.size) lineTo(pts[k]!![0], pts[k]!![1]); close() }, color)
    }
    scene.gantry?.let { drawSign(it, scene, p, density) }
  }
  return img
}

/** A panel laid out on the screen. */
private class PanelBox(val pn: Panel, var x0: Float, var x1: Float, val minW: Float, val cx: Float)

/**
 * The sign: its panels at the top of the picture, each over the lanes it is for (as the eye sees
 * them a little ahead), as wide as its text needs, the text never smaller than readable from the
 * driver's seat; under them the beam and, down to the road's edges, the posts.
 */
private fun DrawScope.drawSign(g: Gantry, scene: Scene, p: Projector, density: Density) {
  val dp = density.density
  val pal = scene.palette
  val w = size.width; val h = size.height
  val margin = 10f * dp
  // the close button sits at the top right of the picture
  val marginRight = 54f * dp
  val textMin = 18f * dp
  var textPx = (h * 0.052f).coerceIn(textMin, 30f * dp)
  val arrowSpace = { t: Float -> t * 2.0f }
  val pad = { t: Float -> t * 0.55f }
  fun paintOf(t: Float, color: Color) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); textSize = t
  }
  fun plateW(t: Float, ref: String) = paintOf(t * 0.92f, Color.White).measureText(ref.uppercase()) + t * 0.9f
  fun minWidth(pn: Panel, t: Float): Float {
    val tp = paintOf(t, Color.White)
    val refs = if (pn.refs.isEmpty()) 0f else pn.refs.sumOf { plateW(t, it).toDouble() }.toFloat() + t * 0.3f * (pn.refs.size - 1)
    val towns = pn.towns.maxOfOrNull { tp.measureText(it) } ?: 0f
    return max(refs, towns) + pad(t) * 2 + arrowSpace(t) + (if (pn.exit != null) t * 2.3f else 0f)
  }
  // where the lanes of each panel are on the screen
  val boxes = g.panels.map { pn ->
    val xl = g.at(pn.yLeft, 0.0, p)?.get(0) ?: (w * 0.3f)
    val xr = g.at(pn.yRight, 0.0, p)?.get(0) ?: (w * 0.7f)
    PanelBox(pn, min(xl, xr), max(xl, xr), minWidth(pn, textPx), (xl + xr) / 2)
  }.sortedBy { it.cx }
  val avail = w - margin - marginRight - (boxes.size - 1) * 6f * dp
  val minSum = boxes.sumOf { it.minW.toDouble() }.toFloat()
  // too much text for the width: smaller letters, down to the readable minimum
  if (minSum > avail && textPx > textMin) textPx = max(textMin, textPx * avail / minSum)
  // each panel at least its minimum width, centred on its lanes; then side by side without overlapping, inside the picture
  val share = if (boxes.isEmpty()) 1f else (avail / boxes.sumOf { minWidth(it.pn, textPx).toDouble() }.toFloat()).coerceAtMost(1f)
  for (b in boxes) {
    val mw = minWidth(b.pn, textPx) * share
    if (b.x1 - b.x0 < mw) { b.x0 = b.cx - mw / 2; b.x1 = b.cx + mw / 2 }
    b.x0 = b.x0.coerceAtLeast(margin); b.x1 = b.x1.coerceAtMost(w - marginRight)
    if (b.x1 - b.x0 < mw) { if (b.x0 <= margin + 0.5f) b.x1 = b.x0 + mw else b.x0 = b.x1 - mw }
  }
  for (i in 1 until boxes.size) {
    val prev = boxes[i - 1]; val cur = boxes[i]
    val gap = 6f * dp
    if (cur.x0 < prev.x1 + gap) { val d = prev.x1 + gap - cur.x0; cur.x0 += d; cur.x1 += d }
  }
  for (i in boxes.indices.reversed()) {
    val b = boxes[i]
    if (b.x1 > w - marginRight) { val d = b.x1 - (w - marginRight); b.x0 -= d; b.x1 -= d }
    if (i > 0) { val prev = boxes[i - 1]; val gap = 6f * dp; if (prev.x1 + gap > b.x0) { val d = prev.x1 + gap - b.x0; prev.x0 -= d; prev.x1 -= d } }
  }
  // the height: the lines at the text size, at most a third of the picture
  val maxLines = boxes.maxOfOrNull { (if (it.pn.refs.isEmpty()) 0 else 1) + it.pn.towns.size }?.coerceAtLeast(1) ?: 1
  val lineH = textPx * 1.22f
  var lines = min(maxLines, 4)
  var boxH = lineH * lines + pad(textPx) * 1.6f
  while (boxH > h * 0.36f && lines > 1) { lines--; boxH = lineH * lines + pad(textPx) * 1.6f }
  val top = margin
  val bottom = top + boxH
  // the beam under the panels and the posts down to the road's edges
  if (boxes.isNotEmpty()) {
    val beamL = boxes.first().x0 - 4f * dp; val beamR = boxes.last().x1 + 4f * dp
    val pl = g.at(g.yLeft, 0.0, p); val pr = g.at(g.yRight, 0.0, p)
    val postW = 4f * dp
    if (pl != null) drawLine(pal.post, Offset(pl[0].coerceIn(beamL, beamR), bottom), Offset(pl[0], pl[1]), postW)
    if (pr != null) drawLine(pal.post, Offset(pr[0].coerceIn(beamL, beamR), bottom), Offset(pr[0], pr[1]), postW)
    drawRect(pal.beam, Offset(beamL, bottom), Size(beamR - beamL, 5f * dp))
  }
  for (b in boxes) {
    val pn = b.pn
    val x0 = b.x0; val x1 = b.x1
    val bw = x1 - x0
    val bg = if (pn.taken) pn.bg else androidx.compose.ui.graphics.lerp(pn.bg, Color(0xFF6E7883), 0.4f)
    val fg = if (pn.taken) pn.fg else pn.fg.copy(alpha = 0.8f)
    val r = androidx.compose.ui.geometry.CornerRadius(5f * dp)
    drawRoundRect(Color(0x66000000), Offset(x0 + 2f * dp, top + 3f * dp), Size(bw, boxH), r)
    drawRoundRect(bg, Offset(x0, top), Size(bw, boxH), r)
    drawRoundRect(fg.copy(alpha = 0.9f), Offset(x0, top), Size(bw, boxH), r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * dp))
    val tp = paintOf(textPx, fg)
    var y = top + pad(textPx) * 0.8f + textPx
    var textLeft = x0 + pad(textPx)
    val textRight = x1 - arrowSpace(textPx) - pad(textPx) * 0.4f
    pn.exit?.let { ex ->
      // the exit number in its own box at the top left
      val box = android.graphics.RectF(textLeft, top + pad(textPx) * 0.6f, textLeft + textPx * 1.9f, top + pad(textPx) * 0.6f + textPx * 1.25f)
      val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fg.toArgb(); style = android.graphics.Paint.Style.STROKE; strokeWidth = textPx * 0.08f }
      drawContext.canvas.nativeCanvas.drawRoundRect(box, textPx * 0.2f, textPx * 0.2f, boxPaint)
      val small = android.graphics.Paint(tp).apply { textSize = textPx * 0.8f; textAlign = android.graphics.Paint.Align.CENTER }
      drawContext.canvas.nativeCanvas.drawText(ex, box.centerX(), box.centerY() + small.textSize * 0.35f, small)
      textLeft += textPx * 2.3f
    }
    var drawn = 0
    if (pn.refs.isNotEmpty() && drawn < lines) {
      // the road numbers as plates, coloured like the country paints them
      var x = textLeft
      for (ref in pn.refs) {
        val pw = plateW(textPx, ref)
        if (x + pw > textRight + 1f) break
        val (pbg, pfg) = refPlateColors(ref, scene.country)
        val plate = android.graphics.RectF(x, y - textPx * 0.98f, x + pw, y + textPx * 0.26f)
        drawContext.canvas.nativeCanvas.drawRoundRect(plate, textPx * 0.18f, textPx * 0.18f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = (if (pn.taken) pbg else androidx.compose.ui.graphics.lerp(pbg, Color(0xFF6E7883), 0.4f)).toArgb() })
        drawContext.canvas.nativeCanvas.drawRoundRect(plate, textPx * 0.18f, textPx * 0.18f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pfg.toArgb(); style = android.graphics.Paint.Style.STROKE; strokeWidth = textPx * 0.07f })
        val pt = android.graphics.Paint(tp).apply { color = pfg.toArgb(); textSize = textPx * 0.92f; textAlign = android.graphics.Paint.Align.CENTER }
        drawContext.canvas.nativeCanvas.drawText(ref.uppercase(), plate.centerX(), y, pt)
        x += pw + textPx * 0.3f
      }
      y += lineH; drawn++
    }
    for (town in pn.towns) {
      if (drawn >= lines) break
      var t = town
      while (t.length > 3 && tp.measureText(t) > textRight - textLeft) t = t.dropLast(2) + "…"
      drawContext.canvas.nativeCanvas.drawText(t, textLeft, y, tp)
      y += lineH; drawn++
    }
    // the arrow of the panel: where its lanes go
    val cx = x1 - arrowSpace(textPx) * 0.5f - pad(textPx) * 0.3f
    val cy = top + boxH * 0.5f
    val s = textPx * 0.75f
    val ang = Math.toRadians(pn.arrowDeg.toDouble())
    val dxA = (sin(ang) * s).toFloat(); val dyA = (-cos(ang) * s).toFloat()
    drawLine(fg, Offset(cx - dxA, cy - dyA), Offset(cx + dxA, cy + dyA), strokeWidth = s * 0.3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    val hx = cx + dxA; val hy = cy + dyA
    val px = -dyA * 0.6f; val py = dxA * 0.6f
    drawPath(Path().apply { moveTo(hx + dxA * 0.55f, hy + dyA * 0.55f); lineTo(hx + px, hy + py); lineTo(hx - px, hy - py); close() }, fg)
  }
}

/** The vehicle's mark on the picture at [alongM] metres of the route, in [lane] (null: the lanes to take). */
internal fun DrawScope.drawVehicle(scene: Scene, alongM: Double, lane: Int?) {
  if (alongM < scene.visibleFromM) return
  val total = scene.routeX.size - 1
  if (total < 1) return
  val fi = (alongM - scene.routeFrom) / scene.routeStep
  if (fi < 0 || fi >= total) return
  val p = Projector(scene, size.width, size.height)
  val k = fi.toInt().coerceIn(0, total - 1)
  val t = fi - k
  val x = scene.routeX[k] + (scene.routeX[k + 1] - scene.routeX[k]) * t
  val y = scene.routeY[k] + (scene.routeY[k + 1] - scene.routeY[k]) * t
  val dx = scene.routeX[k + 1] - scene.routeX[k]; val dy = scene.routeY[k + 1] - scene.routeY[k]
  val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
  val ux = dx / len; val uy = dy / len
  val nx = -uy; val ny = ux
  // before the junction in its real lane when known, else (and after it) on the lanes to take
  val off = if (alongM < scene.nodeM - 15 && lane != null && lane in scene.laneY.indices) scene.laneY[lane]
      else scene.routeLaneY[k] + (scene.routeLaneY[k + 1] - scene.routeLaneY[k]) * t
  val cx = x + nx * off; val cy = y + ny * off
  // a chevron on the road, 6 m long and 2.6 m wide, never smaller than a fingertip on the screen
  // (seen from the eye, a flat mark far away would be a line); with its shadow
  val c0 = p.project(cx, cy, 0.3) ?: return
  val c1 = p.project(cx + ux * 3.0, cy + uy * 3.0, 0.3) ?: return
  var dxs = c1[0] - c0[0]; var dys = c1[1] - c0[1]
  val ls = sqrt(dxs * dxs + dys * dys)
  if (ls < 1e-3f) { dxs = 0f; dys = -1f } else { dxs /= ls; dys /= ls }
  val minL = 16f * density
  val lenS = max(ls * 2, minL)
  val wid = max((p.focal * 2.6 / c0[2]).toFloat(), minL * 0.8f)
  val tip = floatArrayOf(c0[0] + dxs * lenS * 0.55f, c0[1] + dys * lenS * 0.55f)
  val bl = floatArrayOf(c0[0] - dxs * lenS * 0.45f - dys * wid / 2, c0[1] - dys * lenS * 0.45f + dxs * wid / 2)
  val br = floatArrayOf(c0[0] - dxs * lenS * 0.45f + dys * wid / 2, c0[1] - dys * lenS * 0.45f - dxs * wid / 2)
  val mid = floatArrayOf(c0[0] - dxs * lenS * 0.2f, c0[1] - dys * lenS * 0.2f)
  val shape = Path().apply { moveTo(tip[0], tip[1]); lineTo(bl[0], bl[1]); lineTo(mid[0], mid[1]); lineTo(br[0], br[1]); close() }
  // dark on the blue lane, with a white rim: unlike the white arrow heads of the lane
  drawPath(shape, Color(0x66000000), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 7f))
  drawPath(shape, Color(0xFF0B2A5C))
  drawPath(shape, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
}

/**
 * The junction view: the picture of the junction, a bar with the distance to it, and under it the
 * lanes (the same strip as on the map). [vehAlong]: where the vehicle is along the route (metres).
 */
@Composable
fun JunctionView(scene: JunctionScene, night: Boolean, modifier: Modifier = Modifier, fill: Boolean = false,
                 height: Dp = 260.dp, vehAlong: State<Double>? = null) {
  // the same junction keeps its picture while the point wobbles by a few metres
  val key = remember(scene.route.key, (scene.maneuverAtM / 60.0).roundToLong()) { Any() }
  var closed by remember(key) { mutableStateOf(false) }
  if (closed) return
  Column(modifier.shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color(0xFF11161C))) {
    BoxWithConstraints(Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else Modifier.height(height))) {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val wPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
      val hPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
      val built by produceState<Pair<Scene, ImageBitmap>?>(null, key, night, wPx, hPx) {
        value = withContext(Dispatchers.Default) {
          runCatching { buildScene(scene, night)?.let { sc -> sc to renderStatic(sc, wPx, hPx, density) } }
              .onFailure { android.util.Log.w("NmJunction", "vista svincolo", it) }.getOrNull()
        }
      }
      val pic = built
      if (pic != null) {
        Canvas(Modifier.fillMaxSize().testTag("jv-picture")) {
          drawImage(pic.second)
          val along = vehAlong?.value?.takeIf { it >= 0 } ?: scene.traveledM
          drawVehicle(pic.first, along, scene.vehicleLane)
          // the distance: a bar on the right that empties as the junction comes
          val f = (scene.distanceM / scene.rangeM).coerceIn(0.0, 1.0).toFloat()
          val bw = size.width * 0.022f
          val bx = size.width - bw * 2.2f
          val top = size.height * 0.42f; val bot = size.height * 0.84f
          drawRoundRect(Color(0x66000000), Offset(bx, top), Size(bw, bot - top), androidx.compose.ui.geometry.CornerRadius(bw / 2))
          drawRoundRect(if (scene.distanceM < 300) NmAmber else Color(0xFF2F7CF6), Offset(bx, bot - (bot - top) * f), Size(bw, (bot - top) * f),
              androidx.compose.ui.geometry.CornerRadius(bw / 2))
        }
      }
      Text(Fmt.distanceText(scene.distanceM), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
      Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color(0x99000000)).clickable { closed = true },
          contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 17.sp) }
    }
    // the lanes, the same strip as on the map
    val plan = scene.junction.plan
    if (plan.lanes.isNotEmpty()) {
      BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        val w = min(maxWidth.value, plan.lanes.size * 60f).dp
        Canvas(Modifier.width(w).fillMaxHeight()) { drawLanes(plan, scene.vehicleLane) }
      }
    }
  }
}
