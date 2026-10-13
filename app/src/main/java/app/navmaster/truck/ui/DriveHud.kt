package app.navmaster.truck.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.poi.Poi
import app.navmaster.truck.poi.RoutePoi
import app.navmaster.truck.settings.PoiCategories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------------------------ the glyphs

/**
 * The signs of the kinds of place, drawn as lines (24×24, as the icons of the app): the same in
 * the panel and on the map pins, a colour for each kind.
 */
object PoiGlyphs {
  const val FUEL = "M4 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16M3 21h12M6 8h6M14 10h2a2 2 0 0 1 2 2v4a1.5 1.5 0 0 0 3 0V8l-3-3"
  const val DROP = "M12 3c3 4 6 7 6 11a6 6 0 0 1-12 0c0-4 3-7 6-11z"
  const val PARKING = "M8 20V4h5a4 4 0 0 1 0 8H8"
  const val SERVICES = "M3 21h18M5 21V9l7-5 7 5v12M9 21v-6h6v6"
  const val FOOD = "M7 3v8M5 3v5a2 2 0 0 0 4 0V3M7 11v10M17 3c-2 2-2 6 0 8v10"
  const val SHOWER = "M4 20V8a4 4 0 0 1 8 0M10 8h4M12 12v1M15 12v1M18 12v1M13.5 15v1M16.5 15v1"
  const val CAMPER = "M3 17V7h12l4 4v6M3 17h18M6 10h4M5 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0M14 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
  const val WC = "M6 3h5v8H6zM4 11h12a5 5 0 0 1-5 5H9M9 16l-1 5h6l-1-5"
  const val REPAIR = "M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z"
  const val BED = "M3 18V7M3 14h18v4M21 14v-3a3 3 0 0 0-3-3h-7v6M7 11.5a1.5 1.5 0 1 0 0 .01"
  const val PIN = "M12 21s-6-5.5-6-11a6 6 0 0 1 12 0c0 5.5-6 11-6 11zM12 10.5a1.5 1.5 0 1 0 0 .01"
  const val SEARCH = "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13M15.5 15.5L21 21"
  const val CLOSE = "M6 6l12 12M18 6L6 18"

  val Blue = Color(0xFF3D8BFD)
  val LightBlue = Color(0xFF5FA8FF)
  val Violet = Color(0xFFB9A4FF)
  val Teal = Color(0xFF1FB5A8)
  val Orange = Color(0xFFFF8A3D)
  val Cyan = Color(0xFF36C2D9)
  val Green = Color(0xFF2EB85C)
  val Grey = Color(0xFFC9D1D9)
  val Amber = Color(0xFFFFB300)

  /** The sign and the colour of a place of the database ([cat] of poi.sqlite). */
  fun of(cat: String): Pair<String, Color> = when (cat) {
    "fuel" -> FUEL to Blue
    "hgv_charging" -> FUEL to LightBlue
    "truck_parking", "camper_parking" -> PARKING to Violet
    "services", "rest_area" -> SERVICES to Teal
    "food", "supermarket" -> FOOD to Orange
    "shower" -> SHOWER to Cyan
    "toilets" -> WC to Grey
    "truck_repair", "tyres", "truck_wash", "weighbridge", "camper_shop" -> REPAIR to Amber
    "hotel" -> BED to Violet
    "campsite", "camper_site", "camper_service" -> CAMPER to Green
    else -> PIN to Grey
  }

  private val cache = HashMap<String, ImageVector>()

  /** The sign as a vector (white lines, tinted by Icon). */
  fun vector(d: String): ImageVector = synchronized(cache) {
    cache.getOrPut(d) {
      ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
          .addPath(
              pathData = addPathNodes(d),
              fill = null,
              stroke = SolidColor(Color.White),
              strokeLineWidth = 2.2f,
              strokeLineCap = StrokeCap.Round,
              strokeLineJoin = StrokeJoin.Round,
          )
          .build()
    }
  }
}

@Composable
fun Glyph(d: String, color: Color, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
  Icon(PoiGlyphs.vector(d), description, modifier.size(size), tint = color)
}

/** A sign on a coloured tile (dark lines on the colour of the kind). */
@Composable
fun GlyphTile(d: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
  Box(modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color), contentAlignment = Alignment.Center) {
    Glyph(d, Color(0xFF0E1216), size * 0.58f)
  }
}

// ------------------------------------------------------------------------------- the speedometer

/** How the vehicle's speed stands against its limit. */
enum class SpeedState { OK, OVER, FAR, SECTION }

/**
 * The speedometer at the top right while driving: half a dial of lit segments (5 km/h each, up to
 * 130 or 150), a white mark on the limit of THIS vehicle (a lorry has its 80 where the sign says
 * 130), the speed in big figures and the limit sign under it. Amber a little over the limit (one
 * soft beep when over the tolerance of the settings), red from 10 km/h over (and the voice
 * "Rallenta"), blue on a stretch with average speed control (the average under the speed).
 * [width] gives its size: the height is about three quarters of it.
 */
