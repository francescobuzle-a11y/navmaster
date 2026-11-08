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
import app.navmaster.truck.nav.MapGaps
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

/** The state of the start screen: what is being prepared, how far (0–1), whether it is over. */
data class Startup(val label: String, val fraction: Float, val done: Boolean)

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
    // the engine's graphs in the internal storage (see NmEngine.internalRoot)
    NmEngine.internalRoot = java.io.File(application.filesDir, "nm")
    // TomTom and HERE only inside the free allowance of the driver's own accounts (also the map's own requests)
    TomTomGuard.init(application)
    HereGuard.init(application)
    TomTomGuard.installOnMapLibre(application)
    // the junctions of the routes driven that the map lacks signs or lanes for (Impostazioni › Guida)
    MapGaps.init(application.filesDir, application.getExternalFilesDir(null))
    // at once, behind the start screen (ui.StartupScreen): the graphs of the active vehicle's profile
    // warmed (the first route cold cost twice: see NmEngine.warmUp); then, at low priority, the
    // border crossings of the downloaded countries
    Thread {
      runCatching {
        val v = profiles.garage.value.active
        // the budget from the memory free now (it counts the file cache, which the system can take
        // back): three quarters, so the map and the other apps keep some; on the tablet (3.8 GB,
        // 1.5 GB free) 6/10 left out the edges of Italy by 62 MB, and the first route cost 9.2 s instead of 4.6 s
        val mem = android.app.ActivityManager.MemoryInfo().also { (app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        val budget = (mem.availMem * 3 / 4).coerceIn(256L shl 20, 1536L shl 20)
        // the country where the vehicle is (last known position), warmed whole first
        val here = runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1500) { smartLocation.lastLocation() } } }.getOrNull()
        val first = here?.let { regions.regionAt(it.latitude, it.longitude)?.id }
        android.util.Log.i("NavMasterRoute", "riscaldamento: ${budget shr 20} MB su ${mem.availMem shr 20} MB liberi, prima ${first ?: "nessun Paese noto"}")
        var shown = -1; var label: String? = null
        engine.warmUp(if (v.isHgv || v.isBus) "driving-hgv" else "driving-car", budget, first) { r, fraction ->
          // one update per percent and per country (the warm-up reports every megabyte: hundreds of frames for nothing)
          val pct = (fraction * 100).toInt()
          if (r.label != label || pct != shown) { shown = pct; label = r.label; startup.value = Startup("Preparo ${r.label}…", fraction.coerceIn(0f, 1f), false) }
        }
      }.onFailure { android.util.Log.w("NavMasterRoute", "riscaldamento: $it") }
      startup.value = Startup("Pronto", 1f, true)
      Thread.currentThread().priority = Thread.MIN_PRIORITY
      runCatching { engine.prepareBorders() }
    }.apply { isDaemon = true; name = "valichi" }.start()
  }

  /** What the start screen shows while the engine's graphs warm up (null before the start, done at the end). */
  val startup = MutableStateFlow<Startup?>(null)

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
