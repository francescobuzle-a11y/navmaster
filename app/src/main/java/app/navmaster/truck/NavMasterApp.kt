package app.navmaster.truck

import app.navmaster.truck.location.NmLocationProvider
import app.navmaster.truck.location.NmSimulator
import android.app.Application
import app.navmaster.truck.data.CatalogStore
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.limits.LimitsIndex
import app.navmaster.truck.live.ApiKeys
import app.navmaster.truck.live.HereGuard
import app.navmaster.truck.live.TomTomGuard
import app.navmaster.truck.location.SmartLocationProvider
import app.navmaster.truck.poi.PoiIndex
import app.navmaster.truck.data.RoadsIndex
import app.navmaster.truck.routing.CriticalityFinder
import app.navmaster.truck.routing.NmEngine
import app.navmaster.truck.routing.NmRouting
import app.navmaster.truck.routing.NmServer
import app.navmaster.truck.routing.OfflineRouteProvider
import app.navmaster.truck.search.AddressIndex
import app.navmaster.truck.search.PlaceIndex
import app.navmaster.truck.search.RecentStore
import app.navmaster.truck.settings.SettingsStore
import app.navmaster.truck.vehicle.ProfileStore
import app.navmaster.truck.vehicle.TripOptions
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
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
    // TomTom and HERE only inside this tablet's part of the fleet's free allowance (also the map's own requests)
    TomTomGuard.init(application)
    HereGuard.init(application)
    TomTomGuard.installOnMapLibre(application)
    // the border crossings of the downloaded countries, in the background a little after the start
    Thread {
      runCatching { Thread.sleep(30_000); engine.prepareBorders() }
    }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true; name = "valichi" }.start()
  }

  val settings by lazy { SettingsStore(app) }
  val profiles by lazy { ProfileStore(app) }
  val catalog by lazy { CatalogStore(app) }
  val regions by lazy { RegionManager(app, catalog) }
  /** navmaster-engine on the tablet: the graphs of the downloaded countries (see OfflineRouteProvider). */
  val engine by lazy { NmEngine(app, regions, catalog) }
  /** The NavMaster server (engine-server on a graph of Europe), when one is set in the settings. */
  val server by lazy { NmServer(app, { settings.settings.value.engineServer }, { ApiKeys.ors(settings.settings.value) }) }
  /** The routes computed by the engine with openrouteservice's logic (on the tablet, or by the server across borders). */
  val nm by lazy { NmRouting(engine, server) { lat, lon -> catalog.countryAt(lat, lon)?.id } }
  val limits by lazy { LimitsIndex(regions) }
  val poi by lazy { PoiIndex(regions) }
  val addresses by lazy { AddressIndex(regions) }
  val places by lazy { PlaceIndex(regions) }
  val criticalities by lazy { CriticalityFinder(regions) }

  /** The roads around a point, from the engine's graph. */
  val roads by lazy { RoadsIndex { engine } }
  val recents by lazy { RecentStore(app) }

  /** Choices of the trip being driven (tolls, points to avoid): recalculations keep them. */
  val trip = MutableStateFlow(TripOptions())

  private val recent = ArrayDeque<Route>()

  @Synchronized
  private fun remember(routes: List<Route>) {
    for (r in routes) {
      recent.addFirst(r)
      while (recent.size > 8) recent.removeLast()
    }
  }

  /** A route made here (the simulation cut at another point), found again by its geometry. */
  fun keep(route: Route) = remember(listOf(route))

  /** A route computed recently with this geometry (Ferrostar reports only the geometry). */
  @Synchronized
  fun findRoute(geometry: List<GeographicCoordinate>): Route? =
      recent.firstOrNull { it.geometry.size == geometry.size && it.geometry.firstOrNull() == geometry.firstOrNull() && it.geometry.lastOrNull() == geometry.lastOrNull() }

  val routes by lazy { OfflineRouteProvider(nm, { profiles.garage.value }, { trip.value }) { remember(it) } }

  /** The simulated drive, at a lorry's speeds (its pace can be changed while it runs). */
  val simulator by lazy { NmSimulator() }

  /** GPS + network, and through tunnels the position along the route (see SmartLocationProvider). */
  val smartLocation by lazy { SmartLocationProvider(app) }

  val locationProvider by lazy { NmLocationProvider(smartLocation, simulator) }

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
          RouteDeviationTracking.StaticThreshold(15U, 30.0),
          CourseFiltering.SNAP_TO_ROUTE,
      )
}