@Composable
fun SpeedArc(
    speedKmh: Int?,
    limitKmh: Int?,
    vehicleMaxKmh: Int,
    toleranceKmh: Int,
    sectionAvg: Int?,
    sectionLimit: Int?,
    width: Dp,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onSay: (String) -> Unit = {},
) {
  val effective = limitKmh?.let { minOf(it, vehicleMaxKmh) }
  val forVehicle = limitKmh != null && vehicleMaxKmh < limitKmh
  val over = if (speedKmh != null && effective != null) speedKmh - effective else 0
  val state = when {
    effective != null && over >= 10 -> SpeedState.FAR
    effective != null && over >= 1 -> SpeedState.OVER
    sectionAvg != null -> SpeedState.SECTION
    else -> SpeedState.OK
  }
  // sounds: a soft beep entering amber beyond the tolerance, the voice entering red (said again
  // by the voice queue only after two minutes)
  val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }.getOrNull() }
  DisposableEffect(Unit) { onDispose { tone?.release() } }
  var beepedAt by remember { mutableStateOf(0L) }
  val beyondTolerance = state == SpeedState.OVER && over > toleranceKmh
  LaunchedEffect(beyondTolerance, state == SpeedState.FAR, muted) {
    if (muted) return@LaunchedEffect
    if (state == SpeedState.FAR && effective != null) {
      onSay("Rallenta, limite $effective.")
    } else if (beyondTolerance && System.currentTimeMillis() - beepedAt > 30_000) {
      beepedAt = System.currentTimeMillis()
      runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 160) }
    }
  }

  val lit = when (state) {
    SpeedState.OK -> Nm.Accent
    SpeedState.OVER -> Nm.Amber
    SpeedState.FAR -> Color.White
    SpeedState.SECTION -> Nm.Blue
  }
  val track = if (state == SpeedState.FAR) Color(0x40FFFFFF) else Color(0xFF2A323C)
  val bg = if (state == SpeedState.FAR) Nm.RedDark else Nm.PanelSolid
  val edge = when (state) {
    SpeedState.OVER -> Nm.Amber
    SpeedState.SECTION -> Nm.Blue.copy(alpha = 0.8f)
    SpeedState.FAR -> Nm.Red
    SpeedState.OK -> Color(0x1FFFFFFF)
  }
  val numberColor = if (state == SpeedState.OVER) Nm.Amber else Color.White
  val scaleMax = if ((effective ?: 0) > 115 || (speedKmh ?: 0) > 125) 150 else 130
  val height = width * 0.733f
  val w = width.value
  Box(
      modifier.animateContentSize().size(width, height).shadow(10.dp, RoundedCornerShape(width * 0.11f))
          .clip(RoundedCornerShape(width * 0.11f)).background(bg).border(if (state == SpeedState.OK) 1.dp else 2.dp, edge, RoundedCornerShape(width * 0.11f)),
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val cx = size.width / 2f
      val cy = size.height * 0.70f
      val r = size.width * 0.36f
      val stroke = size.width * 0.058f
      val n = scaleMax / 5
      val seg = 180f / n
      val gap = seg * 0.26f
      val shown = speedKmh ?: 0
      for (i in 0 until n) {
        val on = (i + 1) * 5 <= shown + 2
        drawArc(
            color = if (on) lit else track,
            startAngle = 180f + i * seg + gap / 2f,
            sweepAngle = seg - gap,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(2 * r, 2 * r),
            style = Stroke(width = stroke, cap = StrokeCap.Butt),
        )
      }
      if (effective != null) {
        val a = Math.toRadians(180.0 + 180.0 * (effective.coerceIn(0, scaleMax).toDouble() / scaleMax))
        val r1 = r - stroke * 0.95f
        val r2 = r + stroke * 0.95f
        drawLine(
            if (state == SpeedState.FAR) Color(0xFF0E1216) else Color.White,
            Offset(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat()),
            Offset(cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat()),
            strokeWidth = size.width * 0.016f,
            cap = StrokeCap.Round,
        )
      }
    }
    Column(Modifier.fillMaxWidth().padding(top = height * 0.30f), horizontalAlignment = Alignment.CenterHorizontally) {
      Text(speedKmh?.toString() ?: "–", color = numberColor, fontSize = (w * 0.235f).sp, fontWeight = FontWeight.Bold,
          lineHeight = (w * 0.235f).sp)
      Text("km/h", color = if (state == SpeedState.FAR) Color(0xDDFFFFFF) else Nm.Muted, fontSize = (w * 0.055f).sp, fontWeight = FontWeight.SemiBold)
    }
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = height * 0.05f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(width * 0.03f),
    ) {
      if (effective != null) {
        val sign = width * 0.13f
        Box(
            Modifier.size(sign).clip(CircleShape).background(Color.White).border(sign * 0.13f, NmRed, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(effective.toString(), color = Color.Black, fontSize = (w * 0.052f).sp, fontWeight = FontWeight.Bold) }
      }
      val label = when {
        sectionAvg != null -> "media $sectionAvg" + (sectionLimit?.let { " / $it" } ?: "")
        forVehicle -> "limite del mezzo"
        effective != null -> "limite"
        else -> null
      }
      if (label != null) {
        Text(label, color = if (state == SpeedState.FAR) Color.White else if (sectionAvg != null) Color(0xFF8DB8FF) else Nm.Muted,
            fontSize = (w * 0.052f).sp, fontWeight = if (sectionAvg != null) FontWeight.Bold else FontWeight.Normal)
      }
    }
  }
}

