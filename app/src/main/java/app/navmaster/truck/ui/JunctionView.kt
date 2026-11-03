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
 * The junction view: the junction as it is, seen from a fixed point before it, as on the dedicated
 * truck navigators. The roads of the graph with their real number of lanes and their markings, the
 * lanes to take lit up (the same ones as in the lane strip and the voice: nav.LaneModel), going on
 * along the road taken, the gantry with the direction signs over the right lanes when the map has
 * them, a bar with the distance. The picture is drawn once per junction; only the vehicle's mark
 * moves on it, in its lane.
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
  return JunctionScene(j, distanceM, range, JvRoute.of(a), traveled, at, country, vehicleLane)
}

// ---------------------------------------------------------------------------------- the picture

internal class Palette(night: Boolean) {
  val skyTop = if (night) Color(0xFF0B1220) else Color(0xFF8FB9E6)
  val skyLow = if (night) Color(0xFF1C2635) else Color(0xFFDCE9F6)
  val groundFar = if (night) Color(0xFF1A2026) else Color(0xFFD9E2CF)
  val groundNear = if (night) Color(0xFF151A1F) else Color(0xFFC3D2B1)
  val asphalt = if (night) Color(0xFF3A3F47) else Color(0xFF6C7179)
  val asphaltFar = if (night) Color(0xFF2B3038) else Color(0xFFA3A8AE)
  val shoulder = if (night) Color(0xFF30353C) else Color(0xFF8E9298)
  val marking = if (night) Color(0xFFC9CED4) else Color(0xFFFFFFFF)
  val lit = Color(0xFF2F7CF6)
  val litEdge = Color(0xFF9CC4FF)
  val arrow = Color(0xF2FFFFFF)
  val post = if (night) Color(0xFF6A7078) else Color(0xFF8D949C)
}

/** A strip of ground along a line: the centre line in the junction's frame and, at each point, its left and right edges (metres, left positive). */
internal class Strip(val x: DoubleArray, val y: DoubleArray, val left: DoubleArray, val right: DoubleArray, val color: Color, val z: Double = 0.0)

/** What is drawn, in metres: x along the road arriving at the junction (the node at 0), y to the left, z up. */
internal class Scene(
    val asphalt: List<Strip>,
    val markings: List<Strip>,
    val lit: List<Strip>,
    val arrow: Strip?,
    val arrowHead: List<DoubleArray>?,
    val gantry: Gantry?,
    val camera: DoubleArray,
    val target: DoubleArray,
    val vfovDeg: Double,
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
)

internal class Panel(val yLeft: Double, val yRight: Double, val lines: List<String>, val exit: String?, val bg: Color, val fg: Color, val arrowDeg: Float, val taken: Boolean)
/** The gantry across the road at the route's point ([px], [py]), its offsets along the normal ([nx], [ny]) of the road there. */
internal class Gantry(val px: Double, val py: Double, val nx: Double, val ny: Double, val yLeft: Double, val yRight: Double, val panels: List<Panel>) {
  fun at(o: Double, z: Double, p: Projector): FloatArray? = p.project(px + nx * o, py + ny * o, z)
}

