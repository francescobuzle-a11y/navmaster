package app.navmaster.truck.nav

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.viewModelScope
import app.navmaster.truck.AppGraph
import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.limits.RouteLimit
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

data class Stop(val coordinate: GeographicCoordinate, val label: String)

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
        val waypoints = p.stops.map { Waypoint(coordinate = it.coordinate, kind = WaypointKind.BREAK) }
        val exclusions = exclusionsFor(p.avoided, _plan.value.current?.analysis)
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
          if (nt != null) {
            val existing = variants.indexOfFirst { same(it.route, nt) }
            val ntVariant = if (existing >= 0) variants[existing].copy(kind = VariantKind.NO_TOLL, title = "Senza pedaggi")
            else build(VariantKind.NO_TOLL, "Senza pedaggi", nt, base.copy(avoidTolls = true))
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
    if (simulate) locationProvider.enableSimulationOn(v.route) else locationProvider.disableSimulation()
    AppGraph.trip.value = v.options.copy(alternates = 0)
    asked.clear()
    lastGeometryKey = geometryKey(v.route.geometry)
    _nav.value = NavExtras(v.analysis, v.limits, v.criticalities, pois(v.route), v.analysis.length)
    if (navigationUiState.value.isNavigating()) core.replaceRoute(v.route) else core.startNavigation(v.route)
  }

  override fun stopNavigation() {
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
          s.poiMaxDetourM)
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
    if (extras.prompt != null) {
      // a question nobody answered in time goes away
      val p = extras.prompt
      if (p is DriverPrompt.TightRamp && p.crit.startM - traveled < 150) dismissPrompt()
      if (p is DriverPrompt.TollChoice && remaining < 0) dismissPrompt()
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

  /** A place chosen while driving becomes a stop on the way to the destination. */
  fun addStopDuringNav(target: GeographicCoordinate, label: String) {
    val p = _plan.value
    val dest = p.stops.lastOrNull()
    _plan.value = p.copy(stops = listOfNotNull(Stop(target, label), dest))
    val from = navigationUiState.value.location ?: lastLocation.value ?: return
    _nav.update { it.copy(recalculating = true) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val waypoints = _plan.value.stops.map { Waypoint(coordinate = it.coordinate, kind = WaypointKind.BREAK) }
        val r = AppGraph.routes.routes(from, waypoints, AppGraph.trip.value).firstOrNull() ?: throw IllegalStateException("nessun percorso")
        withContext(Dispatchers.Main) {
          if (_simulating.value) locationProvider.enableSimulationOn(r)
          core.replaceRoute(r)
        }
        say("Tappa aggiunta: $label.")
      } catch (e: Exception) {
        Log.w(TAG, "add stop: $e")
        say("Non riesco ad aggiungere la tappa.")
      } finally {
        _nav.update { it.copy(recalculating = false) }
      }
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

  fun say(text: String) {
    if (!AppGraph.settings.settings.value.voiceWarnings) return
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
