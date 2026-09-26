package app.navmaster.truck

import android.app.Application
import app.navmaster.truck.data.CatalogStore
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.limits.LimitsIndex
import app.navmaster.truck.location.SmartLocationProvider
import app.navmaster.truck.poi.PoiIndex
import app.navmaster.truck.routing.CriticalityFinder
import app.navmaster.truck.routing.OfflineRouteProvider
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
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
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
    app.navmaster.truck.live.TomTomGuard.init(application)
    app.navmaster.truck.live.TomTomGuard.installOnMapLibre()
  }

  val settings by lazy { SettingsStore(app) }
  val profiles by lazy { ProfileStore(app) }
  val catalog by lazy { CatalogStore(app) }
  val regions by lazy { RegionManager(app, catalog) }
  val engine by lazy { RoutingEngine(app, regions) }
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

  /** A route computed recently with this geometry (Ferrostar reports only the geometry). */
  @Synchronized
  fun findRoute(geometry: List<GeographicCoordinate>): Route? =
      recent.firstOrNull { it.geometry.size == geometry.size && it.geometry.firstOrNull() == geometry.firstOrNull() && it.geometry.lastOrNull() == geometry.lastOrNull() }

  val routes by lazy { OfflineRouteProvider(engine, { profiles.garage.value }, { trip.value }) { remember(it) } }

  val locationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = SmartLocationProvider(app),
        simulatedProvider = SimulatedLocationProvider(warpFactor = 3u),
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
   */
  private fun navigationConfig() =
      NavigationControllerConfig(
          WaypointAdvanceMode.WaypointWithinRange(100.0),
          stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
          stepAdvanceDistanceToEndOfStep(10u, 32u),
          RouteDeviationTracking.StaticThreshold(15U, 35.0),
          CourseFiltering.SNAP_TO_ROUTE,
      )
}