private const val LANE_MOTORWAY = 3.5
private const val LANE_MAIN = 3.4
private const val LANE_LOCAL = 3.0

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
  val back = if (exitLike) 300.0 else 160.0
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
    markings += stripOf(bx, by, carriageRight + 0.35, carriageRight + 0.15, pal.marking)
    markings += stripOf(bx, by, carriageLeft - 0.15, carriageLeft - 0.35, pal.marking)
    for (k in 1 until nLanes) dashes(bx, by, ourLeft - k * laneW, 0.15, if (exitLike) 4.5 else 3.0, if (exitLike) 7.5 else 4.5, pal.marking, markings)
    if (!approachOneway) dashes(bx, by, if (lht) ourLeft - nLanes * laneW else ourLeft, 0.15, 3.0, 4.5, pal.marking, markings)
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
    markings += Strip(tx, ty, DoubleArray(tx.size) { shift[it] + takenW / 2 - 0.15 }, DoubleArray(tx.size) { shift[it] + takenW / 2 - 0.35 }, pal.marking)
    markings += Strip(tx, ty, DoubleArray(tx.size) { shift[it] - takenW / 2 + 0.35 }, DoubleArray(tx.size) { shift[it] - takenW / 2 + 0.15 }, pal.marking)
    if (takenOneway) {
      for (k in 1 until takenLanes) dashes(tx, ty, takenW / 2 - k * takenLw, 0.15, 4.5, 7.5, pal.marking, markings)
      lit += Strip(tx, ty, DoubleArray(tx.size) { shift[it] + takenW / 2 - 0.25 }, DoubleArray(tx.size) { shift[it] - takenW / 2 + 0.25 }, pal.lit)
    } else {
      dashes(tx, ty, 0.0, 0.15, 3.0, 4.5, pal.marking, markings)
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
  for (r in roads) {
    if (r.pts.size < 2 || r.tunnel) continue
    val local = r.pts.map { f.local(it.lat, it.lng) }
    var xs = local.map { it[0] }; var ys = local.map { it[1] }
    val isOther = r.osm == otherWay && otherWay != 0L
    if (r.osm in routeWays && !isOther) {
      // the route's own roads are drawn from the route's line (above); keep what leaves it
      val keep = xs.indices.map { i -> nearestRoute(xs[i], ys[i], rx, ry) > 2.5 }
      val parts = splitRuns(xs, ys, keep)
      for ((px, py) in parts) addRoad(r, px, py, false, 0.0, 0.0, asphalt, markings, pal, lht)
      continue
    }
    if (isOther) {
      // only after the node, starting where its lanes are
      val keep = xs.indices.map { xs[it] >= -1.0 }
      for ((px, py) in splitRuns(xs, ys, keep)) addRoad(r, px, py, true, otherY, taper, asphalt, markings, pal, lht, forcedWidth = otherW)
      continue
    }
    addRoad(r, xs, ys, false, 0.0, 0.0, asphalt, markings, pal, lht)
  }

  // the guidance arrow on the lanes to take: from 70 m before the node to 45 m after it
  var arrow: Strip? = null
  var head: List<DoubleArray>? = null
  val aIdx = (0..n).filter { routeAlong[it] in (node - 70)..(node + 45) }
  if (aIdx.size >= 3 && active.isNotEmpty()) {
    val ax = sub(aIdx, rx); val ay = sub(aIdx, ry); val ly = sub(aIdx, routeLaneY)
    val shaftEnd = ax.size - 3
    arrow = Strip(ax.copyOfRange(0, shaftEnd), ay.copyOfRange(0, shaftEnd), DoubleArray(shaftEnd) { ly[it] + 0.45 }, DoubleArray(shaftEnd) { ly[it] - 0.45 }, pal.arrow)
    // the head: a triangle 6 m long, 2.6 m wide, along the line's direction
    val i1 = ax.size - 1; val i0 = shaftEnd - 1
    val dx = ax[i1] - ax[i0]; val dy = ay[i1] - ay[i0]; val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
    val nx = -dy / len; val ny = dx / len
    val bx = ax[i0] + nx * ly[i0]; val by = ay[i0] + ny * ly[i0]
    val tx = bx + dx / len * 6.0; val ty = by + dy / len * 6.0
    head = listOf(doubleArrayOf(bx + nx * 1.3, by + ny * 1.3), doubleArrayOf(tx, ty), doubleArrayOf(bx - nx * 1.3, by - ny * 1.3))
  }

  // the normal (to the left) of the route's line at point i
  fun normal(i: Int): DoubleArray {
    val i0 = (i - 2).coerceAtLeast(0); val i1 = (i + 2).coerceAtMost(n)
    val dx = rx[i1] - rx[i0]; val dy = ry[i1] - ry[i0]
    val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
    return doubleArrayOf(-dy / l, dx / l)
  }
  // the camera: fixed, behind and high above the road arriving, looking down at the junction as
  // the dedicated navigators do (the whole junction and the roads leaving it in the picture, the
  // horizon near the top)
  val camBack = if (exitLike) 150.0 else 70.0
  val camH = if (exitLike) 32.0 else 26.0
  // the gantry: over the road arriving, halfway between the camera and the node, when the map has the signs
  val gi = (0..n).minByOrNull { abs(routeAlong[it] - (node - camBack * 0.5)) } ?: 0
  val gantry = gantryOf(j, s.country, ourLeft, laneW, nLanes, active)?.let { g ->
    val nn = normal(gi)
    Gantry(rx[gi], ry[gi], nn[0], nn[1], g.yLeft, g.yRight, g.panels)
  }

  val ci = (0..n).minByOrNull { abs(routeAlong[it] - (node - camBack)) } ?: 0
  // over the middle of our lanes, looking a little past the junction, towards the lanes to take
  val camY = (ourLeft - nLanes * laneW / 2)
  val cn = normal(ci)
  val camera = doubleArrayOf(rx[ci] + cn[0] * camY, ry[ci] + cn[1] * camY, camH)
  val ti = (0..n).minByOrNull { abs(routeAlong[it] - (node + if (exitLike) 70.0 else 15.0)) } ?: n
  val tn = normal(ti)
  val tY = camY * 0.5 + activeY * 0.3
  // halfway between the route's point there and the straight line ahead: the other road stays in the picture
  val target = doubleArrayOf((rx[ti] + tn[0] * tY + (if (exitLike) 70.0 else 15.0)) / 2, (ry[ti] + tn[1] * tY + tY) / 2, 0.0)
  return Scene(asphalt, markings, lit, arrow, head, gantry, camera, target, if (exitLike) 44.0 else 52.0,
      from, (to - from) / n, node, routeAlong[ci] + camH * 1.8, rx.copyOf(), ry.copyOf(), routeLaneY, laneY, laneW, pal)
}

