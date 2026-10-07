package app.navmaster.truck

import android.app.Application
import app.navmaster.truck.data.CatalogStore
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.limits.LimitsIndex
import app.navmaster.truck.live.TomTomGuard
import app.navmaster.truck.location.SmartLocationProvider
import app.navmaster.truck.poi.PoiIndex
import app.navmaster.truck.routing.CriticalityFinder
import app.navmaster.truck.routing.GhRouting
import app.navmaster.truck.routing.OfflineRouteProvider
import app.navmaster.truck.routing.OrsRouting
import app.navmaster.truck.routing.RoutingEngine
import app.navmaster.truck.search.AddressIndex
import app.navmaster.truck.search.RecentStore
import app.navmaster.truck.settings.SettingsStore
import app.navmaster.truck.vehicle.ProfileStore
import app.navmaster.truck.vehicle.TripOptions
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.stepAdvanceDistanceEntryAndSnappedExit
import uniffi.ferrostar.stepAdvanceDistanceFromStep
import uniffi.ferrostar.stepAdvanceOr
import uniffi.ferrostar.DeviationCalculationPolicy
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep

class NavMasterApp : Application() {
  override fun onCreate() {
    super.onCreate()
    AppGraph.init(this)
  }
}

/** The app's few long-lived objects, wired by hand. */
object AppGraph {
  lateinit var app: Application
    private set

  fun init(application: Application) {
    app = application
    // TomTom only inside its free allowance, also for the map's own requests
    TomTomGuard.init(application)
    TomTomGuard.installOnMapLibre(application)
  }

  val settings by lazy { SettingsStore(app) }
  val profiles by lazy { ProfileStore(app) }
  val catalog by lazy { CatalogStore(app) }
  val regions by lazy { RegionManager(app, catalog) }
  val engine by lazy { RoutingEngine(app, regions) }
  /** GraphHopper: the route itself, with the vehicle's measures (see OfflineRouteProvider). */
  val gh by lazy { GhRouting(app, regions) }
  val limits by lazy { LimitsIndex(regions) }
  val poi by lazy { PoiIndex(regions) }
  val addresses by lazy { AddressIndex(regions) }
  val criticalities by lazy { CriticalityFinder(regions) }
  val recents by lazy { RecentStore(app) }

  /** Choices of the trip being driven (tolls, points to avoid): recalculations keep them. */
  val trip = MutableStateFlow(TripOptions())

  private val recent = ArrayDeque<Route>()

  @Synchronized
  private fun remember(routes: List<Route>) {
    for (r in routes) {
      recent.addFirst(r)
      while (recent.size > 16) recent.removeLast()
    }
  }

  /** A route made here (the simulation cut at another point), found again by its geometry. */
  fun keep(route: Route) = remember(listOf(route))

  /** A route computed recently with this geometry (Ferrostar reports only the geometry). */
  @Synchronized
  fun findRoute(geometry: List<GeographicCoordinate>): Route? =
      recent.firstOrNull { it.geometry.size == geometry.size && it.geometry.firstOrNull() == geometry.firstOrNull() && it.geometry.lastOrNull() == geometry.lastOrNull() }

  /** openrouteservice online (with a key built into the app), see OrsRouting. */
  val ors: OrsRouting by lazy { OrsRouting(app) { settings.settings.value.onlineRouting } }

  val routes by lazy { OfflineRouteProvider(engine, gh, { profiles.garage.value }, { trip.value }, ors) { remember(it) } }

  /** The simulated drive (its speed can be changed while it runs). */
  val simulator by lazy { SimulatedLocationProvider(warpFactor = 3u) }

  /** GPS + network, and through tunnels the position along the route (see SmartLocationProvider). */
  val smartLocation by lazy { SmartLocationProvider(app) }

  val locationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = smartLocation,
        simulatedProvider = simulator,
    )
  }

  val ferrostar by lazy {
    FerrostarCore(
        customRouteProvider = routes,
        httpClient = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(15)).build().toOkHttpClientProvider(),
        locationProvider = locationProvider,
        navigationControllerConfig = navigationConfig(),
        foregroundServiceManager = FerrostarForegroundServiceManager(app, DefaultForegroundNotificationBuilder(app)),
    )
  }

  val tts by lazy { AndroidTtsObserver(app) }

  /**
   * A long vehicle needs a bit more room before being declared off route, and steps advance only
   * once the vehicle is really through the junction.
   *
   * The step moves on when the vehicle came near its end and its position ON THE ROUTE has gone past
   * it (not the raw GPS one: in a roundabout, with short steps and a fix a second, the raw position
   * often never got far enough from the step, the arrow stayed nailed to the end of the step and
   * jumped forward only later). A position more than 60 m from the step also moves it on. Fixes up
   * to 50 m of accuracy count (tunnel mouths, built-up areas), not only the very precise ones.
   */
  private fun navigationConfig() =
      NavigationControllerConfig(
          WaypointAdvanceMode.WaypointWithinRange(100.0),
          stepAdvanceOr(listOf(stepAdvanceDistanceEntryAndSnappedExit(30u, 5u, 50u), stepAdvanceDistanceFromStep(60u, 50u, DeviationCalculationPolicy.WHILE_ON_ROUTE))),
          stepAdvanceDistanceToEndOfStep(10u, 32u),
          RouteDeviationTracking.StaticThreshold(15U, 35.0),
          CourseFiltering.SNAP_TO_ROUTE,
      )
}
