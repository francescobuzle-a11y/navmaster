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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
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
 * The junction view, a fixed drawing as on the dedicated navigators: the road ahead in a set
 * perspective with its lanes (the same lanes as in the lane strip and the voice: nav.LaneModel),
 * the lanes to take lit with the arrows painted on them, the exit or the turn on its side, the
 * sign over the lanes (the map's direction signs when it has them, else a sign made from the road
 * taken: its number, its name, the turn), a bar with the distance. Nothing of the road's real
 * shape is drawn (that made the picture wrong wherever the map's lines were rough): only what the
 * driver has to choose. The vehicle's mark sits in its lane when the satellites know it.
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
  // the vehicle's lane counts only when the lanes here are the junction's (the same rule as the
  // lane strip: as many, or one exit lane more on the right)
  val hereLanes = a.edgeAt(traveled)?.lanes ?: 0
  val nJ = j.lanes.count { !it.merge }
  val laneHere = vehicleLane?.takeIf { nJ > 0 && (nJ == hereLanes || (nJ == hereLanes + 1 && j.side > 0)) }
  val (tollEntry, tollRef) = synchronized(tollCache) {
    tollCache.getOrPut("${System.identityHashCode(a)}:${Math.round(at / 25.0)}") { tollOf(a, at) }
  }
  return JunctionScene(j, distanceM, range, JvRoute.of(a), traveled, at, country, laneHere, tollEntry, tollRef)
}

/** The booth of the last junctions asked for (asked at every fix: the route's roads are not scanned each time). */
private val tollCache = object : LinkedHashMap<String, Pair<Boolean, String?>>(8, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Boolean, String?>>?) = size > 6
}

/** Whether the road taken at [at] leads to the entry booth of a motorway, and the motorway's number. */
private fun tollOf(a: RouteAnalysis, at: Double): Pair<Boolean, String?> {
  // the entry booth of a motorway just past the junction (the booth's node is on the link, a
  // little before or after the turn): the sign of the booth, with the motorway's number
  val booth = a.tollBooths.firstOrNull { it.alongM >= at - 300 && it.alongM <= at + 800 }
  val tollEntry = booth != null && a.boothRole(booth) == "entrata"
  if (!tollEntry) return false to null
  // the motorway's number: from the motorway's own stretches (the first ones may carry no name),
  // else the signs of the slip road (destination:ref), else the map's ref of the motorway's way
  val tollRef = run {
    val mw = a.edges.filter { it.roadClass == "motorway" && !it.isRamp && it.startM >= at - 50 && it.startM <= at + 4000 }
    mw.firstNotNullOfOrNull { e -> e.refs.firstOrNull { isMotorwayRef(it) } }
        ?: a.edges.filter { it.startM >= at - 50 && it.startM <= at + 4000 }.firstNotNullOfOrNull { e -> e.sign?.branches?.firstOrNull { isMotorwayRef(it) } }
        ?: mw.firstOrNull()?.let { e ->
          val q = a.pointAt((e.startM + 20).coerceAtMost(a.length))
          runCatching { jvRoadsNear(q.lat, q.lng, 40.0) }.getOrDefault(emptyList())
              .firstOrNull { it.osm == e.wayId }?.ref?.split(';')?.map { it.trim() }?.firstOrNull { isMotorwayRef(it) }
        }
  }
  return true to tollRef
}

// ---------------------------------------------------------------------------------- the sign

private fun isMotorwayRef(r: String): Boolean = r.uppercase().let { (it.startsWith("A") || it.startsWith("E")) && it.drop(1).firstOrNull()?.isDigit() == true }

private val RefLike = Regex("^[A-Z]{1,3}[ -]?\\d{1,4}[a-z]?$")

/** The arrow of the sign for the road taken: the exit's side at exits and forks, else the road's real direction. */
private fun signArrow(j: LaneModel.Junction): Float {
  val exitLike = j.kind == LanePlan.Kind.EXIT || j.kind == LanePlan.Kind.FORK
  if (exitLike) return if (j.side > 0) 45f else if (j.side < 0) -45f else 0f
  val a = j.taken.angle
  return if (abs(a) < 12) 0f else a.coerceIn(-90.0, 90.0).toFloat()
}

/**
 * One panel of the sign, over the lanes [l0]..[l1] of the picture (when no lanes are known the
 * road is split in two halves, 0 the left one and 1 the right one).
 */
