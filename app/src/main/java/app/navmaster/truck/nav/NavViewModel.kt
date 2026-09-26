package app.navmaster.truck.nav

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.viewModelScope
import app.navmaster.truck.AppGraph
import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.limits.RouteLimit
import app.navmaster.truck.live.GeoBox
import app.navmaster.truck.live.LiveEvent
import app.navmaster.truck.live.LiveKind
import app.navmaster.truck.live.LiveMatch
import app.navmaster.truck.live.LiveRules
import app.navmaster.truck.live.RouteLiveEvent
import app.navmaster.truck.live.SharedReports
import app.navmaster.truck.live.TrafficFeeds
import app.navmaster.truck.poi.RoutePoi
import app.navmaster.truck.routing.CritKind
import app.navmaster.truck.routing.Criticality
import app.navmaster.truck.routing.RouteAnalysis
import app.navmaster.truck.routing.Severity
import app.navmaster.truck.settings.TollPolicy
import app.navmaster.truck.ui.Fmt
import app.navmaster.truck.vehicle.TripOptions
import com.stadiamaps.ferrostar.core.DefaultNavigationViewModel
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.location.toUserLocation
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

/** A stop of the trip; [via] = only pass through it (no stop, no "you have arrived"). */
data class Stop(val coordinate: GeographicCoordinate, val label: String, val via: Boolean = false)

enum class VariantKind(val label: String) {
  FASTEST("Più veloce"),
  NO_TOLL("Senza pedaggi"),
  ALTERNATIVE("Alternativa"),
}

/** One of the routes offered before departure, already checked for this vehicle. */
data class RouteVariant(
    val kind: VariantKind,
    val title: String,
    val route: Route,
    val analysis: RouteAnalysis,
    val limits: List<RouteLimit>,
    val criticalities: List<Criticality>,
    val options: TripOptions,
    val recommended: Boolean = false,
) {
  val durationS: Double
    get() = analysis.durationS

  val distanceM: Double
    get() = route.distance

  val critical: Int
    get() = criticalities.count { it.severity == Severity.CRITICAL }

  val warnings: Int
    get() = criticalities.count { it.severity == Severity.WARN }
}

data class PlanState(
    val stops: List<Stop> = emptyList(),
    val variants: List<RouteVariant> = emptyList(),
    val selected: Int = 0,
    val computing: Boolean = false,
    val error: String? = null,
    /** Short advice on the choice (e.g. the toll-free route costs only 2 minutes more). */
    val advice: String? = null,
    /** Points the driver asked to avoid. */
    val avoided: List<Criticality> = emptyList(),
    /** Next long press on the map adds a stop instead of changing the destination. */
    val addingStop: Boolean = false,
    /** Zones the driver marked on the map to stay away from. */
    val avoidAreas: List<GeographicCoordinate> = emptyList(),
) {
  val current: RouteVariant?
    get() = variants.getOrNull(selected)
}

/** A question the app asks the driver while driving (big buttons, and by voice). */
sealed class DriverPrompt {
  abstract val id: String

  data class TightRamp(override val id: String, val crit: Criticality, val distanceM: Double) : DriverPrompt()

  data class TollChoice(
      override val id: String,
      val extraMin: Int,
      val extraKm: Double,
      val tollKm: Double,
      val tollFree: Route,
      val distanceToTollM: Double,
  ) : DriverPrompt()

  data class Break(override val id: String, val drivenMin: Int, val parking: RoutePoi?) : DriverPrompt()

  /** The road ahead is closed (official information): look for another way? */
  data class Closure(override val id: String, val event: RouteLiveEvent, val distanceM: Double) : DriverPrompt()
}