// ------------------------------------------------------------------------------ places while driving

/** A pin of a place on the map while driving (the panel's places, with their distance on a tag). */
data class PoiPin(val key: String, val lat: Double, val lon: Double, val glyph: String, val color: Color, val tag: String, val poi: RoutePoi)

/** The quick choices of the magnifier: what the driver looks for, in one touch. */
enum class QuickPoi(val label: String, val glyph: String, val color: Color, val cats: Set<String>, val subs: Set<String> = emptySet()) {
  FUEL("Gasolio", PoiGlyphs.FUEL, PoiGlyphs.Blue, setOf("fuel")),
  ADBLUE("AdBlue", PoiGlyphs.DROP, PoiGlyphs.LightBlue, setOf("fuel"), setOf("fuel:adblue")),
  // the motorway service areas have lorry parking: kept all (OpenStreetMap seldom says so)
  PARKING("Posti TIR", PoiGlyphs.PARKING, PoiGlyphs.Violet, setOf("truck_parking", "services", "rest_area"), setOf("rest_area:hgv")),
  SERVICES("Area servizio", PoiGlyphs.SERVICES, PoiGlyphs.Teal, setOf("services")),
  FOOD("Ristoro", PoiGlyphs.FOOD, PoiGlyphs.Orange, setOf("food")),
  SHOWER("Docce", PoiGlyphs.SHOWER, PoiGlyphs.Cyan, setOf("shower")),
  CAMPER("Camper", PoiGlyphs.CAMPER, PoiGlyphs.Green, setOf("camper_site", "camper_parking", "camper_service", "campsite")),
  WC("WC", PoiGlyphs.WC, PoiGlyphs.Grey, setOf("toilets")),
  REPAIR("Officina", PoiGlyphs.REPAIR, PoiGlyphs.Amber, setOf("truck_repair", "tyres")),
}

/** Where the magnifier's search looks. */
enum class SearchScope(val label: String) {
  ROUTE("Lungo il percorso"),
  HERE("Vicino a me"),
  DESTINATION("Vicino alla destinazione"),
}

private enum class PanelMode { LIST, CHOOSE, SEARCH, RESULTS }

/** What the magnifier finds, with a note when it had to look elsewhere ("none along the route"). */
data class PoiFound(val list: List<RoutePoi>, val note: String? = null)

/**
 * Where the driving panel finds its places: the app's offline databases ([AppPoiSource]), or
 * fixed lists in the screenshot tests.
 */
interface PoiSource {
  suspend fun quick(q: QuickPoi, traveledM: Double): PoiFound
  suspend fun search(text: String, scope: SearchScope, traveledM: Double): PoiFound
}

/**
 * The places of the offline databases (poi.sqlite of every installed country): along [route]
 * within 1.5 km, or near the vehicle / the destination. A quick choice with nothing along the
 * route shows the nearest ones around the vehicle instead (and says so). Fuel stations are not
 * filtered on "for lorries": OpenStreetMap marks very few of them (245 of 32 000 in Italy), the
 * badge "TIR" says which ones are.
 */
class AppPoiSource(
    route: List<GeographicCoordinate>?,
    private val here: () -> GeographicCoordinate?,
    private val destination: GeographicCoordinate?,
) : PoiSource {
  private val matcher = route?.takeIf { it.size > 1 }?.let { lazy { RouteMatcher(it) } }

  override suspend fun quick(q: QuickPoi, traveledM: Double): PoiFound = withContext(Dispatchers.IO) {
    runCatching {
      val m = matcher?.value
      val along = if (m == null) emptyList() else
        AppGraph.poi.alongRoute(m, q.cats, false, 1500, q.subs).filter { it.alongM > traveledM + 30 }.take(8)
      if (along.isNotEmpty()) return@runCatching PoiFound(along)
      // nothing on the way: the nearest around the vehicle
      val c = here() ?: return@runCatching PoiFound(emptyList())
      val near = AppGraph.poi.near(c.lat, c.lng, 30_000.0, q.cats, limit = 60)
          .filter { (p, _) -> AppGraph.poi.passes(p, q.subs) }
          .take(8).map { (p, d) -> RoutePoi(p, traveledM + d, -1.0) }
      PoiFound(near, if (near.isEmpty()) null else "Nessuno lungo il percorso: i più vicini a te")
    }.getOrDefault(PoiFound(emptyList()))
  }

  override suspend fun search(text: String, scope: SearchScope, traveledM: Double): PoiFound = withContext(Dispatchers.IO) {
    PoiFound(runCatching { searchPlaces(text, scope, matcher?.value, here(), destination, traveledM) }.getOrDefault(emptyList()))
  }
}