internal class SignPanel(val l0: Int, val l1: Int, val refs: List<String>, val towns: List<String>, val exit: String?,
                         val bg: Color, val fg: Color, val arrowDeg: Float, val taken: Boolean) {
  /** The lines as read: the numbers on one line, then the towns. */
  val lines: List<String> get() = listOfNotNull(refs.joinToString("  ").takeIf { it.isNotBlank() }) + towns
}

/** The sign over the lanes, and whether its texts are the map's (OSM destination tags) or made from the road taken. */
internal class Sign(val panels: List<SignPanel>, val fromMap: Boolean)

/**
 * The sign over the lanes: one panel per destination from the map's signs (destination:lanes, or
 * the signs of the road taken and of the other main road) when it has them; otherwise a sign made
 * from the road taken, its number and its name (at the entry of a motorway: the booth, with the
 * motorway's number). Never without a sign, never a destination made up.
 */
internal fun signOf(s: JunctionScene): Sign {
  val j = s.junction
  val country = s.country
  val known = j.lanes.isNotEmpty()
  val nLanes = if (known) j.lanes.size else 2
  val active = if (known) j.active else (if (j.side > 0) listOf(1) else if (j.side < 0) listOf(0) else listOf(0, 1))
  val panels = ArrayList<SignPanel>()
  val motorwayCtx = j.approachClass == "motorway"
  fun colorsFor(sign: EdgeSign?, toMotorway: Boolean): Pair<Color, Color> =
      sign?.colour?.let { osmSignColors(it) } ?: signColors(country, toMotorway)
  val sideArrow = signArrow(j)
  val l0 = active.minOrNull() ?: 0
  val l1 = active.maxOrNull() ?: (nLanes - 1)
  var fromMap = true
  // signs over the lanes (destination:lanes): one panel per run of lanes with the same destination
  val ls = j.laneSigns
  if (known && ls != null && ls.size == nLanes) {
    var i = 0
    while (i < nLanes) {
      var k = i
      while (k + 1 < nLanes && ls[k + 1].towns == ls[i].towns && ls[k + 1].refs == ls[i].refs) k++
      val d = ls[i]
      if (!d.isEmpty) {
        val taken = (i..k).any { it in active }
        val toMotorway = d.refs.any { isMotorwayRef(it) }
        val (bg, fg) = d.colour?.let { osmSignColors(it) } ?: signColors(country, toMotorway)
        panels += SignPanel(i, k, d.refs.take(3), d.towns.take(3), if (taken) j.sign?.exitNumbers?.firstOrNull() else null,
            bg, fg, if (taken) sideArrow else 0f, taken)
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
      panels += SignPanel(l0, l1, refs, towns, takenSign.exitNumbers.firstOrNull(), bg, fg, sideArrow, true)
    }
    if (otherSign != null && others.isNotEmpty()) {
      val (bg, fg) = colorsFor(otherSign, motorwayCtx)
      panels += SignPanel(others.min(), others.max(), otherSign.branches.take(3), otherSign.towards.take(3), null, bg, fg, 0f, false)
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
    panels += SignPanel(l0, l1, refs, towns, null, bg, fg, sideArrow, true)
  }
  return Sign(panels.sortedBy { it.l0 }, fromMap)
}

// ---------------------------------------------------------------------------------- the picture

internal class Palette(night: Boolean) {
  val skyTop = if (night) Color(0xFF0B1220) else Color(0xFF7FB0E4)
  val skyLow = if (night) Color(0xFF1C2635) else Color(0xFFDCE9F6)
  val groundFar = if (night) Color(0xFF1A2026) else Color(0xFFCBD6BE)
  val groundNear = if (night) Color(0xFF151A1F) else Color(0xFFB9CBA3)
  val asphalt = if (night) Color(0xFF3A3F47) else Color(0xFF596069)
  val asphaltFar = if (night) Color(0xFF2E333B) else Color(0xFF7D848C)
  val marking = if (night) Color(0xFFC9CED4) else Color(0xFFFFFFFF)
  val lit = Color(0xFF2F7CF6)
  val litFar = if (night) Color(0xFF2A5FB8) else Color(0xFF5B95F0)
  /** The arrows painted on the lanes: bright on the lanes to take, dull on the others. */
  val paint = if (night) Color(0xFFE6EAEE) else Color(0xFFFFFFFF)
  val paintDim = if (night) Color(0xFF6E747C) else Color(0xFFA9AEB4)
  val post = if (night) Color(0xFF6A7078) else Color(0xFF8D949C)
  val beam = if (night) Color(0xFF4A5058) else Color(0xFF5C636B)
}

/** Where the horizon is, as a fraction of the picture's height from the top. */
internal const val HORIZON_FRAC = 0.34
/** Depth of the far end of the road (1 = the bottom edge of the picture). */
private const val Z_FAR = 30.0
/** Depth where an exit or a fork leaves the road. */
private const val Z_SPLIT = 2.3
/** Depth of the middle of the crossing road of a junction in town. */
private const val Z_CROSS = 3.4
/** Half the depth the crossing road takes (its near and far edges). */
private const val Z_CROSS_HALF = 0.55
/** Depth of the sign's posts. */
private const val Z_SIGN = 2.0

/**
 * The fixed perspective of the picture: a point of the road is given by its depth z (1 at the
 * bottom edge of the picture, growing towards the horizon) and its place across u (−1 … 1 from the
 * left to the right edge of the road arriving, lane boundary b at −1 + 2b/n).
 */
internal class Persp(val w: Float, val h: Float, val n: Int) {
  val hz: Float = h * HORIZON_FRAC.toFloat()
  val cx: Float = w / 2
  /** Half the road's width at the bottom edge: wide, but a lane never wider than a third of the picture. */
  val hw0: Float = min(w * 0.46f, max(1, n) * w * 0.34f / 2)
  fun y(z: Double): Float = (hz + (h - hz) / z).toFloat()
  fun x(u: Double, z: Double): Float = (cx + u * hw0 / z).toFloat()
  /** The depth of a screen row (inverse of y). */
  fun zAt(yy: Float): Double = ((h - hz) / (yy - hz).coerceAtLeast(0.5f)).toDouble()
  fun boundary(b: Int): Double = -1.0 + 2.0 * b / max(1, n)
}

/** The layout of the junction in the picture: which lanes branch off, on which side, how. */
internal class Layout(val scene: JunctionScene) {
  val j = scene.junction
  val exitLike = j.kind == LanePlan.Kind.EXIT || j.kind == LanePlan.Kind.FORK
  /** The lanes are known (painted arrows or counts that add up). */
  val known = j.lanes.isNotEmpty()
  /** Lanes drawn: the junction's, or (unknown) a road of three lanes' width without lines, the branch on one of them. */
  val n = if (known) j.lanes.size else 3
  val side = j.side
  val active: List<Int> = when {
    known -> j.active
    side > 0 -> listOf(n - 1)
    side < 0 -> listOf(0)
    else -> emptyList()
  }
  /** Boundaries of the lit lanes. */
  val litA = active.minOrNull() ?: 0
  val litB = (active.maxOrNull() ?: -1) + 1
  /** A turn in town of less than 50°: drawn as a road branching off, like an exit. */
  val shallow = !exitLike && abs(j.taken.angle) < 50
  /** The branch of an exit, a fork or a slight turn: from the first lit lane to the road's edge on its side. */
  val branch = (exitLike || shallow) && side != 0 && active.isNotEmpty()
  /** The boundary where the branch leaves (it belongs to both). */
  val splitB = if (side > 0) litA else litB
  /** A turn in town: the crossing road, the lit lanes turning into it. */
  val turn = !exitLike && !shallow && side != 0
  val lht = scene.country in LEFT_TRAFFIC
  /** In a turn: into the near half of the crossing road (right turns where traffic keeps right), else the far half. */
  val nearHalf = (side > 0) != lht
  /** At a junction in town: a road goes on straight beyond the crossing. */
  val straightOn = !exitLike && j.roads.any { !it.taken && abs(it.angle) < 35 }
}

/** How much a branch has moved sideways at depth [z] (in road half-widths), towards its side. */
private fun bend(z: Double, p: Persp, fork: Boolean, outward: Boolean): Double {
  if (z <= Z_SPLIT) return 0.0
  val zEnd = 10.0
  // at its far end the branch reaches past the picture's edge
  val target = 1.15 * zEnd * (p.w / 2) / p.hw0
  val a = target / ((zEnd - Z_SPLIT) * (zEnd - Z_SPLIT))
  val k = if (outward) 1.0 else if (fork) 0.22 else 0.0
  return k * a * (z - Z_SPLIT) * (z - Z_SPLIT)
}

/** The screen point of boundary place [u] at depth [z], on the branch ([branch]) or on the road going on. */
internal fun pt(p: Persp, l: Layout, u: Double, z: Double, onBranch: Boolean): Offset {
  val b = if (!l.branch) 0.0 else if (onBranch) l.side * bend(z, p, l.j.kind == LanePlan.Kind.FORK, true)
      else -l.side * bend(z, p, l.j.kind == LanePlan.Kind.FORK, false)
  return Offset(p.x(u + b, z), p.y(z))
}

/** Depths from [z0] to [z1], denser near the bottom (equal steps on the screen). */
private fun depths(z0: Double, z1: Double, steps: Int = 40): List<Double> {
  val a = 1.0 / z0; val b = 1.0 / z1
  return (0..steps).map { 1.0 / (a + (b - a) * it / steps) }
}

/** A band of road between places [u0] and [u1] from depth [z0] to [z1], as one shape. */
private fun band(p: Persp, l: Layout, u0: Double, u1: Double, z0: Double, z1: Double, onBranch: Boolean): Path {
  val zs = depths(z0, z1)
  return Path().apply {
    zs.forEachIndexed { i, z -> val q = pt(p, l, u0, z, onBranch); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }
    zs.asReversed().forEach { z -> val q = pt(p, l, u1, z, onBranch); lineTo(q.x, q.y) }
    close()
  }
}

/** A painted line along place [u] from depth [z0] to [z1]: solid, or dashed in equal steps of depth. */
private fun DrawScope.line(p: Persp, l: Layout, u: Double, z0: Double, z1: Double, onBranch: Boolean, color: Color, dashed: Boolean) {
  // 20 cm on a lane of 3.5 m
  val half = max(0.01, 0.2 / 3.5 / max(1, p.n))
  if (!dashed) {
    drawPath(band(p, l, u - half, u + half, z0, z1, onBranch), color)
    return
  }
  // dashes 3 m long every 9 m (the bottom edge of the picture is about 12 m ahead)
  var z = z0
  while (z < z1) {
    val e = min(z + 0.25, z1)
    drawPath(band(p, l, u - half, u + half, z, e, onBranch), color)
    z = e + 0.5
  }
}

/**
 * An arrow on lane [lane] at depth [z]: upright on the screen like the arrows of the lane strip
 * (projected flat on the road they were squashed until a turn looked broken), as wide as part of
 * the lane there, [across] of the lane off its middle when two arrows share it.
 */
private fun DrawScope.laneArrow(p: Persp, l: Layout, lane: Int, across: Double, z: Double, angleDeg: Float, color: Color, share: Float, onBranch: Boolean) {
  val laneU = 2.0 / l.n
  val c = pt(p, l, p.boundary(lane) + laneU * (0.5 + across), z, onBranch)
  val laneW = abs(p.x(p.boundary(lane + 1), z) - p.x(p.boundary(lane), z))
  val cell = laneW * share
  val cw = cell; val ch = cell * 0.95f
  translate(c.x - cw / 2, c.y - ch / 2) {
    val old = drawContext.size
    drawContext.size = Size(cw, ch)
    drawTurnArrow(angleDeg, color, 0.17f, laneW * share * 1.6f)
    drawContext.size = old
  }
}

/** An upright chevron on lane [lane] at depth [z] (lanes known from their number only: no arrows are painted on the map). */
private fun DrawScope.laneChevron(p: Persp, l: Layout, lane: Int, z: Double, color: Color, onBranch: Boolean) {
  val laneU = 2.0 / l.n
  val c = pt(p, l, p.boundary(lane) + laneU * 0.5, z, onBranch)
  val laneW = abs(p.x(p.boundary(lane + 1), z) - p.x(p.boundary(lane), z))
  val wd = laneW * 0.42f; val ht = wd * 0.62f
  drawPath(Path().apply {
    moveTo(c.x, c.y - ht / 2); lineTo(c.x + wd / 2, c.y + ht / 2); lineTo(c.x, c.y + ht * 0.12f); lineTo(c.x - wd / 2, c.y + ht / 2); close()
  }, color)
}

/** The fixed picture of the junction, drawn once into a bitmap. */
internal fun renderJunction(scene: JunctionScene, w: Int, h: Int, density: Density, night: Boolean): ImageBitmap {
  val img = ImageBitmap(w, h)
  CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(img), Size(w.toFloat(), h.toFloat())) {
    drawJunction(scene, night)
  }
  return img
}

internal fun DrawScope.drawJunction(scene: JunctionScene, night: Boolean) {
  val l = Layout(scene)
  val p = Persp(size.width, size.height, l.n)
  val pal = Palette(night)
  val w = size.width; val h = size.height
  val hy = p.hz
  drawRect(Brush.verticalGradient(listOf(pal.skyTop, pal.skyLow), 0f, hy), Offset.Zero, Size(w, hy))
  drawRect(Brush.verticalGradient(listOf(pal.groundFar, pal.groundNear), hy, h), Offset(0f, hy), Size(w, h - hy))
  // the road's colour, lighter into the distance
  val road = Brush.verticalGradient(listOf(pal.asphaltFar, pal.asphalt), hy, h)
  val litBrush = Brush.verticalGradient(listOf(pal.litFar, pal.lit), hy, h)
  val shoulder = 0.07
  val zEndBranch = 10.0
  val zNear = Z_CROSS - Z_CROSS_HALF; val zFarX = Z_CROSS + Z_CROSS_HALF
  // ---- the asphalt
  when {
    l.branch -> {
      val zMain = Z_FAR
      if (l.side > 0) {
        drawPath(band(p, l, -1 - shoulder, p.boundary(l.splitB) + shoulder * 0.3, 1.0, zMain, false), road)
        drawPath(band(p, l, p.boundary(l.splitB) - shoulder * 0.3, 1 + shoulder, 1.0, zEndBranch, true), road)
      } else {
        drawPath(band(p, l, p.boundary(l.splitB) - shoulder * 0.3, 1 + shoulder, 1.0, zMain, false), road)
        drawPath(band(p, l, -1 - shoulder, p.boundary(l.splitB) + shoulder * 0.3, 1.0, zEndBranch, true), road)
      }
    }
    l.turn -> {
      drawPath(band(p, l, -1 - shoulder, 1 + shoulder, 1.0, zNear + 0.05, false), road)
      // the crossing road, the whole width
      drawRect(road, Offset(0f, p.y(zFarX)), Size(w, p.y(zNear) - p.y(zFarX)))
      if (l.straightOn) drawPath(band(p, l, -1 - shoulder, 1 + shoulder, zFarX - 0.05, Z_FAR, false), road)
      // the corners of the crossing rounded
      for (sgn in listOf(-1, 1)) {
        val ex = p.x(sgn * (1 + shoulder), zNear); val ey = p.y(zNear)
        val r = (p.y(zNear - 0.35) - ey)
        val corner = Path().apply {
          moveTo(ex, ey + r); quadraticTo(ex, ey, ex + sgn * r * 1.4f, ey); lineTo(ex, ey); close()
        }
        drawPath(corner, road)
      }
    }
    else -> drawPath(band(p, l, -1 - shoulder, 1 + shoulder, 1.0, Z_FAR, false), road)
  }
  // ---- the lanes to take
  if (l.active.isNotEmpty()) {
    val ua = p.boundary(l.litA); val ub = p.boundary(l.litB)
    when {
      l.branch -> drawPath(band(p, l, ua, ub, 1.0, zEndBranch, true), litBrush)
      l.turn -> {
        // up the lanes, then round the corner into the crossing road's half on the turn's side
        val yNear = p.y(zNear); val yMid = p.y(Z_CROSS); val yFar = p.y(zFarX)
        val yEnd = if (l.nearHalf) yMid else yFar
        val yIn = if (l.nearHalf) yNear else yMid
        val s = l.side.toFloat()
        // the outer edge of the turn (away from its side) and the inner one
        val uOut = if (l.side > 0) ua else ub; val uIn = if (l.side > 0) ub else ua
        val zOut = p.zAt(yEnd); val zIn = p.zAt(yIn)
        val xOut = p.x(uOut, zOut); val xIn = p.x(uIn, zIn)
        val edge = if (l.side > 0) w else 0f
        val rOut = (p.y(1.6) - yEnd).coerceAtMost(abs(edge - xOut) * 0.6f).coerceAtLeast(4f)
        val rIn = (yNear - yIn + (p.y(2.2) - yNear) * 0.5f).coerceAtLeast(3f).coerceAtMost(abs(edge - xIn) * 0.5f)
        val path = Path().apply {
          moveTo(p.x(uOut, 1.0), h)
          lineTo(p.x(uOut, p.zAt(yEnd + rOut)), yEnd + rOut)
          quadraticTo(xOut, yEnd, xOut + s * rOut, yEnd)
          lineTo(edge, yEnd)
          lineTo(edge, yIn)
          lineTo(xIn + s * rIn, yIn)
          quadraticTo(xIn, yIn, p.x(uIn, p.zAt(yIn + rIn)), yIn + rIn)
          lineTo(p.x(uIn, 1.0), h)
          close()
        }
        drawPath(path, litBrush)
      }
      else -> drawPath(band(p, l, ua, ub, 1.0, Z_FAR, false), litBrush)
    }
  }
  // ---- the markings
  val m = pal.marking
  if (l.branch) {
    val zEndMain = Z_FAR
    // the road's outer edges
    line(p, l, -1.0, 1.0, if (l.side > 0) zEndMain else zEndBranch, l.side < 0, m, false)
    line(p, l, 1.0, 1.0, if (l.side > 0) zEndBranch else zEndMain, l.side > 0, m, false)
    // where the branch leaves: a lane line before, two edges after
    val us = p.boundary(l.splitB)
    if (l.known) line(p, l, us, 1.0, Z_SPLIT, false, m, true)
    line(p, l, us, Z_SPLIT, zEndMain, false, m, false)
    line(p, l, us, Z_SPLIT, zEndBranch, true, m, false)
    if (l.known) for (b in 1 until l.n) {
      if (b == l.splitB) continue
      val onBranch = if (l.side > 0) b > l.splitB else b < l.splitB
      line(p, l, p.boundary(b), 1.0, if (onBranch) zEndBranch else zEndMain, onBranch, m, true)
    }
  } else if (l.turn) {
    line(p, l, -1.0, 1.0, zNear, false, m, false)
    line(p, l, 1.0, 1.0, zNear, false, m, false)
    if (l.known) for (b in 1 until l.n) line(p, l, p.boundary(b), 1.0, zNear - 0.15, false, m, true)
    // the crossing road: its middle dashed, its edges solid except across the mouths of the roads
    val lw = max(2f, (p.y(Z_CROSS - 0.02) - p.y(Z_CROSS + 0.02)))
    var x = 0f
    while (x < w) { drawRect(m, Offset(x, p.y(Z_CROSS) - lw / 2), Size(w * 0.05f, lw)); x += w * 0.09f }
    val xl = p.x(-1 - shoulder, zNear); val xr = p.x(1 + shoulder, zNear)
    drawRect(m, Offset(0f, p.y(zNear) - lw), Size(xl, lw))
    drawRect(m, Offset(xr, p.y(zNear) - lw), Size(w - xr, lw))
    if (l.straightOn) {
      val fl = p.x(-1 - shoulder, zFarX); val fr = p.x(1 + shoulder, zFarX)
      drawRect(m, Offset(0f, p.y(zFarX)), Size(fl, lw))
      drawRect(m, Offset(fr, p.y(zFarX)), Size(w - fr, lw))
      line(p, l, -1.0, zFarX, Z_FAR, false, m, false)
      line(p, l, 1.0, zFarX, Z_FAR, false, m, false)
    } else drawRect(m, Offset(0f, p.y(zFarX)), Size(w, lw))
  } else {
    line(p, l, -1.0, 1.0, Z_FAR, false, m, false)
    line(p, l, 1.0, 1.0, Z_FAR, false, m, false)
    if (l.known) for (b in 1 until l.n) line(p, l, p.boundary(b), 1.0, Z_FAR, false, m, true)
  }
  // ---- the arrows painted on the lanes (the ones of the route bright on the lanes to take), or
  // chevrons on lanes to take known from their number only
  // two rows: near (big) and a little further; arrows of a lane with two side by side
  val rows = if (l.turn) listOf(1.45, 2.3) else listOf(1.5, 2.6)
  if (l.known) for (i in 0 until l.n) {
    val lane = l.j.lanes[i]
    val onBranch = l.branch && i in l.active
    for (z0 in rows) {
      if (!lane.painted) {
        if (lane.take) laneChevron(p, l, i, z0, pal.paint, onBranch)
        continue
      }
      val arrows = lane.arrows.distinct().take(2).sortedBy { directionAngle(it) }
      arrows.forEachIndexed { ai, ar ->
        val bright = lane.take && ar == lane.arrow
        val across = if (arrows.size == 1) 0.0 else if (ai == 0) -0.22 else 0.22
        laneArrow(p, l, i, across, z0, directionAngle(ar), if (bright) pal.paint else pal.paintDim, if (arrows.size == 1) 0.62f else 0.44f, onBranch)
      }
    }
  } else if (l.branch) {
    for (z0 in rows) laneChevron(p, l, l.active.first(), z0 + 0.2, pal.paint, true)
  }
  // ---- the sign
  drawSignStatic(signOf(scene), p, l, pal)
}

/** A panel laid out on the screen. */
private class PanelBox(val pn: SignPanel, var x0: Float, var x1: Float, val cx: Float)

/**
 * The sign: its panels at the top of the picture, each over the lanes it is for, as wide as its
 * text needs, the text never smaller than readable from the driver's seat; under them the beam
 * and the posts standing at the road's edges.
 */
private fun DrawScope.drawSignStatic(sign: Sign, p: Persp, l: Layout, pal: Palette) {
  val dp = density
  val w = size.width; val h = size.height
  val margin = 10f * dp
  // the close button sits at the top right of the picture
  val marginRight = 54f * dp
  val textMin = 18f * dp
  var textPx = (h * 0.05f).coerceIn(textMin, 30f * dp)
  fun arrowSpace(t: Float) = t * 2.0f
  fun pad(t: Float) = t * 0.55f
  fun paintOf(t: Float, color: Color) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); textSize = t
  }
  fun plateW(t: Float, ref: String) = paintOf(t * 0.92f, Color.White).measureText(ref.uppercase()) + t * 0.9f
  fun minWidth(pn: SignPanel, t: Float): Float {
    val tp = paintOf(t, Color.White)
    val refs = if (pn.refs.isEmpty()) 0f else pn.refs.sumOf { plateW(t, it).toDouble() }.toFloat() + t * 0.3f * (pn.refs.size - 1)
    val towns = pn.towns.maxOfOrNull { tp.measureText(it) } ?: 0f
    return max(refs, towns) + pad(t) * 2 + arrowSpace(t) + (if (pn.exit != null) t * 2.3f else 0f)
  }
  // the lanes of each panel at the sign's depth (unknown lanes: the road's two halves)
  val nSign = if (l.known) l.n else 2
  fun ub(b: Int) = -1.0 + 2.0 * b / nSign
  val boxes = sign.panels.map { pn ->
    val onBranch = l.branch && l.known && pn.l0 >= l.litA && pn.l1 < l.litB
    val xl = pt(p, l, ub(pn.l0), Z_SIGN, onBranch).x
    val xr = pt(p, l, ub(pn.l1 + 1), Z_SIGN, onBranch).x
    PanelBox(pn, min(xl, xr), max(xl, xr), (xl + xr) / 2)
  }.sortedBy { it.cx }
  if (boxes.isEmpty()) return
  val avail = w - margin - marginRight - (boxes.size - 1) * 6f * dp
  val minSum = boxes.sumOf { minWidth(it.pn, textPx).toDouble() }.toFloat()
  // too much text for the width: smaller letters, down to the readable minimum
  if (minSum > avail && textPx > textMin) textPx = max(textMin, textPx * avail / minSum)
  val share = (avail / boxes.sumOf { minWidth(it.pn, textPx).toDouble() }.toFloat()).coerceAtMost(1f)
  for (b in boxes) {
    val mw = minWidth(b.pn, textPx) * share
    if (b.x1 - b.x0 < mw) { b.x0 = b.cx - mw / 2; b.x1 = b.cx + mw / 2 }
    b.x0 = b.x0.coerceAtLeast(margin); b.x1 = b.x1.coerceAtMost(w - marginRight)
    if (b.x1 - b.x0 < mw) { if (b.x0 <= margin + 0.5f) b.x1 = b.x0 + mw else b.x0 = b.x1 - mw }
  }
  val gap = 6f * dp
  for (i in 1 until boxes.size) {
    val prev = boxes[i - 1]; val cur = boxes[i]
    if (cur.x0 < prev.x1 + gap) { val d = prev.x1 + gap - cur.x0; cur.x0 += d; cur.x1 += d }
  }
  for (i in boxes.indices.reversed()) {
    val b = boxes[i]
    if (b.x1 > w - marginRight) { val d = b.x1 - (w - marginRight); b.x0 -= d; b.x1 -= d }
    if (i > 0) { val prev = boxes[i - 1]; if (prev.x1 + gap > b.x0) { val d = prev.x1 + gap - b.x0; prev.x0 -= d; prev.x1 -= d } }
  }
  // the height: the lines at the text size, at most a third of the picture
  val maxLines = boxes.maxOf { (if (it.pn.refs.isEmpty()) 0 else 1) + it.pn.towns.size }.coerceAtLeast(1)
  val lineH = textPx * 1.22f
  var lines = min(maxLines, 4)
  var boxH = lineH * lines + pad(textPx) * 1.6f
  while (boxH > h * 0.30f && lines > 1) { lines--; boxH = lineH * lines + pad(textPx) * 1.6f }
  val top = margin
  val bottom = top + boxH
  // the posts at the road's edges, upright, and the beam reaching them
  val postW = 4f * dp
  val spanL = boxes.first().x0; val spanR = boxes.last().x1
  val reach = (spanR - spanL) * 0.6f
  val yPost = p.y(Z_SIGN)
  var posts = listOf(p.x(-1.12, Z_SIGN), p.x(1.12, Z_SIGN)).filter { it in 2f..(w - 2f) && it in (spanL - reach)..(spanR + reach) }
  if (posts.isEmpty()) posts = listOf(((spanL + spanR) / 2))
  val beamL = (posts.map { it - postW } + (spanL - 4f * dp)).min().coerceAtLeast(0f)
  val beamR = (posts.map { it + postW } + (spanR + 4f * dp)).max().coerceAtMost(w)
  for (x in posts) drawLine(pal.post, Offset(x, bottom), Offset(x, yPost), postW)
  drawRect(pal.beam, Offset(beamL, bottom), Size(beamR - beamL, 5f * dp))
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
        val (pbg, pfg) = refPlateColors(ref, l.scene.country)
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