data class NavExtras(
    val analysis: RouteAnalysis? = null,
    val limits: List<RouteLimit> = emptyList(),
    val criticalities: List<Criticality> = emptyList(),
    val pois: List<RoutePoi> = emptyList(),
    val routeLength: Double = 0.0,
    val prompt: DriverPrompt? = null,
    val drivenS: Long = 0,
    val recalculating: Boolean = false,
    /** Traffic events and drivers' reports on the route ahead. */
    val live: List<RouteLiveEvent> = emptyList(),
    /** A driver's report just passed: "still there?". */
    val liveAsk: RouteLiveEvent? = null,
    /** Where the live information comes from and how fresh it is ("TomTom, segnalazioni · 1 min fa"). */
    val liveInfo: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NavViewModel : DefaultNavigationViewModel(AppGraph.ferrostar, valhallaExtendedOSRMAnnotationPublisher()) {

  private val core = AppGraph.ferrostar
  private val locationProvider = AppGraph.locationProvider
  private val hasPermission = MutableStateFlow(false)
  private val lastLocation = MutableStateFlow<UserLocation?>(null)
  val location: StateFlow<UserLocation?> = lastLocation.asStateFlow()

  private val _plan = MutableStateFlow(PlanState())
  val plan: StateFlow<PlanState> = _plan.asStateFlow()

  private val _nav = MutableStateFlow(NavExtras())
  val nav: StateFlow<NavExtras> = _nav.asStateFlow()

  private val _simulating = MutableStateFlow(false)
  val simulating: StateFlow<Boolean> = _simulating.asStateFlow()

  private var planJob: Job? = null
  private val asked = mutableSetOf<String>()
  private var lastGeometryKey: String? = null

  /** Before navigation starts, the map still shows where the vehicle is. */
  override val navigationUiState: StateFlow<NavigationUiState> =
      combine(super.navigationUiState, lastLocation) { ui, loc -> if (ui.isNavigating()) ui else ui.copy(location = loc) }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NavigationUiState.empty())

  init {
    viewModelScope.launch {
      hasPermission
          .flatMapLatest { ok -> if (ok) locationProvider.locationUpdates(1000L).map { it.toUserLocation() } else emptyFlow() }
          .collect {
            if (lastLocation.value == null) Log.i(TAG, "prima posizione: ${it.coordinates.lat},${it.coordinates.lng}")
            lastLocation.value = it
          }
    }
    // everything that has to follow the vehicle while driving
    viewModelScope.launch { super.navigationUiState.collect { onNavUpdate(it) } }
  }

  fun setLocationPermission(granted: Boolean) {
    Log.i(TAG, "permesso posizione: $granted")
    hasPermission.value = granted
  }

  /** Emulator tests: a fixed starting position when the emulator GPS delivers nothing. */
  fun setTestLocation(lat: Double, lng: Double) {
    val l = android.location.Location("test").apply {
      latitude = lat
      longitude = lng
      accuracy = 5f
      time = System.currentTimeMillis()
    }
    Log.i(TAG, "posizione di prova: $lat,$lng")
    lastLocation.value = l.toUserLocation()
  }

  /**
   * Emulator diagnosis: the route of the active vehicle between consecutive points, in the log
   * (distance, time, toll), to find which stretch a vehicle cannot use. From tools/preview.sh.
   */
  fun probe(points: List<GeographicCoordinate>, debugCosting: Map<String, Double> = emptyMap()) {
    viewModelScope.launch(Dispatchers.IO) {
      val v = AppGraph.profiles.garage.value.active
      for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val from = android.location.Location("probe").apply {
          latitude = a.lat; longitude = a.lng; accuracy = 5f; time = System.currentTimeMillis()
        }.toUserLocation()
        val msg = try {
          val r = AppGraph.routes.routes(from, listOf(Waypoint(coordinate = b, kind = WaypointKind.BREAK)),
              TripOptions(debugCosting = debugCosting)).firstOrNull()
          if (r == null) "no route" else {
            val an = RouteAnalysis.analyse(AppGraph.engine, r, v, AppGraph.profiles.garage.value.loadT)
            "${"%.0f".format(r.distance)} m, ${"%.0f".format(r.steps.sumOf { it.duration })} s, toll ${"%.1f".format(an.tollKm)} km, " +
                "ways ${an.edges.map { it.wayId }.distinct().take(25)}"
          }
        } catch (e: Exception) {
          "error $e"
        }
        Log.i(TAG, "probe ${v.name} $debugCosting $i ${a.lat},${a.lng} -> ${b.lat},${b.lng}: $msg")
      }
    }
  }

  /**
   * Emulator diagnosis: the route of [refProfile] between two points, matched again on the graph
   * with the lorry's rules. Where the lorry's match leaves the reference route is the road the
   * lorry may not use (logged with its OSM way id).
   */
  fun probeCompare(a: GeographicCoordinate, b: GeographicCoordinate, refProfile: String, lorryProfile: String) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val from = android.location.Location("probe").apply {
          latitude = a.lat; longitude = a.lng; accuracy = 5f; time = System.currentTimeMillis()
        }.toUserLocation()
        AppGraph.profiles.select(refProfile)
        val load = AppGraph.profiles.garage.value.loadT
        val ref = AppGraph.profiles.garage.value.active
        val r = AppGraph.routes.routes(from, listOf(Waypoint(coordinate = b, kind = WaypointKind.BREAK)), TripOptions()).firstOrNull()
            ?: run { Log.i(TAG, "probecmp: no reference route"); return@launch }
        val refWays = RouteAnalysis.analyse(AppGraph.engine, r, ref, load).edges.map { it.wayId }.distinct()
        AppGraph.profiles.select(lorryProfile)
        val lorry = AppGraph.profiles.garage.value.active
        val lorryA = RouteAnalysis.analyse(AppGraph.engine, r, lorry, load)
        val lorryWays = lorryA.edges.map { it.wayId }.distinct()
        val firstDiff = refWays.indices.firstOrNull { it >= lorryWays.size || refWays[it] != lorryWays[it] }
        Log.i(TAG, "probecmp ${ref.name} ${r.distance.toInt()} m ways ${refWays.size}: $refWays")
        Log.i(TAG, "probecmp ${lorry.name} matched ${lorryA.edges.size} edges ways ${lorryWays.size}: $lorryWays")
        for (line in listOf(RouteAnalysis.speedsDebug(AppGraph.engine, r, ref, load),
            runCatching { RouteAnalysis.speedsDebug(AppGraph.engine, r, lorry, load) }.getOrElse { "lorry map_snap: $it" },
            runCatching { RouteAnalysis.speedsDebug(AppGraph.engine, r, lorry, load, "edge_walk") }.getOrElse { "lorry edge_walk: $it" })) {
          line.chunked(3000).forEach { Log.i(TAG, "probespeed $it") }
        }
        Log.i(TAG, "probecmp first difference at ${firstDiff ?: "none"}: ref ${firstDiff?.let { refWays.subList((it - 2).coerceAtLeast(0), (it + 3).coerceAtMost(refWays.size)) }} " +
            "lorry ${firstDiff?.let { lorryWays.subList((it - 2).coerceAtLeast(0).coerceAtMost(lorryWays.size), (it + 3).coerceAtMost(lorryWays.size)) }}")
      } catch (e: Exception) {
        Log.w(TAG, "probecmp: $e")
      }
    }
  }

  // ------------------------------------------------------------------------------ planning

  fun selectDestination(coordinate: GeographicCoordinate, label: String?) {
    val p = _plan.value
    val stop = Stop(coordinate, label ?: "Destinazione")
    val stops = if (p.addingStop && p.stops.isNotEmpty()) p.stops.dropLast(1) + stop + p.stops.last() else listOf(stop)
    _plan.value = p.copy(stops = stops, addingStop = false, variants = emptyList(), error = null, advice = null,
        avoided = if (p.addingStop) p.avoided else emptyList())
    planRoutes()
  }

  fun startAddingStop() = _plan.update { it.copy(addingStop = true) }

  /**
   * "Pass here": the point goes where it lengthens the trip the least (between the start and the
   * first stop, between two stops, ...), so the driver only has to show where, not when.
   */
  fun addVia(coordinate: GeographicCoordinate, label: String = "Passa di qui") {
    val p = _plan.value
    if (p.stops.isEmpty()) return selectDestination(coordinate, label)
    val start = lastLocation.value?.coordinates
    val pts = listOfNotNull(start) + p.stops.map { it.coordinate }
    val offset = if (start != null) 1 else 0
    var best = 0
    var bestCost = Double.MAX_VALUE
    for (i in 0 until p.stops.size) {
      val prev = pts.getOrNull(i + offset - 1) ?: continue
      val next = pts[i + offset]
      val cost = Geo.dist(prev, coordinate) + Geo.dist(coordinate, next) - Geo.dist(prev, next)
      if (cost < bestCost) {
        bestCost = cost
        best = i
      }
    }
    if (start == null) best = (p.stops.size - 1).coerceAtLeast(0)
    val stops = p.stops.toMutableList().apply { add(best, Stop(coordinate, label, via = true)) }
    _plan.value = p.copy(stops = stops, addingStop = false, variants = emptyList(), error = null, advice = null)
    planRoutes()
  }

  /** A zone of about 120 m the route must stay out of (a street the driver knows is bad). */
  fun avoidArea(coordinate: GeographicCoordinate) {
    _plan.update { it.copy(avoidAreas = it.avoidAreas + coordinate, variants = emptyList()) }
    planRoutes()
  }

  fun removeAvoidArea(index: Int) {
    _plan.update { it.copy(avoidAreas = it.avoidAreas.filterIndexed { i, _ -> i != index }, variants = emptyList()) }
    planRoutes()
  }

  fun cancelAddingStop() = _plan.update { it.copy(addingStop = false) }

  fun removeStop(index: Int) {
    val p = _plan.value
    if (p.stops.size <= 1) return clearPlan()
    _plan.value = p.copy(stops = p.stops.filterIndexed { i, _ -> i != index })
    planRoutes()
  }

  fun clearPlan() {
    planJob?.cancel()
    _plan.value = PlanState()
  }

  fun selectVariant(i: Int) = _plan.update { it.copy(selected = i.coerceIn(0, (it.variants.size - 1).coerceAtLeast(0))) }

  /** The driver does not want to go through this point: recompute avoiding it. */
  fun avoid(c: Criticality) {
    _plan.update { it.copy(avoided = it.avoided + c) }
    planRoutes()
  }

  fun unavoid(c: Criticality) {
    _plan.update { it.copy(avoided = it.avoided.filterNot { a -> a.id == c.id }) }
    planRoutes()
  }

  private fun exclusionsFor(list: List<Criticality>, route: RouteAnalysis?): List<List<List<Double>>> =
      list.flatMap { c ->
        // a square on the start of the difficult stretch (and on its middle when it is long)
        val pts = mutableListOf(c.lat to c.lon)
        if (route != null && c.endM - c.startM > 80) route.pointAt((c.startM + c.endM) / 2).let { pts += it.lat to it.lng }
        pts.map { (lat, lon) -> Geo.squareAround(lat, lon, 18.0) }
      }

  fun planRoutes(then: ((PlanState) -> Unit)? = null) {
    val p = _plan.value
    if (p.stops.isEmpty()) return
    val from = lastLocation.value
    if (from == null) {
      _plan.value = p.copy(error = "Posizione non ancora disponibile: attendi il segnale GPS o di rete")
      return
    }
    planJob?.cancel()
    _plan.value = p.copy(computing = true, error = null)
    planJob = viewModelScope.launch(Dispatchers.IO) {
      try {
        val settings = AppGraph.settings.settings.value
        val garage = AppGraph.profiles.garage.value
        val v = garage.active
        val waypoints = p.stops.mapIndexed { i, st ->
          Waypoint(coordinate = st.coordinate, kind = if (st.via && i < p.stops.size - 1) WaypointKind.VIA else WaypointKind.BREAK)
        }
        val exclusions = exclusionsFor(p.avoided, _plan.value.current?.analysis) +
            p.avoidAreas.map { Geo.squareAround(it.lat, it.lng, 60.0) }
        val avoidTolls = settings.tollPolicy == TollPolicy.AVOID
        val base = TripOptions(avoidTolls = avoidTolls, excludePolygons = exclusions)
        val main = AppGraph.routes.routes(from, waypoints, base.copy(alternates = 2))
        val noToll = if (!avoidTolls) runCatching { AppGraph.routes.routes(from, waypoints, base.copy(avoidTolls = true)) }.getOrNull() else null
        val departure = LocalDateTime.now()

        fun build(kind: VariantKind, title: String, r: Route, opts: TripOptions): RouteVariant {
          val analysis = RouteAnalysis.analyse(AppGraph.engine, r, v, garage.loadT)
          val m = RouteMatcher(r.geometry)
          val weight = v.tripWeightT(garage.loadT)
          val limits = AppGraph.limits.scan(m, v, weight, departure, a = analysis)
          val crit = AppGraph.criticalities.find(analysis, m, limits, v, garage.loadT, departure)
          return RouteVariant(kind, title, r, analysis, limits, crit, opts)
        }

        val variants = mutableListOf<RouteVariant>()
        main.firstOrNull()?.let { variants += build(VariantKind.FASTEST, if (avoidTolls) "Senza pedaggi" else "Più veloce", it, base) }
        for ((i, r) in main.drop(1).withIndex()) {
          if (variants.any { same(it.route, r) }) continue
          variants += build(VariantKind.ALTERNATIVE, "Alternativa ${i + 1}", r, base)
        }
        val fastest = variants.firstOrNull()
        var advice: String? = null
        var recommended = 0
        if (noToll != null && fastest != null && fastest.analysis.tollKm > 0.5) {
          val nt = noToll.firstOrNull()
          // the graph may give back a route with tolls anyway (start or end on a motorway): it is a
          // toll-free choice only when it really saves the toll
          val existing = nt?.let { r -> variants.indexOfFirst { same(it.route, r) } } ?: -1
          val candidate = when {
            nt == null -> null
            existing >= 0 -> variants[existing].copy(kind = VariantKind.NO_TOLL, title = "Senza pedaggi")
            else -> build(VariantKind.NO_TOLL, "Senza pedaggi", nt, base.copy(avoidTolls = true))
          }?.takeIf { it.analysis.tollKm < fastest.analysis.tollKm - 0.5 && it.analysis.tollKm < 0.5 }
          if (nt != null && candidate == null) {
            advice = "Il pedaggio qui non si può evitare: ${km(fastest.analysis.tollKm, false)} a pagamento."
          }
          if (candidate != null) {
            val ntVariant = candidate
            if (existing >= 0) variants[existing] = ntVariant else variants += ntVariant
            val extraS = ntVariant.durationS - fastest.durationS
            val extraMin = (extraS / 60).roundToInt().coerceAtLeast(0)
            val extraKm = (ntVariant.distanceM - fastest.distanceM) / 1000.0
            val tollKm = fastest.analysis.tollKm
            val worse = ntVariant.critical > fastest.critical || ntVariant.warnings > fastest.warnings + 2
            advice = when {
              extraS <= settings.tollMaxExtraMin * 60 && !worse -> {
                if (settings.tollPolicy == TollPolicy.ASK) recommended = variants.indexOf(ntVariant)
                "Senza pedaggio arrivi solo $extraMin min più tardi (${km(extraKm)}) ed eviti ${km(tollKm, false)} a pagamento."
              }
              extraS <= settings.tollMaxExtraMin * 60 && worse ->
                "Senza pedaggio impieghi solo $extraMin min in più, ma la strada ha più punti difficili: meglio l'autostrada."
              else -> "Col pedaggio risparmi $extraMin min: ${km(tollKm, false)} a pagamento."
            }
          }
        }
        val list = variants.mapIndexed { i, x -> if (i == recommended) x.copy(recommended = true) else x }
        if (list.isEmpty()) throw IllegalStateException("Nessun percorso trovato")
        for (x in list) Log.i(TAG, "variant ${x.title}: ${(x.durationS / 60).roundToInt()} min, ${"%.1f".format(x.distanceM / 1000)} km, " +
            "toll ${"%.1f".format(x.analysis.tollKm)} km, booths ${x.analysis.tollBooths.size}, crit ${x.critical}/${x.warnings}${if (x.recommended) " CONSIGLIATO" else ""}")
        Log.i(TAG, "advice: $advice")
        val state = _plan.value.copy(variants = list, selected = recommended, computing = false, advice = advice)
        _plan.value = state
        if (advice != null && settings.voiceWarnings && settings.tollPolicy == TollPolicy.ASK && recommended != 0) say(advice)
        withContext(Dispatchers.Main) { then?.invoke(state) }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "route failed", e)
        _plan.update { it.copy(computing = false, error = friendly(e)) }
      }
    }
  }

  private fun km(v: Double, signed: Boolean = true): String {
    val s = if (abs(v) < 10) String.format(java.util.Locale.ITALY, "%.1f km", abs(v)) else "${abs(v).roundToInt()} km"
    return if (!signed) s else if (v >= 0) "+$s" else "−$s"
  }

  private fun same(a: Route, b: Route): Boolean =
      Geo.almostSame(a.distance, b.distance, 0.004) && Geo.almostSame(a.steps.sumOf { it.duration }, b.steps.sumOf { it.duration }, 0.01)

  // ------------------------------------------------------------------------------ guidance

  fun start(simulate: Boolean) {
    val v = _plan.value.current ?: return
    _simulating.value = simulate
    simBase = null
    simOffset = 0.0
    if (simulate) locationProvider.enableSimulationOn(v.route) else locationProvider.disableSimulation()
    AppGraph.trip.value = v.options.copy(alternates = 0)
    asked.clear()
    lastGeometryKey = geometryKey(v.route.geometry)
    _nav.value = NavExtras(v.analysis, v.limits, v.criticalities, pois(v.route), v.analysis.length)
    if (navigationUiState.value.isNavigating()) core.replaceRoute(v.route) else core.startNavigation(v.route)
    startLive()
  }

  // ------------------------------------------------------------------------------ live traffic and reports

  private var liveJob: Job? = null
  private var liveEvents: List<LiveEvent> = emptyList()
  private var trafficEvents: List<LiveEvent> = emptyList()
  private var localReports: List<LiveEvent> = emptyList()
  private var lastTrafficAt = 0L
  private var liveMatcher: Pair<String, RouteMatcher>? = null
  private var liveSources: String? = null
  private var trafficSources: List<String> = emptyList()
  private var openEvents: List<LiveEvent> = emptyList()
  private var tomtomEvents: List<LiveEvent> = emptyList()
  private val tomtomTiles = HashMap<String, Pair<Long, List<LiveEvent>>>()
  private var lastMoveAt = 0L
  private var personalEvents: List<LiveEvent> = emptyList()
  private var lastPersonalAt = 0L
  private var personalMoveAt = 0L
  private var nationalMoveAt = 0L
  private var nationalEvents: List<LiveEvent> = emptyList()
  private var nationalCounts: List<String> = emptyList()

  /**
   * TomTom incidents of the road ahead (60 km on motorways, 30 km elsewhere), read as zoom 12
   * tiles: a tile is asked again only after 4 minutes on motorways and 8 elsewhere (twice as long
   * when less than a third of the day's budget is left), never while the vehicle stands still.
   */
  private fun refreshTomTom(key: String, a: RouteAnalysis, traveled: Double) {
    val now = System.currentTimeMillis()
    val speed = navigationUiState.value.location?.speed?.value ?: 0.0
    if (speed > 2.0 || lastMoveAt == 0L) lastMoveAt = now
    val fast = a.edgeAt(traveled)?.roadClass in setOf("motorway", "trunk")
    val end = minOf(a.length, traveled + if (fast) 60_000.0 else 30_000.0)
    val z = TrafficFeeds.INCIDENT_ZOOM
    val tiles = LinkedHashSet<String>()
    var at = traveled
    while (at <= end && tiles.size < 16) {
      val p = a.pointAt(at)
      val (x, y) = app.navmaster.truck.live.Mvt.tileOf(p.lat, p.lng, z)
      tiles += "$z/$x/$y"
      at += 500.0
    }
    tomtomTiles.keys.retainAll(tiles)
    if (now - lastMoveAt < 5 * 60_000L) {
      val maxAge = (if (fast) 4 else 8) * 60_000L * (if (app.navmaster.truck.live.TomTomGuard.leftToday() < 0.3) 2 else 1)
      var asked = 0
      for (t in tiles) {
        val old = tomtomTiles[t]
        if (old != null && now - old.first < maxAge) continue
        val (tz, tx, ty) = t.split('/').map { it.toInt() }
        val ev = TrafficFeeds.tomtomTile(key, tz, tx, ty) ?: continue
        tomtomTiles[t] = now to ev
        asked++
      }
      if (asked > 0) Log.i(TAG, "TomTom: $asked tiles asked, ${app.navmaster.truck.live.TomTomGuard.summary()}")
    }
    tomtomEvents = tomtomTiles.values.flatMap { it.second }.distinctBy { it.id }
  }

  private fun traveledNow(): Double =
      (_nav.value.routeLength - (navigationUiState.value.progress?.distanceRemaining ?: _nav.value.routeLength)).coerceAtLeast(0.0)

  private fun deviceTag(): String {
    val s = AppGraph.settings.settings.value
    if (s.deviceTag.isNotBlank()) return s.deviceTag
    val tag = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    AppGraph.settings.update { it.copy(deviceTag = tag) }
    return tag
  }

  /** Every minute while driving: the reports of the road ahead; every 4 minutes the official traffic. */
  private fun startLive() {
    liveJob?.cancel()
    lastTrafficAt = 0L
    liveJob = viewModelScope.launch(Dispatchers.IO) {
      delay(3_000)
      while (true) {
        runCatching { refreshLive() }.onFailure { Log.w(TAG, "live: $it") }
        delay(60_000)
      }
    }
  }

  private fun stopLive() {
    liveJob?.cancel()
    liveJob = null
    liveEvents = emptyList()
    trafficEvents = emptyList()
    openEvents = emptyList()
    tomtomEvents = emptyList()
    nationalEvents = emptyList()
    tomtomTiles.clear()
    lastTrafficAt = 0L
  }

  private fun refreshLive() {
    val s = AppGraph.settings.settings.value
    val a = _nav.value.analysis ?: return
    if (!s.liveReports && !s.liveTraffic) {
      _nav.update { it.copy(live = emptyList(), liveInfo = null) }
      return
    }
    val traveled = traveledNow()
    // the road ahead: points every 5 km for the next 80 km
    val ahead = generateSequence(traveled) { it + 5000.0 }.takeWhile { it <= minOf(a.length, traveled + 80_000.0) }
        .map { a.pointAt(it) }.toList() + a.pointAt(minOf(a.length, traveled + 80_000.0))
    val sources = mutableListOf<String>()
    var reports = emptyList<LiveEvent>()
    if (s.liveReports) {
      reports = SharedReports.read(s.reportsServer, SharedReports.topics(ahead), deviceTag())
      sources += "segnalazioni ${reports.size}"
    }
    val now = System.currentTimeMillis()
    if (s.liveTraffic && now - lastTrafficAt > 4 * 60_000L) {
      lastTrafficAt = now
      val list = mutableListOf<LiveEvent>()
      // German motorways: open data, no key
      val deRoads = a.edges.filter { it.endM > traveled && it.country?.uppercase() == "DE" && it.roadClass == "motorway" }
          .flatMap { it.refs }.mapNotNull { r -> Regex("^A ?(\\d{1,3})$").find(r)?.let { "A" + it.groupValues[1] } }.distinct()
      if (deRoads.isNotEmpty()) list += TrafficFeeds.autobahn(deRoads).also { sources += "Autobahn ${it.size}" }
      val hereKey = app.navmaster.truck.live.ApiKeys.here(s)
      if (hereKey.isNotBlank()) list += runCatching { TrafficFeeds.here(hereKey, boxesAhead(a, traveled)) }.getOrDefault(emptyList()).also { sources += "HERE ${it.size}" }
      openEvents = list
      trafficSources = sources.filterNot { it.startsWith("segnalazioni") }
    }
    // TomTom: incident tiles along the road ahead, each asked again only when old (see TomTomGuard)
    val tomtomKey = app.navmaster.truck.live.ApiKeys.tomtom(s)
    if (s.liveTraffic && tomtomKey.isNotBlank()) {
      runCatching { refreshTomTom(tomtomKey, a, traveled) }.onFailure { Log.w(TAG, "TomTom: $it") }
      sources += "TomTom ${tomtomEvents.size}"
      trafficSources = trafficSources.filterNot { it.startsWith("TomTom") } + "TomTom ${tomtomEvents.size}"
    } else tomtomEvents = emptyList()
    // the traffic centres of the countries crossed (open data), each file at its own pace (5 - 15
    // minutes, see NationalFeeds), only while moving
    if (s.liveTraffic && s.nationalTraffic) {
      val speed = navigationUiState.value.location?.speed?.value ?: 0.0
      if (speed > 2.0 || nationalMoveAt == 0L) nationalMoveAt = now
      val (nat, counts) = runCatching {
        app.navmaster.truck.live.NationalFeeds.read(boxesAhead(a, traveled), app.navmaster.truck.live.ApiKeys.trafikverket(s), now - nationalMoveAt < 5 * 60_000L)
      }.getOrElse { Log.w(TAG, "national: $it"); emptyList<LiveEvent>() to emptyList() }
      nationalEvents = nat
      nationalCounts = counts
      sources += counts
    } else {
      nationalEvents = emptyList()
      nationalCounts = emptyList()
    }
    trafficEvents = if (s.liveTraffic) openEvents + tomtomEvents + nationalEvents else emptyList()
    // personal test (see PersonalFeed): Waze asked by the tablet itself, or the owner's own server;
    // every 2 minutes, only the stretch of route ahead, never while standing still
    val direct = s.personalFeedDirect
    if (s.personalFeed && (direct || s.personalFeedUrl.isNotBlank())) {
      val speed = navigationUiState.value.location?.speed?.value ?: 0.0
      if (speed > 2.0 || personalMoveAt == 0L) personalMoveAt = now
      if (now - lastPersonalAt > 120_000L && now - personalMoveAt < 5 * 60_000L) {
        lastPersonalAt = now
        val got = if (direct) {
          // two areas of about 20 km of route each: small enough to get every report
          val parts = boxesAhead(a, traveled, 20_000.0, 2).map { box ->
            runCatching { app.navmaster.truck.live.PersonalFeed.readDirect(box) }.getOrNull()
          }
          if (parts.all { it == null }) null else parts.filterNotNull().flatten().distinctBy { it.id }
        } else boxesAhead(a, traveled).firstOrNull()?.let { box ->
          runCatching { app.navmaster.truck.live.PersonalFeed.read(s.personalFeedUrl, box) }.getOrNull()
        }
        if (got != null) personalEvents = got
      }
      sources += (if (direct) "Waze " else "Waze (computer) ") + personalEvents.size
    } else personalEvents = emptyList()
    // my own reports show at once, before ntfy gives them back
    localReports = localReports.filter { now - it.timeMs < it.kind.ttlMin * 60_000L && reports.none { r -> r.mine && r.kind == it.kind && Geo.dist(r.lat, r.lon, it.lat, it.lon) < 300 } }
    liveEvents = trafficEvents + reports + localReports + personalEvents
    Log.i(TAG, "live: ${sources.joinToString()} -> ${liveEvents.size} events")
    liveSources = (sources.filter { it.startsWith("segnalazioni") || it.startsWith("Waze") } +
        (if (s.liveTraffic) trafficSources + nationalCounts else emptyList()))
        .joinToString(" · ").ifBlank { null }
    matchLive()
  }

  /** Squares of about 40 km along the next 120 km of route (TomTom accepts up to 10,000 km² each). */
  private fun boxesAhead(a: RouteAnalysis, traveled: Double, stepM: Double = 40_000.0, count: Int = 4): List<GeoBox> {
    val out = mutableListOf<GeoBox>()
    var from = traveled
    val end = minOf(a.length, traveled + stepM * count)
    while (from < end && out.size < count) {
      val to = minOf(end, from + stepM)
      val pts = generateSequence(from) { it + 1000.0 }.takeWhile { it <= to }.map { a.pointAt(it) }.toList() + a.pointAt(to)
      val pad = 0.02
      out += GeoBox(pts.minOf { it.lng } - pad, pts.minOf { it.lat } - pad, pts.maxOf { it.lng } + pad, pts.maxOf { it.lat } + pad)
      from = to
    }
    return out
  }

  private fun matchLive() {
    val info = liveSources
    val a = _nav.value.analysis ?: return
    val key = geometryKey(a.route.geometry)
    val m = liveMatcher?.takeIf { it.first == key }?.second ?: RouteMatcher(a.route.geometry).also { liveMatcher = key to it }
    val traveled = traveledNow()
    val list = LiveMatch.onRoute(liveEvents, m).filter { it.endM > traveled - 300 }
        // police checks only where announcing them is allowed
        .filter { LiveRules.allowed(it.e.kind, a.edgeAt(it.startM)?.country) }
    val stamp = java.time.LocalTime.now().let { String.format(java.util.Locale.ITALY, "%02d:%02d", it.hour, it.minute) }
    _nav.update { it.copy(live = list, liveInfo = info?.let { i -> "$i · agg. $stamp" }) }
  }

  /** The driver reports something where he is now. */
  fun report(kind: LiveKind) {
    val loc = navigationUiState.value.location ?: lastLocation.value ?: return
    val c = loc.coordinates
    val heading = loc.courseOverGround?.degrees?.toDouble()
    val s = AppGraph.settings.settings.value
    val local = LiveEvent(id = "local:${System.currentTimeMillis()}", source = "La tua segnalazione", kind = kind, title = kind.label,
        lat = c.lat, lon = c.lng, timeMs = System.currentTimeMillis(), official = false, mine = true, headingDeg = heading)
    localReports = localReports + local
    liveEvents = liveEvents + local
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { matchLive() }
      val id = SharedReports.publish(s.reportsServer, deviceTag(), kind, c.lat, c.lng, heading)
      say(if (id != null) "Segnalazione inviata: ${kind.label.lowercase()}." else "Segnalazione salvata sul tablet: la invio appena c'è rete.")
    }
  }

  /** Answer to "still there?" on a report just passed. */
  fun voteLive(e: RouteLiveEvent, yes: Boolean) {
    _nav.update { it.copy(liveAsk = null) }
    if (e.e.official || e.e.mine || !e.e.id.startsWith("nx:")) return
    val s = AppGraph.settings.settings.value
    viewModelScope.launch(Dispatchers.IO) {
      if (!yes) {
        liveEvents = liveEvents.filterNot { it.id == e.e.id }
        runCatching { matchLive() }
      }
      SharedReports.vote(s.reportsServer, deviceTag(), e.e, yes)
    }
  }

  fun dismissLiveAsk() = _nav.update { it.copy(liveAsk = null) }

  /** The road ahead is closed: go around it (the closed stretch is avoided from here on). */
  fun answerClosure(avoid: Boolean) {
    val p = _nav.value.prompt as? DriverPrompt.Closure ?: return dismissPrompt()
    dismissPrompt()
    if (!avoid) return
    val a = _nav.value.analysis
    val pts = if (a != null) {
      generateSequence(p.event.startM) { it + 150.0 }.takeWhile { it <= p.event.endM }.take(6).map { a.pointAt(it) }.toList()
          .ifEmpty { listOf(a.pointAt(p.event.startM)) }
    } else listOf(GeographicCoordinate(p.event.e.lat, p.event.e.lon))
    val ex = pts.map { Geo.squareAround(it.lat, it.lng, 25.0) }
    AppGraph.trip.value = AppGraph.trip.value.let { it.copy(excludePolygons = it.excludePolygons + ex) }
    recalcFromHere("Cerco un percorso che eviti la strada chiusa")
  }

  override fun stopNavigation() {
    stopLive()
    locationProvider.disableSimulation()
    _simulating.value = false
    core.stopNavigation()
    _nav.value = NavExtras()
    AppGraph.trip.value = TripOptions()
    _plan.value = PlanState()
  }

  private fun pois(r: Route): List<RoutePoi> {
    val s = AppGraph.settings.settings.value
    return runCatching {
      AppGraph.poi.alongRoute(RouteMatcher(r.geometry), s.poiCategories, s.poiOnlyTruckFriendly && AppGraph.profiles.garage.value.active.type.heavy,
          s.poiMaxDetourM, s.poiSubs)
    }.getOrDefault(emptyList())
  }

  fun refreshPois() {
    val a = _nav.value.analysis ?: return
    viewModelScope.launch(Dispatchers.IO) { _nav.update { it.copy(pois = pois(a.route)) } }
  }

  private fun geometryKey(g: List<GeographicCoordinate>) = "${g.size}:${g.firstOrNull()}:${g.lastOrNull()}"

  private var lastTick = 0L
  private var stoppedSince = 0L

  private fun onNavUpdate(ui: NavigationUiState) {
    if (!ui.isNavigating()) return
    val geometry = ui.routeGeometry ?: return
    val extras = _nav.value
    // Ferrostar recalculated after a wrong turn: check the new route
    val key = geometryKey(geometry)
    if (key != lastGeometryKey && geometry.size > 1) {
      lastGeometryKey = key
      if (simJumpPending) simJumpPending = false else if (simBase != null) {
        simBase = null
        simOffset = 0.0
      }
      reanalyse(geometry)
    }
    val remaining = ui.progress?.distanceRemaining ?: return
    val traveled = (extras.routeLength - remaining).coerceAtLeast(0.0)

    // driving time (EU rules: a 45 minute break after 4 h 30 of driving)
    val now = System.currentTimeMillis()
    val speed = ui.location?.speed?.value ?: 0.0
    if (lastTick > 0) {
      val dt = (now - lastTick) / 1000
      if (speed > 1.5) {
        stoppedSince = 0
        _nav.update { it.copy(drivenS = it.drivenS + dt) }
      } else {
        if (stoppedSince == 0L) stoppedSince = now
        if (now - stoppedSince > 45 * 60_000L) _nav.update { it.copy(drivenS = 0) }
      }
    }
    lastTick = now
    onLiveUpdate(extras, traveled)
    runCatching { announce(ui, geometry, key) }.onFailure { Log.w(TAG, "voice: $it") }
    if (extras.prompt != null) {
      // a question nobody answered in time goes away
      val p = extras.prompt
      if (p is DriverPrompt.TightRamp && p.crit.startM - traveled < 150) dismissPrompt()
      if (p is DriverPrompt.TollChoice && remaining < 0) dismissPrompt()
      if (p is DriverPrompt.Closure && p.event.startM - traveled < 300) dismissPrompt()
      return
    }
    val settings = AppGraph.settings.settings.value

    // difficulties ahead: say them once, in time
    if (settings.voiceWarnings) {
      val c = extras.criticalities.firstOrNull { c ->
        c.kind != CritKind.RAMP && c.kind != CritKind.BAN && c.severity != Severity.INFO && c.startM - traveled in 0.0..1500.0 &&
            "say:${c.id}" !in asked
      }
      if (c != null) {
        asked += "say:${c.id}"
        say("Attenzione, tra ${Fmt.distanceText(c.startM - traveled).replace("km", "chilometri").replace(" m", " metri")}: ${c.title}.")
      }
    }

    // toll booths and borders: said once, about a kilometre before
    if (settings.voiceWarnings) {
      val a = extras.analysis
      val booth = a?.nodes?.firstOrNull { it.alongM - traveled in 150.0..1100.0 && "node:${it.alongM.toLong()}" !in asked }
      if (a != null && booth != null) {
        asked += "node:${booth.alongM.toLong()}"
        val d = Fmt.distanceText(booth.alongM - traveled).replace("km", "chilometri").replace(" m", " metri")
        val what = if (booth.isToll) when (a.boothRole(booth)) {
          "entrata" -> "casello d'ingresso in autostrada"
          "uscita" -> "casello di uscita: prepara il pagamento"
          else -> "barriera del pedaggio"
        } else "confine di Stato"
        say("Tra $d, $what.")
      }
    }

    // a tight exit ramp ahead: ask the driver
    if (settings.askTightRamps) {
      val ramp = extras.criticalities.firstOrNull { c ->
        c.kind == CritKind.RAMP && c.severity != Severity.INFO && c.startM - traveled in 250.0..1600.0 && c.id !in asked
      }
      if (ramp != null) {
        asked += ramp.id
        val d = ramp.startM - traveled
        _nav.update { it.copy(prompt = DriverPrompt.TightRamp(ramp.id, ramp, d)) }
        say("Attenzione: tra ${Fmt.distanceText(d).replace("km", "chilometri").replace(" m", " metri")} lo svincolo ha una curva stretta" +
            (ramp.scene?.radiusM?.takeIf { it < 500 }?.let { ", raggio circa ${it.toInt()} metri" } ?: "") +
            ". Pensi di poterlo affrontare, o preferisci l'uscita successiva?")
        return
      }
    }

    // a toll stretch ahead: is the toll-free way almost as quick?
    if (settings.tollPolicy == TollPolicy.ASK && !AppGraph.trip.value.avoidTolls) {
      val a = extras.analysis
      val toll = a?.tolls?.firstOrNull { it.startM - traveled in 1200.0..6000.0 && "toll:${it.startM.toLong()}" !in asked }
      if (toll != null) {
        asked += "toll:${toll.startM.toLong()}"
        val from = ui.location ?: lastLocation.value
        if (from != null) checkTollFree(from, toll.startM - traveled, a, traveled, ui.progress?.durationRemaining ?: 0.0)
      }
    }

    // time for a break
    if (settings.driveTimeReminder && extras.drivenS > 4 * 3600 && "break:${extras.drivenS / 1800}" !in asked) {
      asked += "break:${extras.drivenS / 1800}"
      val leftS = (4.5 * 3600 - extras.drivenS).coerceAtLeast(0.0)
      val reach = traveled + leftS * speed.coerceAtLeast(15.0)
      val parking = extras.pois.filter { it.poi.cat in setOf("truck_parking", "services", "rest_area") && it.alongM in traveled..reach }.lastOrNull()
      _nav.update { it.copy(prompt = DriverPrompt.Break("break", (extras.drivenS / 60).toInt(), parking)) }
      say("Guidi da ${extras.drivenS / 3600} ore e ${(extras.drivenS % 3600) / 60} minuti. " +
          (parking?.let { "Parcheggio adatto tra ${Fmt.distanceText(it.alongM - traveled).replace("km", "chilometri")}." } ?: "Pianifica la pausa."))
    }
  }

  private fun onLiveUpdate(extras: NavExtras, traveled: Double) {
    val settings = AppGraph.settings.settings.value
    val a = extras.analysis
    val fast = a?.edgeAt(traveled)?.roadClass in setOf("motorway", "trunk")
    // said once, early enough to slow down: 2 km on motorways, 800 m elsewhere
    val next = extras.live.firstOrNull { ev ->
      ev.startM - traveled in 0.0..(if (fast) 2000.0 else 800.0) && "live:${ev.e.id}" !in asked
    }
    if (next != null && settings.voiceWarnings) {
      asked += "live:${next.e.id}"
      val d = Fmt.distanceText(next.startM - traveled).replace("km", "chilometri").replace(" m", " metri")
      val what = LiveRules.label(next.e.kind, a?.edgeAt(next.startM)?.country)
      val delay = if (next.e.delayS >= 120) ", ritardo circa ${next.e.delayS / 60} minuti" else ""
      say(if (next.e.official) "$what tra $d$delay." else "$what segnalato tra $d.")
    }
    // a report of a driver just passed: still there? (one question at a time, 15 seconds)
    if (extras.liveAsk == null && extras.prompt == null && settings.liveReports) {
      val passed = extras.live.firstOrNull { ev ->
        ev.e.id.startsWith("nx:") && !ev.e.mine && traveled - ev.endM in 30.0..400.0 && "ask:${ev.e.id}" !in asked
      }
      if (passed != null) {
        asked += "ask:${passed.e.id}"
        _nav.update { it.copy(liveAsk = passed) }
        viewModelScope.launch {
          delay(15_000)
          _nav.update { if (it.liveAsk?.e?.id == passed.e.id) it.copy(liveAsk = null) else it }
        }
      }
    }
    // a closed road ahead (official information): offer to go around it in time
    if (extras.prompt == null) {
      val closed = extras.live.firstOrNull { ev ->
        ev.e.official && ev.e.kind == LiveKind.CLOSED && ev.startM - traveled in 1500.0..40_000.0 && "closed:${ev.e.id}" !in asked
      }
      if (closed != null) {
        asked += "closed:${closed.e.id}"
        val d = closed.startM - traveled
        _nav.update { it.copy(prompt = DriverPrompt.Closure("closed:${closed.e.id}", closed, d)) }
        say("Attenzione: tra ${Fmt.distanceText(d).replace("km", "chilometri").replace(" m", " metri")} la strada è chiusa. Cerco un'alternativa?")
      }
    }
  }

  private fun checkTollFree(from: UserLocation, distToToll: Double, a: RouteAnalysis, traveled: Double, remainingS: Double) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val waypoints = _plan.value.stops.map { Waypoint(coordinate = it.coordinate, kind = WaypointKind.BREAK) }.takeLast(1)
        if (waypoints.isEmpty()) return@launch
        val alt = AppGraph.routes.routes(from, waypoints, AppGraph.trip.value.copy(avoidTolls = true, alternates = 0)).firstOrNull() ?: return@launch
        val altS = alt.steps.sumOf { it.duration }
        val extra = altS - remainingS
        val max = AppGraph.settings.settings.value.tollMaxExtraMin * 60
        val tollKm = a.tolls.filter { it.endM > traveled }.sumOf { it.length } / 1000.0
        Log.i(TAG, "toll check: ${distToToll.toInt()} m to the toll, ${"%.1f".format(tollKm)} km toll, toll-free +${extra.toInt()} s (max $max s)")
        if (extra <= max && tollKm > 1) {
          val extraKm = (alt.distance - (a.length - traveled)) / 1000.0
          val p = DriverPrompt.TollChoice("toll", (extra / 60).roundToInt().coerceAtLeast(0), extraKm, tollKm, alt, distToToll)
          _nav.update { it.copy(prompt = p) }
          say("Tra ${Fmt.distanceText(distToToll).replace("km", "chilometri")} inizia un tratto a pedaggio. " +
              "Senza pedaggio arrivi solo ${p.extraMin} minuti più tardi. Vuoi evitarlo?")
        }
      } catch (e: Exception) {
        Log.w(TAG, "toll-free check: $e")
      }
    }
  }

  fun dismissPrompt() = _nav.update { it.copy(prompt = null) }

  /**
   * A place chosen while driving becomes a stop on the way to the destination ([via]: only pass
   * there, the route goes on from that point to the destination).
   */
  fun addStopDuringNav(target: GeographicCoordinate, label: String, via: Boolean = false) {
    val p = _plan.value
    val dest = p.stops.lastOrNull()
    _plan.value = p.copy(stops = listOfNotNull(Stop(target, label, via = via), dest))
    val from = navigationUiState.value.location ?: lastLocation.value ?: return
    _nav.update { it.copy(recalculating = true) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val stops = _plan.value.stops
        val waypoints = stops.mapIndexed { i, st ->
          Waypoint(coordinate = st.coordinate, kind = if (st.via && i < stops.size - 1) WaypointKind.VIA else WaypointKind.BREAK)
        }
        val r = AppGraph.routes.routes(from, waypoints, AppGraph.trip.value).firstOrNull() ?: throw IllegalStateException("nessun percorso")
        withContext(Dispatchers.Main) {
          if (_simulating.value) locationProvider.enableSimulationOn(r)
          core.replaceRoute(r)
        }
        say(if (via) "Percorso ricalcolato: passi da quel punto e prosegui verso la destinazione." else "Tappa aggiunta: $label.")
      } catch (e: Exception) {
        Log.w(TAG, "add stop: $e")
        say(if (via) "Da quel punto non trovo un percorso verso la destinazione." else "Non riesco ad aggiungere la tappa.")
      } finally {
        _nav.update { it.copy(recalculating = false) }
      }
    }
  }

  /**
   * Go to a point off the route and then back onto the route computed at the start, to drive the
   * rest of it as planned: the route goes to the point, back to the nearest point of the old route
   * still ahead, and then through points of the old route (with their direction of travel, so a
   * motorway is taken on the right carriageway) to the destination.
   */
  fun detourAndReturn(target: GeographicCoordinate, label: String = "Punto sulla mappa") {
    val a = _nav.value.analysis ?: return addStopDuringNav(target, label, via = true)
    val dest = _plan.value.stops.lastOrNull() ?: return
    val from = navigationUiState.value.location ?: lastLocation.value ?: return
    val traveled = (_nav.value.routeLength - (navigationUiState.value.progress?.distanceRemaining ?: _nav.value.routeLength)).coerceAtLeast(0.0)
    val g = a.route.geometry
    val cum = Geo.cumulative(g)
    // where to get back on the old route: its point nearest to the place, still ahead of us
    var rejoin = -1.0
    var best = Double.MAX_VALUE
    for (i in g.indices) {
      if (cum[i] < traveled + 50) continue
      val d = Geo.dist(g[i], target)
      if (d < best) {
        best = d
        rejoin = cum[i]
      }
    }
    val length = cum.lastOrNull() ?: 0.0
    val headings = HashMap<String, Double>()
    val vias = mutableListOf<GeographicCoordinate>()
    if (rejoin >= 0 && rejoin < length - 500) {
      var at = rejoin
      while (at < length - 2000 && vias.size < 12) {
        val p = a.pointAt(at)
        val h = Geo.bearing(a.pointAt((at - 15).coerceAtLeast(0.0)), a.pointAt(at + 15))
        val c = GeographicCoordinate(Math.round(p.lat * 1e5) / 1e5, Math.round(p.lng * 1e5) / 1e5)
        vias += c
        headings[TripOptions.pointKey(c.lat, c.lng)] = h
        at += if (vias.size == 1) 3000.0 else 15_000.0
      }
    }
    Log.i(TAG, "detour to ${target.lat},${target.lng}, back on the route at ${rejoin.toInt()} m, ${vias.size} points of the old route")
    _plan.value = _plan.value.copy(stops = listOf(Stop(target, label), dest))
    _nav.update { it.copy(recalculating = true) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val opts = AppGraph.trip.value.copy(viaHeadings = headings, alternates = 0)
        val waypoints = listOf(Waypoint(coordinate = target, kind = WaypointKind.BREAK)) +
            vias.map { Waypoint(coordinate = it, kind = WaypointKind.VIA) } +
            Waypoint(coordinate = dest.coordinate, kind = WaypointKind.BREAK)
        val r = AppGraph.routes.routes(from, waypoints, opts).firstOrNull() ?: throw IllegalStateException("nessun percorso")
        AppGraph.trip.value = opts
        withContext(Dispatchers.Main) {
          if (_simulating.value) locationProvider.enableSimulationOn(r)
          core.replaceRoute(r)
        }
        say("Ti porto al punto scelto, poi torni sul percorso di prima.")
      } catch (e: Exception) {
        Log.w(TAG, "detour: $e")
        say("Non trovo un percorso per quel punto.")
      } finally {
        _nav.update { it.copy(recalculating = false) }
      }
    }
  }

  // ------------------------------------------------------------------------------ stops while driving

  /** The driver removes one of the stops or pass-through points still ahead (not the destination). */
  fun removeStopDuringNav(index: Int) {
    val p = _plan.value
    if (p.stops.size <= 1 || index !in 0 until p.stops.size - 1) return
    _plan.value = p.copy(stops = p.stops.filterIndexed { i, _ -> i != index })
    rerouteToStops("Tappa eliminata: ricalcolo il percorso.")
  }

  /** Every stop and point away: straight to the destination. */
  fun removeAllStopsDuringNav() {
    val p = _plan.value
    val dest = p.stops.lastOrNull() ?: return
    if (p.stops.size <= 1) return
    _plan.value = p.copy(stops = listOf(dest))
    rerouteToStops("Tappe eliminate: vado diretto a destinazione.")
  }

  private fun rerouteToStops(message: String) {
    val from = navigationUiState.value.location ?: lastLocation.value ?: return
    // the points of an old "go there and come back" do not count any more
    AppGraph.trip.value = AppGraph.trip.value.copy(viaHeadings = emptyMap())
    _nav.update { it.copy(recalculating = true) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val stops = _plan.value.stops
        val waypoints = stops.mapIndexed { i, st ->
          Waypoint(coordinate = st.coordinate, kind = if (st.via && i < stops.size - 1) WaypointKind.VIA else WaypointKind.BREAK)
        }
        val r = AppGraph.routes.routes(from, waypoints, AppGraph.trip.value.copy(alternates = 0)).firstOrNull()
            ?: throw IllegalStateException("nessun percorso")
        withContext(Dispatchers.Main) {
          if (_simulating.value) locationProvider.enableSimulationOn(r)
          core.replaceRoute(r)
        }
        say(message)
      } catch (e: Exception) {
        Log.w(TAG, "reroute: $e")
        say("Non riesco a ricalcolare il percorso.")
      } finally {
        _nav.update { it.copy(recalculating = false) }
      }
    }
  }

  // ------------------------------------------------------------------------------ simulation controls

  private val simSpeeds = listOf(1, 2, 3, 5, 8, 12, 20)
  private val _simSpeed = MutableStateFlow(3)
  /** How many times faster than real the simulated drive runs. */
  val simSpeed: StateFlow<Int> = _simSpeed.asStateFlow()

  fun simFaster() = setSimSpeed(simSpeeds.firstOrNull { it > _simSpeed.value } ?: simSpeeds.last())

  fun simSlower() = setSimSpeed(simSpeeds.lastOrNull { it < _simSpeed.value } ?: simSpeeds.first())

  private fun setSimSpeed(v: Int) {
    _simSpeed.value = v
    AppGraph.simulator.warpFactor = v.toUInt()
  }

  // ---- simulation jumps. The route of the simulation is kept whole (simBase): after a jump the
  // guidance runs on a new route that starts where the vehicle was put, so "back" and "previous
  // manoeuvre" are measured on the whole route, not on the new one. Several taps in a row add up
  // and only one new route is computed, after the last tap.
  private var simBase: RouteAnalysis? = null
  private var simOffset = 0.0
  private var simTarget: Double? = null
  private var simJob: Job? = null
  @Volatile private var simJumpPending = false
  private var curLenKey = ""
  private var curLen = 0.0

  /** Where the vehicle is on the whole route of the simulation (metres). */
  private fun simPosition(): Double {
    val ui = navigationUiState.value
    val g = ui.routeGeometry ?: return traveledNow()
    val key = geometryKey(g)
    if (key != curLenKey) {
      curLenKey = key
      curLen = Geo.cumulative(g).lastOrNull() ?: 0.0
    }
    val remaining = ui.progress?.distanceRemaining ?: return simOffset
    return simOffset + (curLen - remaining).coerceAtLeast(0.0)
  }

  /** A bit ahead or back on the route (metres, negative = back). */
  fun simJump(deltaM: Double) {
    if (!_simulating.value) return
    queueSimJump((simTarget ?: simPosition()) + deltaM)
  }

  /** To the next (or previous) manoeuvre, a little before it so it is seen coming. */
  fun simManeuver(next: Boolean) {
    if (!_simulating.value) return
    val a = simBase ?: _nav.value.analysis ?: return
    val pos = simTarget?.plus(250) ?: simPosition()
    var acc = 0.0
    val starts = mutableListOf<Double>()
    for (st in a.route.steps) {
      if (acc > 0) starts += acc
      acc += st.distance
    }
    val target = if (next) starts.firstOrNull { it > pos + 200 } else starts.lastOrNull { it < pos - 350 }
    queueSimJump((target ?: if (next) a.length - 100 else 250.0) - 250)
  }

  /** Emulator test of the simulation controls: ahead, back twice in a row, previous manoeuvre. */
  fun simSelfTest() {
    viewModelScope.launch {
      delay(20_000)
      Log.i(TAG, "simtest start at ${simPosition().toInt()}")
      simJump(2000.0)
      delay(12_000)
      Log.i(TAG, "simtest after +2000: ${simPosition().toInt()}")
      simJump(-500.0)
      delay(150)
      simJump(-500.0)
      delay(12_000)
      Log.i(TAG, "simtest after -500 -500: ${simPosition().toInt()}")
      simManeuver(false)
      delay(12_000)
      Log.i(TAG, "simtest after previous manoeuvre: ${simPosition().toInt()}")
    }
  }

  private fun queueSimJump(target: Double) {
    val base = simBase ?: _nav.value.analysis ?: return
    if (simBase == null) {
      simBase = base
      simOffset = 0.0
    }
    val t = target.coerceIn(0.0, (base.length - 60).coerceAtLeast(0.0))
    simTarget = t
    _nav.update { it.copy(recalculating = true) }
    simJob?.cancel()
    simJob = viewModelScope.launch {
      delay(350)
      simJumpTo(t)
    }
  }

  private suspend fun simJumpTo(t: Double) {
    val a = simBase ?: return
    val p = a.pointAt(t)
    val h = Geo.bearing(a.pointAt((t - 15).coerceAtLeast(0.0)), a.pointAt(t + 15))
    val loc = android.location.Location("sim").apply {
      latitude = p.lat
      longitude = p.lng
      accuracy = 5f
      bearing = h.toFloat()
      speed = 15f
      time = System.currentTimeMillis()
    }.toUserLocation()
    // the stops still ahead of the new position, then the destination
    val stops = _plan.value.stops
    val g = a.route.geometry
    fun alongOf(c: GeographicCoordinate): Double {
      var best = Double.MAX_VALUE
      var at = 0.0
      for (i in g.indices) {
        val d = Geo.dist(g[i], c)
        if (d < best) { best = d; at = a.cum[i] }
      }
      return at
    }
    val ahead = stops.dropLast(1).filter { alongOf(it.coordinate) > t + 50 } + listOfNotNull(stops.lastOrNull())
    Log.i(TAG, "simulation: jump to ${t.toInt()} m of ${a.length.toInt()} m")
    try {
      val r = withContext(Dispatchers.IO) {
        val waypoints = ahead.mapIndexed { i, st ->
          Waypoint(coordinate = st.coordinate, kind = if (st.via && i < ahead.size - 1) WaypointKind.VIA else WaypointKind.BREAK)
        }
        AppGraph.routes.routes(loc, waypoints, AppGraph.trip.value.copy(alternates = 0)).firstOrNull()
      } ?: throw IllegalStateException("nessun percorso")
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      simOffset = t
      simTarget = null
      simJumpPending = true
      locationProvider.enableSimulationOn(r)
      core.replaceRoute(r)
    } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "simulation jump: $e")
      simTarget = null
    } finally {
      if (simTarget == null) _nav.update { it.copy(recalculating = false) }
    }
  }

  /** Start a guidance to a single place right away (from the POI list or a search while driving). */
  fun goTo(target: GeographicCoordinate, label: String, simulate: Boolean = false) {
    _plan.value = PlanState(stops = listOf(Stop(target, label)))
    planRoutes { state -> if (state.variants.isNotEmpty()) start(simulate) }
  }

  /** Answer to the tight ramp question: false = take the next exit instead. */
  fun answerRamp(canDoIt: Boolean) {
    val p = _nav.value.prompt as? DriverPrompt.TightRamp ?: return dismissPrompt()
    dismissPrompt()
    if (canDoIt) return
    val ex = Geo.squareAround(p.crit.lat, p.crit.lon, 18.0)
    AppGraph.trip.value = AppGraph.trip.value.let { it.copy(excludePolygons = it.excludePolygons + listOf(ex)) }
    recalcFromHere("Cerco l'uscita successiva")
  }

  fun answerToll(avoid: Boolean) {
    val p = _nav.value.prompt as? DriverPrompt.TollChoice ?: return dismissPrompt()
    dismissPrompt()
    if (!avoid) return
    AppGraph.trip.value = AppGraph.trip.value.copy(avoidTolls = true)
    core.replaceRoute(p.tollFree)
    say("Percorso senza pedaggio.")
  }

  private fun recalcFromHere(message: String) {
    val from = navigationUiState.value.location ?: lastLocation.value ?: return
    _nav.update { it.copy(recalculating = true) }
    say(message)
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val waypoints = _plan.value.stops.map { Waypoint(coordinate = it.coordinate, kind = WaypointKind.BREAK) }.takeLast(1)
        val r = AppGraph.routes.routes(from, waypoints, AppGraph.trip.value).firstOrNull() ?: throw IllegalStateException("nessun percorso")
        withContext(Dispatchers.Main) {
          if (_simulating.value) locationProvider.enableSimulationOn(r)
          core.replaceRoute(r)
        }
      } catch (e: Exception) {
        Log.w(TAG, "recalc: $e")
        say("Non trovo un'alternativa: prosegui con attenzione.")
      } finally {
        _nav.update { it.copy(recalculating = false) }
      }
    }
  }

  private fun reanalyse(geometry: List<GeographicCoordinate>) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Ferrostar gives only the geometry of the new route: find the route we computed for it
        val r = AppGraph.findRoute(geometry) ?: return@launch
        if (_nav.value.analysis?.route?.let { geometryKey(it.geometry) } == geometryKey(r.geometry)) return@launch
        val g = AppGraph.profiles.garage.value
        val v = g.active
        val a = RouteAnalysis.analyse(AppGraph.engine, r, v, g.loadT)
        val m = RouteMatcher(r.geometry)
        val limits = AppGraph.limits.scan(m, v, v.tripWeightT(g.loadT), a = a)
        val crit = AppGraph.criticalities.find(a, m, limits, v, g.loadT, LocalDateTime.now())
        _nav.update { it.copy(analysis = a, limits = limits, criticalities = crit, pois = pois(r), routeLength = a.length) }
        Log.i(TAG, "new route checked: ${crit.size} criticalities")
      } catch (e: Exception) {
        Log.w(TAG, "reanalyse: $e")
      }
    }
  }

  // ---- the voice of the manoeuvres (see Announcer): one at a time, at the right moment
  private val announcer = Announcer { text ->
    if (AppGraph.ferrostar.spokenInstructionObserver?.isMuted != true) {
      runCatching { AppGraph.tts.tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nm-man-${text.hashCode()}") }
    }
  }
  private var announceRoute: Pair<String, uniffi.ferrostar.Route>? = null

  private fun announce(ui: NavigationUiState, geometry: List<GeographicCoordinate>, key: String) {
    val route = announceRoute?.takeIf { it.first == key }?.second
        ?: (_nav.value.analysis?.route?.takeIf { geometryKey(it.geometry) == key } ?: AppGraph.findRoute(geometry))
            ?.also { announceRoute = key to it }
        ?: return
    val remaining = ui.progress?.distanceRemaining ?: return
    val toManeuver = ui.progress?.distanceToNextManeuver ?: return
    if (key != curLenKey) {
      curLenKey = key
      curLen = Geo.cumulative(geometry).lastOrNull() ?: 0.0
    }
    val along = (curLen - remaining).coerceAtLeast(0.0)
    val speed = ui.location?.speed?.value ?: 0.0
    val a = _nav.value.analysis?.takeIf { geometryKey(it.route.geometry) == key }
    val fast = speed > 19.0 || a?.edgeAt(along)?.roadClass in setOf("motorway", "trunk")
    announcer.update(route, key, along, toManeuver, speed, fast, AppGraph.settings.settings.value.voiceLevel)
  }

  private val saidAt = HashMap<String, Long>()

  fun say(text: String) {
    if (!AppGraph.settings.settings.value.voiceWarnings) return
    // muted means muted, and the same warning is not repeated within two minutes
    if (AppGraph.ferrostar.spokenInstructionObserver?.isMuted == true) return
    val now = System.currentTimeMillis()
    if ((saidAt[text] ?: 0L) > now - 120_000) return
    saidAt[text] = now
    runCatching { AppGraph.tts.tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nm-${text.hashCode()}") }
  }

  /** Used by the emulator tests: route to a point and start right away, optionally simulated. */
  fun autoRun(dest: GeographicCoordinate, label: String?, simulate: Boolean, variant: Int? = null) {
    viewModelScope.launch {
      Log.i(TAG, "autoRun verso ${dest.lat},${dest.lng}, attendo la posizione")
      while (lastLocation.value == null) delay(500)
      _plan.value = PlanState(stops = listOf(Stop(dest, label ?: "Prova")))
      planRoutes { state ->
        if (state.variants.isNotEmpty()) {
          if (variant != null) selectVariant(variant)
          start(simulate)
        }
      }
    }
  }

  /**
   * Emulator tests of the live functions, once the guidance runs: a report of "another driver"
   * [aheadM] metres ahead on the route (sent and read back through ntfy), a detour to a point with
   * the return onto the old route, and a check of the German open traffic data.
   */
  fun scheduleLiveTests(report: LiveKind?, aheadM: Double, detour: GeographicCoordinate?, selfTest: Boolean) {
    if (selfTest) viewModelScope.launch(Dispatchers.IO) {
      val ab = runCatching { TrafficFeeds.autobahn(listOf("A8")) }.getOrElse { Log.w(TAG, "livetest autobahn: $it"); emptyList() }
      Log.i(TAG, "livetest autobahn A8: ${ab.size} events, first: ${ab.firstOrNull()?.let { "${it.kind} ${it.title} ${it.line.size} pts" }}")
      // the vector tile reader on a real public tile (OpenFreeMap, no key), as TomTom's are read
      runCatching {
        val http = okhttp3.OkHttpClient()
        val tj = http.newCall(okhttp3.Request.Builder().url("https://tiles.openfreemap.org/planet").build()).execute().use { it.body.string() }
        val tpl = Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(tj)?.groupValues?.get(1) ?: error("no tiles in $tj")
        val (x, y) = app.navmaster.truck.live.Mvt.tileOf(44.06, 12.57, 12)
        val bytes = http.newCall(okhttp3.Request.Builder().url(tpl.replace("{z}", "12").replace("{x}", "$x").replace("{y}", "$y")).build())
            .execute().use { it.body.bytes() }
        val f = app.navmaster.truck.live.Mvt.decode(bytes, 12, x, y)
        val roads = f.filter { it.layer == "transportation" && it.type == 2 }
        val first = roads.firstOrNull()?.lines?.firstOrNull()?.firstOrNull()
        Log.i(TAG, "livetest mvt: ${bytes.size} bytes, ${f.size} features, layers ${f.map { it.layer }.distinct()}, " +
            "roads ${roads.size}, first road point $first, props ${roads.firstOrNull()?.props}")
      }.onFailure { Log.w(TAG, "livetest mvt: $it") }
      Log.i(TAG, "livetest tomtom guard: ${app.navmaster.truck.live.TomTomGuard.summary()}")
      // the national traffic centres, every open feed read whole: how many events of each kind
      for (line in app.navmaster.truck.live.NationalFeeds.selfTest()) Log.i(TAG, "livetest national $line")
    }
    if (report == null && detour == null) return
    viewModelScope.launch {
      while (!navigationUiState.value.isNavigating() || _nav.value.analysis == null) delay(500)
      delay(6_000)
      if (report != null) {
        val a = _nav.value.analysis ?: return@launch
        val at = traveledNow() + aheadM
        val p = a.pointAt(at)
        val h = Geo.bearing(a.pointAt(at - 15), a.pointAt(at + 15))
        withContext(Dispatchers.IO) {
          val id = SharedReports.publish(AppGraph.settings.settings.value.reportsServer, "ci-other", report, p.lat, p.lng, h)
          Log.i(TAG, "livetest report ${report.name} at ${at.toInt()} m: $id")
          delay(2_000)
          runCatching { refreshLive() }.onFailure { Log.w(TAG, "livetest refresh: $it") }
          Log.i(TAG, "livetest on route: ${_nav.value.live.map { "${it.e.kind} ${it.startM.toInt()} m ${it.e.source}" }}")
        }
      }
      if (detour != null) {
        delay(14_000)
        detourAndReturn(detour, "Deviazione di prova")
      }
    }
  }

  /** Plan only (emulator screenshot of the route choice). */
  fun autoPlan(dest: GeographicCoordinate, label: String?) {
    viewModelScope.launch {
      while (lastLocation.value == null) delay(500)
      _plan.value = PlanState(stops = listOf(Stop(dest, label ?: "Prova")))
      planRoutes()
    }
  }

  private fun friendly(e: Exception): String {
    val m = e.message ?: e.toString()
    return when {
      "non installate" in m -> m
      "171" in m || "No suitable edges" in m -> "Nessuna strada percorribile vicino alla partenza o alla destinazione"
      "442" in m || "No path" in m -> "Nessun percorso compatibile con le misure del mezzo, o manca la mappa di un Paese attraversato"
      else -> "Calcolo non riuscito: $m"
    }
  }

  companion object {
    private const val TAG = "NavMasterVM"
  }
}