/**
 * The places while driving, in a column at the right under the speedometer: the nearest of the
 * driver's kinds in a big card, the next ones in a line each, "altri" for the whole list and the
 * magnifier. The magnifier opens "Cosa cerchi?" (quick choices: fuel, AdBlue, lorry parking…, and
 * "Cerca" for a name or a kind along the route, near the vehicle or near the destination); a
 * choice shows only those places, in the column and on the map. It never grows beyond
 * [maxHeight]: what does not fit scrolls. [onOpen] says when the magnifier's panels are open
 * (the speedometer and the buttons make room for them).
 */
@Composable
fun PoiColumn(
    pois: List<RoutePoi>,
    traveledM: Double,
    source: PoiSource,
    settings: app.navmaster.truck.settings.Settings,
    hasHere: Boolean,
    hasDestination: Boolean,
    secondsPerMetre: Double?,
    canType: Boolean,
    width: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    startMode: String? = null,
    onPoi: (RoutePoi) -> Unit,
    onAll: () -> Unit,
    onPins: (List<PoiPin>) -> Unit,
    onOpen: (Boolean) -> Unit = {},
    onRemember: (String) -> Unit = {},
) {
  val panel = Nm.PanelSolid
  var mode by remember { mutableStateOf(startMode?.let { m -> PanelMode.entries.firstOrNull { it.name == m } } ?: PanelMode.LIST) }
  var quick by remember { mutableStateOf<QuickPoi?>(null) }
  var query by remember { mutableStateOf("") }
  var scope by remember { mutableStateOf(SearchScope.ROUTE) }
  var found by remember { mutableStateOf<PoiFound?>(null) }
  var resultsTitle by remember { mutableStateOf("") }
  var resultsGlyph by remember { mutableStateOf(PoiGlyphs.SEARCH to PoiGlyphs.Green) }
  val scopeAll = rememberCoroutineScope()
  LaunchedEffect(mode) { onOpen(mode != PanelMode.LIST) }

  // the nearest of each kind ahead (a parking, a fuel station, a service area…), as the side panel did
  // every place ahead (80 km), nearest first: a list to scroll, as the magnifier's results
  val ahead = remember(pois, (traveledM / 100).toInt()) {
    pois.filter { it.alongM > traveledM + 50 && it.alongM < traveledM + 80_000 }.sortedBy { it.alongM }.take(40)
  }

  // after a while the list folds to its first line (the setting of the side panel), opened again
  // by new places ahead or a touch
  var folded by remember { mutableStateOf(false) }
  val keys = ahead.take(3).map { it.poi.key }
  var seen by remember { mutableStateOf(emptySet<String>()) }
  LaunchedEffect(keys) {
    if (keys.any { it !in seen }) folded = false
    seen = seen + keys
  }
  LaunchedEffect(folded, keys, mode, settings.poiRailSeconds) {
    if (!folded && mode == PanelMode.LIST && settings.poiRailSeconds > 0) {
      delay(settings.poiRailSeconds * 1000L)
      folded = true
    }
  }

  fun eta(p: RoutePoi): String? =
      if (p.offRouteM < 0) null else secondsPerMetre?.let { Fmt.duration((p.alongM - traveledM).coerceAtLeast(0.0) * it) }

  fun distOf(p: RoutePoi): String = Fmt.distanceText((p.alongM - traveledM).coerceAtLeast(0.0))

  fun runQuick(q: QuickPoi) {
    quick = q
    resultsTitle = q.label
    resultsGlyph = q.glyph to q.color
    found = null
    mode = PanelMode.RESULTS
    scopeAll.launch { found = source.quick(q, traveledM) }
  }

  fun runSearch() {
    val text = query.trim()
    if (text.length < 2) return
    quick = null
    resultsTitle = "«$text» · " + scope.label.lowercase()
    resultsGlyph = PoiGlyphs.SEARCH to PoiGlyphs.Green
    found = null
    mode = PanelMode.RESULTS
    onRemember(text)
    val sc = scope
    scopeAll.launch { found = source.search(text, sc, traveledM) }
  }

  // the pins on the map: the places shown in the column (the first ones)
  // the pins on the map: the places of the next 3 km (seen well before they are reached), with
  // the name when the settings say so; the magnifier's results all (the first 8)
  val pinSource = when (mode) {
    PanelMode.RESULTS -> found?.list.orEmpty().take(8)
    else -> ahead.filter { it.alongM < traveledM + 3_000 }.take(8)
  }
  val pinKey = pinSource.map { it.poi.key + ":" + distOf(it) } + settings.poiPinNames.toString()
  LaunchedEffect(pinKey) {
    onPins(pinSource.map { p ->
      val q = quick
      val (g, c) = if (mode == PanelMode.RESULTS && q != null) q.glyph to q.color else PoiGlyphs.of(p.poi.cat)
      val name = p.poi.title.trim()
      val tag = if (settings.poiPinNames && name.isNotEmpty()) (if (name.length > 16) name.take(15) + "…" else name) + " · " + distOf(p) else distOf(p)
      PoiPin(p.poi.key, p.poi.lat, p.poi.lon, g, c, tag, p)
    })
  }
  DisposableEffect(Unit) { onDispose { onPins(emptyList()); onOpen(false) } }

  val lensSize = 56.dp
  Column(modifier.width(width).heightIn(max = maxHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (mode) {
      PanelMode.LIST -> if (folded && ahead.isNotEmpty()) {
        // folded (nothing touched for a while): one small tab with the nearest place and the
        // magnifier; a touch opens the list again
        val first = ahead.first()
        val (g, c) = PoiGlyphs.of(first.poi.cat)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Row(
              Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(panel)
                  .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp)).clickable { folded = false }
                  .semantics { contentDescription = "Apri i punti di interesse" }.padding(horizontal = 8.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            GlyphTile(g, c, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(distOf(first), color = Nm.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(first.poi.title.ifBlank { PoiCategories.byId(first.poi.cat)?.label ?: "" }, color = Nm.Text, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾", color = Nm.Muted, fontSize = 14.sp)
          }
          Spacer(Modifier.width(8.dp))
          LensButton(44.dp) { mode = PanelMode.CHOOSE }
        }
      } else {
        // the places ahead, one line each, scrolling in the room there is (the first lines fade
        // out at the edges instead of being cut); the arrow folds the list to its tab
        val listState = rememberScrollState()
        Column(
            Modifier.weight(1f, fill = false).fadeEdges(listState).verticalScroll(listState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (p in ahead) PoiLine(p, distOf(p), eta(p), panel, highlight = null) { onPoi(p) }
          if (ahead.isEmpty()) Text("Nessun posto in vista lungo il percorso.", color = Nm.Muted, fontSize = 13.sp,
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(panel).padding(12.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (ahead.isNotEmpty()) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(panel).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
                    .clickable { folded = true }.semantics { contentDescription = "Riduci i punti di interesse" },
                contentAlignment = Alignment.Center,
            ) { Text("▴", color = Nm.Text, fontSize = 18.sp) }
            Text("tutti ›", color = Nm.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(panel).clickable(onClick = onAll)
                    .padding(horizontal = 12.dp, vertical = 12.dp))
          }
          Spacer(Modifier.weight(1f))
          LensButton(lensSize) { mode = PanelMode.CHOOSE }
        }
      }
      PanelMode.CHOOSE -> ChoosePanel(panel, maxHeight,
          onPick = { runQuick(it) }, onSearch = { mode = PanelMode.SEARCH }, onClose = { mode = PanelMode.LIST })
      PanelMode.SEARCH -> SearchPanel(panel, maxHeight, query, { query = it }, scope, { scope = it }, hasDestination, hasHere,
          canType, settings.poiRecent, onRecent = { query = it; runSearch() }, onGo = { runSearch() }, onBack = { mode = PanelMode.CHOOSE })
      PanelMode.RESULTS -> {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(panel).border(1.dp, resultsGlyph.second, RoundedCornerShape(16.dp))
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          GlyphTile(resultsGlyph.first, resultsGlyph.second, 32.dp)
          Spacer(Modifier.width(10.dp))
          Text(resultsTitle, color = Nm.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f))
          IconButtonSmall(PoiGlyphs.CLOSE, "Togli il filtro") { mode = PanelMode.LIST; found = null; quick = null }
        }
        val f = found
        // the list scrolls in the room left between the title and the magnifier
        val resultsState = rememberScrollState()
        Column(
            Modifier.weight(1f, fill = false).fadeEdges(resultsState).verticalScroll(resultsState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          when {
            f == null -> Row(Modifier.clip(RoundedCornerShape(14.dp)).background(panel).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(color = Nm.Accent, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
              Spacer(Modifier.width(10.dp))
              Text("Cerco…", color = Nm.Text, fontSize = 14.sp)
            }
            f.list.isEmpty() -> Text("Niente trovato lungo il percorso né vicino a te.", color = Nm.Text, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(panel).padding(14.dp))
            else -> {
              f.note?.let {
                Text(it, color = Nm.Amber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(panel).padding(horizontal = 12.dp, vertical = 8.dp))
              }
              for ((i, p) in f.list.withIndex()) {
                PoiLine(p, distOf(p), eta(p), panel, highlight = if (i == 0) resultsGlyph.second else null) { onPoi(p) }
              }
            }
          }
        }
        Row(Modifier.fillMaxWidth()) {
          Spacer(Modifier.weight(1f))
          LensButton(lensSize) { mode = PanelMode.CHOOSE }
        }
      }
    }
  }
}

/**
 * The lines that scroll out of a list fade away at its top and bottom edges (no sharp cut): only
 * at the edge there is more to scroll towards.
 */
private fun Modifier.fadeEdges(state: androidx.compose.foundation.ScrollState, edge: Dp = 22.dp): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
      drawContent()
      val px = edge.toPx()
      if (state.value > 0) {
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = px),
            size = Size(size.width, px), blendMode = BlendMode.DstIn)
      }
      if (state.value < state.maxValue) {
        drawRect(
            Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - px, endY = size.height),
            topLeft = Offset(0f, size.height - px), size = Size(size.width, px), blendMode = BlendMode.DstIn)
      }
    }