/**
 * The vehicle's mark at the bottom of the picture in the lane the satellites put it in: blue in a
 * lane to take, amber in one that does not go the route's way. Nothing when the lane is not known.
 */
internal fun DrawScope.drawVehicleLane(scene: JunctionScene) {
  val l = Layout(scene)
  val lane = scene.vehicleLane ?: return
  if (!l.known || lane !in 0 until l.n) return
  val p = Persp(size.width, size.height, l.n)
  val laneU = 2.0 / l.n
  val ok = l.j.lanes[lane].take
  // upright, near the bottom edge, in the middle of its lane there
  val ht = 34f * density; val wd = 28f * density
  val yy = size.height - ht * 0.8f
  val c = Offset(p.x(p.boundary(lane) + laneU * 0.5, p.zAt(yy)), yy)
  val shape = Path().apply {
    moveTo(c.x, c.y - ht / 2); lineTo(c.x + wd / 2, c.y + ht / 2); lineTo(c.x, c.y + ht * 0.22f); lineTo(c.x - wd / 2, c.y + ht / 2); close()
  }
  drawPath(shape, Color(0x66000000), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.5f * density))
  drawPath(shape, if (ok) Color(0xFF0B2A5C) else NmAmber)
  drawPath(shape, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * density))
}

/**
 * The junction view: the picture of the junction, a bar with the distance to it, and under it the
 * lanes (the same strip as on the map).
 */