private fun nearestRoute(x: Double, y: Double, rx: DoubleArray, ry: DoubleArray): Double {
  var best = Double.MAX_VALUE
  for (i in 0 until rx.size - 1) {
    val dx = rx[i + 1] - rx[i]; val dy = ry[i + 1] - ry[i]
    val l2 = dx * dx + dy * dy
    val t = if (l2 > 0) (((x - rx[i]) * dx + (y - ry[i]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
    val px = rx[i] + dx * t - x; val py = ry[i] + dy * t - y
    best = min(best, sqrt(px * px + py * py))
  }
  return best
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
                    asphalt: MutableList<Strip>, markings: MutableList<Strip>, pal: Palette, lht: Boolean, forcedWidth: Double? = null) {
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
  asphalt += Strip(x, y, DoubleArray(x.size) { shift[it] + w / 2 + 0.6 }, DoubleArray(x.size) { shift[it] - w / 2 - 0.6 }, pal.asphalt)
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

/** The gantry with the direction signs, when the map has them: one panel per destination over its lanes. */
private fun gantryOf(j: LaneModel.Junction, country: String?, ourLeft: Double, laneW: Double, nLanes: Int, active: List<Int>): Gantry? {
  val exitLike = j.kind == LanePlan.Kind.EXIT || j.kind == LanePlan.Kind.FORK
  if (!exitLike) return null
  val panels = ArrayList<Panel>()
  fun laneLeft(i: Int) = ourLeft - i * laneW
  val motorwayCtx = j.approachClass == "motorway"
  fun colorsFor(sign: EdgeSign?, toMotorway: Boolean): Pair<Color, Color> =
      sign?.colour?.let { osmSignColors(it) } ?: signColors(country, toMotorway)
  fun linesOf(sign: EdgeSign): List<String> {
    val refs = sign.branches.take(3).joinToString("  ")
    val towns = sign.towards.take(3)
    return listOfNotNull(refs.takeIf { it.isNotBlank() }) + towns
  }
  val sideArrow = if (j.side > 0) 45f else if (j.side < 0) -45f else 0f
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
        val toMotorway = d.refs.any { r -> r.uppercase().let { (it.startsWith("A") || it.startsWith("E")) && it.drop(1).firstOrNull()?.isDigit() == true } }
        val (bg, fg) = d.colour?.let { osmSignColors(it) } ?: signColors(country, toMotorway)
        panels += Panel(laneLeft(i) - 0.2, laneLeft(k + 1) + 0.2, listOfNotNull(d.refs.take(3).joinToString("  ").takeIf { it.isNotBlank() }) + d.towns.take(3),
            if (taken) j.sign?.exitNumbers?.firstOrNull() else null, bg, fg, if (taken) sideArrow else 0f, taken)
      }
      i = k + 1
    }
  } else {
    val takenSign = j.sign?.takeIf { !it.isEmpty }
    val otherSign = j.otherSign?.takeIf { !it.isEmpty }
    if (takenSign == null && otherSign == null) return null
    val l0 = active.minOrNull() ?: return null
    val l1 = active.maxOrNull() ?: return null
    val others = (0 until nLanes).filter { it !in active }
    if (takenSign != null) {
      val toMotorway = !j.taken.ramp && j.taken.roadClass == "motorway" ||
          takenSign.branches.any { r -> r.uppercase().let { (it.startsWith("A") || it.startsWith("E")) && it.drop(1).firstOrNull()?.isDigit() == true } }
      val (bg, fg) = colorsFor(takenSign, toMotorway)
      panels += Panel(laneLeft(l0) - 0.2, laneLeft(l1 + 1) + 0.2, linesOf(takenSign), takenSign.exitNumbers.firstOrNull(), bg, fg, sideArrow, true)
    }
    if (otherSign != null && others.isNotEmpty()) {
      val (bg, fg) = colorsFor(otherSign, motorwayCtx)
      panels += Panel(laneLeft(others.min()) - 0.2, laneLeft(others.max() + 1) + 0.2, linesOf(otherSign), null, bg, fg, 0f, false)
    }
  }
  if (panels.isEmpty()) return null
  // a real panel is wider than one lane: at least 6.5 m, side by side without overlapping
  val wide = panels.map { pn ->
    val w = pn.yLeft - pn.yRight
    if (w >= 6.5) pn else { val c = (pn.yLeft + pn.yRight) / 2; Panel(c + 3.25, c - 3.25, pn.lines, pn.exit, pn.bg, pn.fg, pn.arrowDeg, pn.taken) }
  }.sortedByDescending { it.yLeft }.toMutableList()
  for (i in 1 until wide.size) {
    val prev = wide[i - 1]; val cur = wide[i]
    if (cur.yLeft > prev.yRight - 0.3) {
      val shift = cur.yLeft - (prev.yRight - 0.3)
      wide[i] = Panel(cur.yLeft - shift, cur.yRight - shift, cur.lines, cur.exit, cur.bg, cur.fg, cur.arrowDeg, cur.taken)
    }
  }
  val left = maxOf(ourLeft + 1.2, wide.first().yLeft + 0.6)
  val right = minOf(ourLeft - nLanes * laneW - 1.2, wide.last().yRight - 0.6)
  return Gantry(0.0, 0.0, 0.0, 1.0, left, right, wide)
}

// ------------------------------------------------------------------------------------- projection

internal class Projector(scene: Scene, val w: Float, val h: Float) {
  private val c = scene.camera
  private val fx: DoubleArray; private val rx: DoubleArray; private val ux: DoubleArray
  private val focal: Double
  init {
    val t = scene.target
    var f = doubleArrayOf(t[0] - c[0], t[1] - c[1], t[2] - c[2])
    val fl = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]); f = doubleArrayOf(f[0] / fl, f[1] / fl, f[2] / fl)
    // right = f × up (y is to the left), up' = right × f
    var r = doubleArrayOf(f[1] * 1.0 - f[2] * 0.0, f[2] * 0.0 - f[0] * 1.0, 0.0)
    val rl = sqrt(r[0] * r[0] + r[1] * r[1]).coerceAtLeast(1e-9); r = doubleArrayOf(r[0] / rl, r[1] / rl, 0.0)
    val u = doubleArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
    fx = f; rx = r; ux = u
    focal = (h / 2.0) / tan(Math.toRadians(scene.vfovDeg / 2))
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
  val horizon: Float get() = (h / 2 - focal * ux[0] / fx[0]).toFloat()
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
    val ground = ArrayList<Pair<Float, Path>>()
    for (st in scene.asphalt) quads(st, p, ground)
    ground.sortByDescending { it.first }
    for ((d, path) in ground) drawPath(path, androidx.compose.ui.graphics.lerp(pal.asphalt, pal.asphaltFar, (d / 700f).coerceIn(0f, 1f)))
    // the lanes to take, the markings over them, then the guidance arrow
    val lit = ArrayList<Pair<Float, Path>>()
    for (st in scene.lit) quads(st, p, lit, 700f)
    lit.sortByDescending { it.first }
    for ((_, path) in lit) drawPath(path, pal.lit.copy(alpha = 0.72f))
    val lines = ArrayList<Pair<Float, Path>>()
    for (st in scene.markings) quads(st, p, lines, 450f)
    lines.sortByDescending { it.first }
    for ((d, path) in lines) drawPath(path, pal.marking.copy(alpha = (1f - d / 500f).coerceIn(0.25f, 1f)))
    scene.arrow?.let { a -> val l = ArrayList<Pair<Float, Path>>(); quads(a, p, l); for ((_, path) in l) drawPath(path, pal.arrow) }
    scene.arrowHead?.let { hd ->
      val pts = hd.mapNotNull { p.project(it[0], it[1], 0.02) }
      if (pts.size == 3) drawPath(Path().apply { moveTo(pts[0][0], pts[0][1]); lineTo(pts[1][0], pts[1][1]); lineTo(pts[2][0], pts[2][1]); close() }, pal.arrow)
    }
    scene.gantry?.let { drawGantry(it, p, pal) }
  }
  return img
}