@Composable
private fun LensButton(size: Dp, onClick: () -> Unit) {
  Box(
      Modifier.size(size).shadow(8.dp, CircleShape).clip(CircleShape).background(Nm.AccentDark).clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) { Glyph(PoiGlyphs.SEARCH, Color.White, size * 0.5f, description = "Cerca punti di interesse") }
}

@Composable
private fun IconButtonSmall(d: String, description: String, onClick: () -> Unit) {
  Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Glyph(d, Color(0xFFC9D1D9), 20.dp, description = description)
  }
}

@Composable
private fun PoiLine(p: RoutePoi, dist: String, eta: String?, bg: Color, highlight: Color?, onClick: () -> Unit) {
  val (g, c) = PoiGlyphs.of(p.poi.cat)
  val cat = PoiCategories.byId(p.poi.cat)
  Row(
      Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(bg)
          .border(1.dp, highlight ?: Color(0x1AFFFFFF), RoundedCornerShape(16.dp)).clickable(onClick = onClick)
          .padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    GlyphTile(g, c, 36.dp)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(p.poi.title.ifBlank { cat?.label ?: "" }, color = Nm.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      val tags = app.navmaster.truck.poi.poiBadges(p.poi.flags).take(2) +
          listOfNotNull(if (p.offRouteM > 150) "a ${p.offRouteM.toInt()} m" else null, if (p.offRouteM < 0) "in linea d'aria" else null)
      if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Nm.Muted, fontSize = 12.sp, maxLines = 1)
    }
    Column(horizontalAlignment = Alignment.End) {
      Text(dist, color = Nm.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      if (eta != null) Text(eta, color = Nm.Muted, fontSize = 12.sp)
    }
  }
}