@Composable
fun JunctionView(scene: JunctionScene, night: Boolean, modifier: Modifier = Modifier, fill: Boolean = false,
                 height: Dp = 260.dp, vehAlong: State<Double>? = null,
                 /** Closing kept by the caller (which then shows the lane strip again); null: kept here. */
                 onClose: (() -> Unit)? = null) {
  // the same junction keeps its picture while the point wobbles by a few metres
  val key = remember(scene.route.key, (scene.maneuverAtM / 60.0).roundToLong()) { Any() }
  var closed by remember(key) { mutableStateOf(false) }
  if (closed) return
  Column(modifier.shadow(10.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(Color(0xFF11161C))) {
    BoxWithConstraints(Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f) else Modifier.height(height))) {
      val density = androidx.compose.ui.platform.LocalDensity.current
      val wPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
      val hPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
      val built by produceState<ImageBitmap?>(null, key, night, wPx, hPx) {
        // a new junction or size: no old picture under the new distance and lanes meanwhile
        value = null
        value = withContext(Dispatchers.Default) {
          runCatching { renderJunction(scene, wPx, hPx, density, night) }
              .onFailure { android.util.Log.w("NmJunction", "vista svincolo", it) }.getOrNull()
        }
      }
      val pic = built
      if (pic != null) {
        Canvas(Modifier.fillMaxSize().testTag("jv-picture")) {
          drawImage(pic)
          drawVehicleLane(scene)
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
      Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color(0x99000000)).clickable { if (onClose != null) onClose() else closed = true },
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
