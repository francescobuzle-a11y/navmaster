package app.navmaster.truck.nav

import android.util.Log
import androidx.lifecycle.viewModelScope
import app.navmaster.truck.AppGraph
import app.navmaster.truck.limits.RouteLimit
import com.stadiamaps.ferrostar.core.DefaultNavigationViewModel
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.location.toUserLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

/** A computed route waiting for the driver to look at it and start. */
data class PlannedRoute(
    val route: Route,
    val limits: List<RouteLimit>,
    val vehicleName: String,
    val weightT: Double,
    val durationS: Double,
)

data class SceneState(
    val destination: GeographicCoordinate? = null,
    val destinationLabel: String? = null,
    val planning: Boolean = false,
    val planned: PlannedRoute? = null,
    val error: String? = null,
    /** limits of the route being driven, with their distance from the start of the route */
    val activeLimits: List<RouteLimit> = emptyList(),
    val activeRouteLength: Double = 0.0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class NavViewModel :
    DefaultNavigationViewModel(AppGraph.ferrostar, valhallaExtendedOSRMAnnotationPublisher()) {

  private val core = AppGraph.ferrostar
  private val locationProvider = AppGraph.locationProvider
  private val hasPermission = MutableStateFlow(false)
  private val lastLocation = MutableStateFlow<UserLocation?>(null)
  val location: StateFlow<UserLocation?> = lastLocation.asStateFlow()

  private val _scene = MutableStateFlow(SceneState())
  val scene: StateFlow<SceneState> = _scene.asStateFlow()

  private val _simulating = MutableStateFlow(false)
  val simulating: StateFlow<Boolean> = _simulating.asStateFlow()

  /** Before navigation starts, the map still shows where the vehicle is. */
  override val navigationUiState: StateFlow<NavigationUiState> =
      combine(super.navigationUiState, lastLocation) { ui, loc ->
            if (ui.isNavigating()) ui else ui.copy(location = loc)
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NavigationUiState.empty())

  init {
    viewModelScope.launch {
      hasPermission
          .flatMapLatest { ok ->
            if (ok) locationProvider.locationUpdates(1000L).map { it.toUserLocation() } else emptyFlow()
          }
          .collect {
            if (lastLocation.value == null) Log.i(TAG, "prima posizione: ${it.coordinates.lat},${it.coordinates.lng}")
            lastLocation.value = it
          }
    }
  }

  fun setLocationPermission(granted: Boolean) {
    Log.i(TAG, "permesso posizione: $granted")
    hasPermission.value = granted
  }

  fun selectDestination(coordinate: GeographicCoordinate, label: String? = null) {
    _scene.value = _scene.value.copy(destination = coordinate, destinationLabel = label, planned = null, error = null)
    planRoute()
  }

  fun clearDestination() {
    _scene.value = SceneState()
  }

  fun planRoute(then: ((PlannedRoute) -> Unit)? = null) {
    val dest = _scene.value.destination ?: return
    val from = lastLocation.value
    if (from == null) {
      _scene.value = _scene.value.copy(error = "Posizione GPS non ancora disponibile")
      return
    }
    _scene.value = _scene.value.copy(planning = true, error = null)
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val garage = AppGraph.profiles.garage.value
        val routes = core.getRoutes(from, listOf(Waypoint(coordinate = dest, kind = WaypointKind.BREAK)))
        val route = routes.first()
        Log.i(TAG, "route computed: ${route.distance.toInt()} m, ${route.steps.size} steps")
        val weight = garage.active.tripWeightT(garage.loadT)
        val limits = AppGraph.limits.scan(route.geometry, garage.active, weight)
        val planned =
            PlannedRoute(
                route = route,
                limits = limits,
                vehicleName = garage.active.name,
                weightT = weight,
                durationS = route.steps.sumOf { it.duration },
            )
        _scene.value = _scene.value.copy(planning = false, planned = planned)
        then?.invoke(planned)
      } catch (e: Exception) {
        Log.e(TAG, "route failed", e)
        _scene.value = _scene.value.copy(planning = false, error = friendly(e))
      }
    }
  }

  fun start(simulate: Boolean) {
    val planned = _scene.value.planned ?: return
    _simulating.value = simulate
    if (simulate) locationProvider.enableSimulationOn(planned.route) else locationProvider.disableSimulation()
    _scene.value =
        _scene.value.copy(
            planned = null,
            activeLimits = planned.limits,
            activeRouteLength = planned.route.distance,
        )
    if (navigationUiState.value.isNavigating()) core.replaceRoute(planned.route) else core.startNavigation(planned.route)
  }

  /** Used by the emulator tests: route to a point and start right away, optionally simulated. */
  fun autoRun(dest: GeographicCoordinate, label: String?, simulate: Boolean) {
    viewModelScope.launch {
      Log.i(TAG, "autoRun verso ${dest.lat},${dest.lng}, attendo la posizione")
      while (lastLocation.value == null) kotlinx.coroutines.delay(500)
      _scene.value = _scene.value.copy(destination = dest, destinationLabel = label)
      planRoute { viewModelScope.launch(Dispatchers.Main) { start(simulate) } }
    }
  }

  /** After a reroute Ferrostar replaces the route: limits follow the new geometry. */
  fun refreshLimitsFor(geometry: List<GeographicCoordinate>) {
    viewModelScope.launch(Dispatchers.IO) {
      val g = AppGraph.profiles.garage.value
      val weight = g.active.tripWeightT(g.loadT)
      val limits = AppGraph.limits.scan(geometry, g.active, weight)
      val length = geometry.zipWithNext { a, b -> app.navmaster.truck.limits.LimitsIndex.dist(a, b) }.sum()
      _scene.value = _scene.value.copy(activeLimits = limits, activeRouteLength = length)
    }
  }

  override fun stopNavigation() {
    locationProvider.disableSimulation()
    _simulating.value = false
    core.stopNavigation()
    _scene.value = SceneState()
  }

  private fun friendly(e: Exception): String {
    val m = e.message ?: e.toString()
    return when {
      "non installate" in m -> m
      "171" in m || "No suitable edges" in m -> "Nessuna strada percorribile vicino alla partenza o alla destinazione"
      "442" in m || "No path" in m -> "Nessun percorso compatibile con le misure del mezzo"
      else -> "Calcolo non riuscito: $m"
    }
  }

  companion object {
    private const val TAG = "NavMasterVM"
  }
}