@Composable
private fun ChoosePanel(bg: Color, maxHeight: Dp, onPick: (QuickPoi) -> Unit, onSearch: () -> Unit, onClose: () -> Unit) {
  // the tiles lower when the room is short (lying down, a phone): the panel scrolls if still too tall
  val tileH = if (maxHeight < 330.dp) 50.dp else 64.dp
  // very short (a phone lying down): «Cerca» goes into the title line, always in sight
  val searchOnTop = maxHeight < 300.dp
  Column(
      Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(22.dp)).background(bg)
          .border(1.dp, Nm.Accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp)).padding(start = 12.dp, end = 6.dp, bottom = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("COSA CERCHI?", color = Color(0xFF8FE0AE), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
          modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
      if (searchOnTop) {
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(Nm.AccentDark).clickable(onClick = onSearch)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Glyph(PoiGlyphs.SEARCH, Color.White, 16.dp)
          Spacer(Modifier.width(6.dp))
          Text("Cerca", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
      }
      IconButtonSmall(PoiGlyphs.CLOSE, "Chiudi", onClose)
    }
    Column(Modifier.padding(end = 6.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (row in QuickPoi.entries.chunked(3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (q in row) {
            Column(
                Modifier.weight(1f).height(tileH).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E252D)).clickable { onPick(q) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
              Glyph(q.glyph, q.color, if (tileH < 60.dp) 20.dp else 24.dp, description = q.label)
              Spacer(Modifier.height(3.dp))
              Text(q.label, color = Nm.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
      if (!searchOnTop) Row(
          Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(Nm.AccentDark).clickable(onClick = onSearch),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Glyph(PoiGlyphs.SEARCH, Color.White, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text("Cerca", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
private fun SearchPanel(
    bg: Color,
    maxHeight: Dp,
    query: String,
    onQuery: (String) -> Unit,
    scope: SearchScope,
    onScope: (SearchScope) -> Unit,
    hasDestination: Boolean,
    hasHere: Boolean,
    canType: Boolean,
    recent: List<String>,
    onRecent: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
) {
  Column(
      Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(22.dp)).background(bg)
          .border(1.dp, Nm.Accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp)).padding(start = 12.dp, end = 6.dp, bottom = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("CERCA", color = Color(0xFF8FE0AE), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
          modifier = Modifier.weight(1f).padding(start = 4.dp))
      IconButtonSmall(PoiGlyphs.CLOSE, "Indietro", onBack)
    }
    Column(Modifier.padding(end = 6.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
          query, onQuery, singleLine = true, enabled = canType,
          label = { Text(if (canType) "Nome o tipo di posto" else "Per scrivere fermati (o usa le recenti)") },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { onGo() }),
          modifier = Modifier.fillMaxWidth(),
          colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent,
              disabledTextColor = Nm.Muted),
      )
      // where to look: along the route, around the vehicle, around the destination
      for (s in SearchScope.entries) {
        val usable = when (s) {
          SearchScope.ROUTE -> true
          SearchScope.HERE -> hasHere
          SearchScope.DESTINATION -> hasDestination
        }
        if (!usable) continue
        val on = s == scope
        Text(s.label, color = Nm.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) Nm.Accent.copy(alpha = 0.18f) else Color(0xFF1E252D))
                .border(1.dp, if (on) Nm.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable { onScope(s) }.padding(horizontal = 12.dp, vertical = 12.dp))
      }
      Row(
          Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
              .background(if (query.trim().length >= 2) Nm.AccentDark else Color(0xFF2A323C)).clickable(enabled = query.trim().length >= 2, onClick = onGo),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Glyph(PoiGlyphs.SEARCH, Color.White, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text("Cerca", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
      if (recent.isNotEmpty()) {
        Text("Ricerche recenti", color = Nm.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
        for (r in recent.take(3)) {
          Text(r, color = Nm.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
              modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E252D))
                  .clickable { onRecent(r) }.padding(horizontal = 12.dp, vertical = 12.dp))
        }
      }
    }
  }
}

/**
 * The search of the magnifier: places whose name matches [text] and places of the kinds whose name
 * matches it ("lavaggio" → lorry wash, "gommista" → tyres), along the route ahead, near the
 * vehicle (25 km) or near the destination (10 km). Places off the route carry offRouteM = -1 and
 * their straight distance in alongM (counted from [traveledM]).
 */
fun searchPlaces(
    text: String,
    scope: SearchScope,
    m: RouteMatcher?,
    here: GeographicCoordinate?,
    destination: GeographicCoordinate?,
    traveledM: Double,
): List<RoutePoi> {
  val cats = categoriesFor(text)
  val byName: List<Poi> = AppGraph.poi.search(text, 200)
  val out = LinkedHashMap<String, RoutePoi>()
  when (scope) {
    SearchScope.ROUTE -> {
      val mm = m ?: return emptyList()
      if (cats.isNotEmpty()) for (p in AppGraph.poi.alongRoute(mm, cats, false, 1500)) out.putIfAbsent(p.poi.key, p)
      for (p in byName) {
        val (d, along) = mm.nearest(p.coordinate) ?: continue
        if (d <= 1500) out.putIfAbsent(p.key, RoutePoi(p, along, d))
      }
      return out.values.filter { it.alongM > traveledM + 30 }.sortedBy { it.alongM }.take(8)
    }
    else -> {
      val c = (if (scope == SearchScope.HERE) here else destination) ?: return emptyList()
      val radius = if (scope == SearchScope.HERE) 25_000.0 else 10_000.0
      if (cats.isNotEmpty()) {
        for ((p, d) in AppGraph.poi.near(c.lat, c.lng, radius, cats, limit = 60)) out.putIfAbsent(p.key, RoutePoi(p, traveledM + d, -1.0))
      }
      for (p in byName) {
        val d = app.navmaster.truck.core.Geo.dist(c.lat, c.lng, p.lat, p.lon)
        if (d <= radius) out.putIfAbsent(p.key, RoutePoi(p, traveledM + d, -1.0))
      }
      return out.values.sortedBy { it.alongM }.take(8)
    }
  }
}

/** The kinds of place a search text names ("gasolio", "parcheggio", "lavaggio camion"…). */
fun categoriesFor(text: String): Set<String> {
  // two letters are enough for «wc»; the names of the kinds are matched from four letters
  val words = text.lowercase().split(' ', ',', '.').filter { it.length >= 2 }
  val out = HashSet<String>()
  for (c in PoiCategories.all) {
    val l = c.label.lowercase()
    if (words.any { w -> w.length >= 4 && (l.contains(w.take(6)) || w.contains(l.take(6))) }) out += c.id
  }
  for (w in words) {
    when {
      w.startsWith("gomm") -> out += "tyres"
      w.startsWith("lavag") -> out += "truck_wash"
      w.startsWith("offic") || w.startsWith("mecca") -> out += "truck_repair"
      w.startsWith("gasol") || w.startsWith("diesel") || w.startsWith("benzi") || w.startsWith("carbur") || w.startsWith("distrib") -> out += "fuel"
      w.startsWith("parche") || w.startsWith("sosta") -> { out += "truck_parking"; out += "rest_area" }
      w.startsWith("ristor") || w.startsWith("mangi") || w == "bar" -> out += "food"
      w.startsWith("docc") -> out += "shower"
      w == "wc" || w.startsWith("bagn") -> out += "toilets"
      w.startsWith("albergo") || w.startsWith("hotel") || w.startsWith("motel") -> out += "hotel"
      w.startsWith("farmac") -> out += "pharmacy"
      w.startsWith("bancom") -> out += "atm"
      w.startsWith("camper") -> { out += "camper_site"; out += "camper_service"; out += "camper_parking" }
    }
  }
  return out
}

// ------------------------------------------------------------------------------ the buttons

/** A round button of the driving screen: [content] is its sign, [size] grows and shrinks with animation. */
@Composable
fun RoundBtn(
    size: Dp,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
  val s by animateDpAsState(size, label = "btn")
  // the gestures read the newest actions (the lambdas change at every recomposition)
  val tap by rememberUpdatedState(onClick)
  val longTap by rememberUpdatedState(onLongClick)
  Box(
      modifier.size(s).shadow(8.dp, CircleShape).clip(CircleShape).background(color).border(1.dp, Nm.Line, CircleShape)
          .semantics { contentDescription = description }
          .pointerInput(onLongClick != null) {
            val press: ((Offset) -> Unit)? = if (onLongClick == null) null else ({ _: Offset -> longTap?.invoke(); Unit })
            detectTapGestures(onTap = { tap() }, onLongPress = press)
          },
      contentAlignment = Alignment.Center,
  ) { content() }
}

/** A button of the bar that appears with a touch on the map: a sign and a word under it. */
data class BarButton(val label: String, val icon: ImageVector?, val text: String?, val color: Color, val onClick: () -> Unit)

/**
 * The buttons that are needed only now and then while driving (2D/3D, satellite, day/night,
 * voice, end), in one bar at the bottom left that appears with a touch on the map and goes away
 * after a few seconds; in the room left of the round buttons, so it never squeezes nor overlaps them.
 */
/** The lines the bar of [n] buttons takes in [maxWidth] (one when it fits, else two, evenly filled). */
fun controlsBarLines(n: Int, maxWidth: Dp): Int {
  val fit = ((maxWidth - 12.dp + 4.dp) / (62.dp + 4.dp)).toInt().coerceIn(1, n.coerceAtLeast(1))
  return (n + fit - 1) / fit
}

/** The height of the bar of [n] buttons in [maxWidth]. */
fun controlsBarHeight(n: Int, maxWidth: Dp): Dp = 12.dp + 60.dp * controlsBarLines(n, maxWidth) + 4.dp * (controlsBarLines(n, maxWidth) - 1)

@Composable
fun ControlsBar(visible: Boolean, items: List<BarButton>, maxWidth: Dp, modifier: Modifier = Modifier) {
  // one line when it fits, else two (an upright phone): never under the round buttons
  val itemW = 62.dp
  val gap = 4.dp
  val lines = controlsBarLines(items.size, maxWidth)
  val perRow = ((items.size + lines - 1) / lines).coerceAtLeast(1)
  androidx.compose.animation.AnimatedVisibility(
      visible,
      modifier,
      enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
      exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
  ) {
    Column(
        Modifier.shadow(10.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Nm.PanelSolid)
            .border(1.dp, Nm.Line, RoundedCornerShape(24.dp)).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
      for (line in items.chunked(perRow)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
          for (b in line) {
            Column(
                Modifier.width(itemW).heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = b.onClick)
                    .semantics { contentDescription = b.label }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
              Box(Modifier.size(34.dp).clip(CircleShape).background(b.color), contentAlignment = Alignment.Center) {
                if (b.icon != null) Icon(b.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                else Text(b.text ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
              }
              Spacer(Modifier.height(3.dp))
              Text(b.label, color = Nm.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
          }
        }
      }
    }
  }
}

/**
 * The driving screen under the manoeuvre bar, laid out so nothing overlaps: the speedometer at
 * the top right (when it is not beside the manoeuvre bar), the places' column under it at the
 * right, the round buttons ([rail], each told its size) at the bottom LEFT, and the bar of
 * [ControlsBar] at the bottom right, under the places' column (which stops above it while it is
 * shown). The bar and most buttons come with a touch on the map.
 */
@Composable
fun DriveSide(
    landscape: Boolean,
    arcInside: Boolean,
    arcHeight: Dp,
    arcWidth: Dp,
    showPoi: Boolean,
    railCount: Int,
    railSize: Dp,
    barVisible: Boolean,
    bar: List<BarButton>,
    barBottom: Dp,
    modifier: Modifier = Modifier,
    arc: @Composable () -> Unit,
    poi: @Composable (width: Dp, maxHeight: Dp) -> Unit,
    rail: @Composable (size: Dp) -> Unit,
) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    val boxH = maxHeight
    // the column keeps its width when the speedometer shrinks (the magnifier's panels need it)
    val colW = (maxWidth * (if (landscape) 0.22f else 0.34f)).coerceIn(230.dp, 290.dp)
        .coerceAtMost(maxWidth * if (landscape) 0.38f else 0.5f)
    val top = if (arcInside) arcHeight + 12.dp else 4.dp
    if (arcInside) Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp)) { arc() }
    // the round buttons smaller when they would not fit (a phone lying down), never below 40 dp
    val n = railCount.coerceAtLeast(1)
    val railRoom = boxH - top - 4.dp
    val railFit = minOf(railSize, (railRoom - 10.dp * (n - 1)) / n).coerceAtLeast(40.dp)
    // the bar at the bottom right, as wide as it may be left of the round buttons
    val barW = (maxWidth - railFit - 28.dp).coerceAtLeast(140.dp)
    val barH = if (barVisible) controlsBarHeight(bar.size, barW) + 8.dp else 0.dp
    val room = (boxH - top - barBottom - barH - 4.dp).coerceAtLeast(0.dp)
    if (showPoi && room >= 64.dp) Box(Modifier.align(Alignment.TopEnd).padding(top = top)) { poi(colW, room) }
    Column(Modifier.align(Alignment.BottomStart).padding(bottom = barBottom), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      rail(railFit)
    }
    ControlsBar(barVisible, bar, barW, Modifier.align(Alignment.BottomEnd).padding(bottom = barBottom))
  }
}