private fun DrawScope.drawGantry(g: Gantry, p: Projector, pal: Palette) {
  // two posts and the beam
  val postW = 0.35
  for (yy in listOf(g.yLeft, g.yRight)) {
    val b0 = g.at(yy + postW, 0.0, p); val b1 = g.at(yy - postW, 0.0, p)
    val t0 = g.at(yy + postW, 7.6, p); val t1 = g.at(yy - postW, 7.6, p)
    if (b0 != null && b1 != null && t0 != null && t1 != null) {
      drawPath(Path().apply { moveTo(b0[0], b0[1]); lineTo(b1[0], b1[1]); lineTo(t1[0], t1[1]); lineTo(t0[0], t0[1]); close() }, pal.post)
    }
  }
  val bl = g.at(g.yLeft, 7.6, p); val br = g.at(g.yRight, 7.6, p)
  val bl2 = g.at(g.yLeft, 7.2, p); val br2 = g.at(g.yRight, 7.2, p)
  if (bl != null && br != null && bl2 != null && br2 != null) {
    drawPath(Path().apply { moveTo(bl[0], bl[1]); lineTo(br[0], br[1]); lineTo(br2[0], br2[1]); lineTo(bl2[0], bl2[1]); close() }, pal.post)
  }
  // the panels, with their text drawn on them
  for (pn in g.panels) {
    val tl = g.at(pn.yLeft, 7.4, p) ?: continue
    val tr = g.at(pn.yRight, 7.4, p) ?: continue
    val bR = g.at(pn.yRight, 4.9, p) ?: continue
    val bL = g.at(pn.yLeft, 4.9, p) ?: continue
    val bg = if (pn.taken) pn.bg else androidx.compose.ui.graphics.lerp(pn.bg, Color(0xFF6E7883), 0.35f)
    drawPath(Path().apply { moveTo(tl[0], tl[1]); lineTo(tr[0], tr[1]); lineTo(bR[0], bR[1]); lineTo(bL[0], bL[1]); close() }, bg)
    drawPath(Path().apply { moveTo(tl[0], tl[1]); lineTo(tr[0], tr[1]); lineTo(bR[0], bR[1]); lineTo(bL[0], bL[1]); close() }, pn.fg.copy(alpha = 0.9f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = ((tr[0] - tl[0]) * 0.012f).coerceIn(1f, 4f)))
    val panelW = tr[0] - tl[0]
    val panelH = bL[1] - tl[1]
    if (panelW < 30 || panelH < 14) continue
    val lines = pn.lines.take(4)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      color = pn.fg.toArgb(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
      textSize = (panelH / (lines.size.coerceAtLeast(1) + 0.9f)).coerceAtMost(panelW / 9f)
    }
    var y = tl[1] + paint.textSize * 1.05f
    val arrowSpace = panelH * 0.55f
    val textLeft = tl[0] + panelW * 0.05f + (if (pn.exit != null) paint.textSize * 2.2f else 0f)
    pn.exit?.let { ex ->
      // the exit number in its own box at the top left
      val box = android.graphics.RectF(tl[0] + panelW * 0.04f, tl[1] + panelH * 0.08f, tl[0] + panelW * 0.04f + paint.textSize * 1.9f, tl[1] + panelH * 0.08f + paint.textSize * 1.25f)
      val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pn.fg.toArgb(); style = android.graphics.Paint.Style.STROKE; strokeWidth = paint.textSize * 0.08f }
      drawContext.canvas.nativeCanvas.drawRoundRect(box, paint.textSize * 0.2f, paint.textSize * 0.2f, boxPaint)
      val small = android.graphics.Paint(paint).apply { textSize = paint.textSize * 0.8f; textAlign = android.graphics.Paint.Align.CENTER }
      drawContext.canvas.nativeCanvas.drawText(ex, box.centerX(), box.centerY() + small.textSize * 0.35f, small)
    }
    for (line in lines) {
      var t = line
      while (t.length > 3 && paint.measureText(t) > panelW - (textLeft - tl[0]) - arrowSpace - panelW * 0.04f) t = t.dropLast(2) + "…"
      drawContext.canvas.nativeCanvas.drawText(t, textLeft, y, paint)
      y += paint.textSize * 1.08f
      if (y > bL[1]) break
    }
    // the arrow of the panel: where its lanes go
    val cx = tr[0] - arrowSpace * 0.55f
    val cy = tl[1] + panelH * 0.5f
    val s = arrowSpace * 0.42f
    val ang = Math.toRadians(pn.arrowDeg.toDouble())
    val dxA = (sin(ang) * s).toFloat(); val dyA = (-cos(ang) * s).toFloat()
    drawLine(pn.fg, Offset(cx - dxA, cy - dyA), Offset(cx + dxA, cy + dyA), strokeWidth = s * 0.28f)
    val hx = cx + dxA; val hy = cy + dyA
    val px = -dyA * 0.55f; val py = dxA * 0.55f
    drawPath(Path().apply { moveTo(hx + dxA * 0.45f, hy + dyA * 0.45f); lineTo(hx + px, hy + py); lineTo(hx - px, hy - py); close() }, pn.fg)
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
  // a chevron 6 m long, 2.6 m wide, with its shadow
  val tip = p.project(cx + ux * 3.2, cy + uy * 3.2, 0.3) ?: return
  val l = p.project(cx - ux * 2.8 + nx * 1.3, cy - uy * 2.8 + ny * 1.3, 0.3) ?: return
  val r = p.project(cx - ux * 2.8 - nx * 1.3, cy - uy * 2.8 - ny * 1.3, 0.3) ?: return
  val m = p.project(cx - ux * 1.5, cy - uy * 1.5, 0.3) ?: return
  val shape = Path().apply { moveTo(tip[0], tip[1]); lineTo(l[0], l[1]); lineTo(m[0], m[1]); lineTo(r[0], r[1]); close() }
  drawPath(shape, Color(0x55000000), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
  drawPath(shape, Color(0xFF1565C0))
  drawPath(shape, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
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
          val top = size.height * 0.16f; val bot = size.height * 0.84f
          drawRoundRect(Color(0x66000000), Offset(bx, top), Size(bw, bot - top), androidx.compose.ui.geometry.CornerRadius(bw / 2))
          drawRoundRect(if (scene.distanceM < 300) NmAmber else Color(0xFF2F7CF6), Offset(bx, bot - (bot - top) * f), Size(bw, (bot - top) * f),
              androidx.compose.ui.geometry.CornerRadius(bw / 2))
        }
      }
      Text(Fmt.distanceText(scene.distanceM), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
          modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
      Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(34.dp).clip(CircleShape).background(Color(0x99000000)).clickable { closed = true },
          contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 16.sp) }
    }
    // the lanes, the same strip as on the map
    val plan = scene.junction.plan
    if (plan.lanes.isNotEmpty()) {
      BoxWithConstraints(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        val w = min(maxWidth.value, plan.lanes.size * 56f).dp
        Canvas(Modifier.width(w).fillMaxHeight()) { drawLanes(plan, scene.vehicleLane) }
      }
    }
  }
}
