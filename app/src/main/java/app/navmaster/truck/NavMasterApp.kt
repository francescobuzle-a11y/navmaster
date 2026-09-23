package app.navmaster.truck

import android.app.Application
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.limits.LimitsIndex
import app.navmaster.truck.routing.OfflineRouteProvider
import app.navmaster.truck.routing.RoutingEngine
import app.navmaster.truck.vehicle.ProfileStore
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.AndroidLocationProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import java.time.Duration
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
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
  }

  val profiles by lazy { ProfileStore(app) }
  val regions by lazy { RegionManager(app) }
  val engine by lazy { RoutingEngine(app, regions) }
  val limits by lazy { LimitsIndex(regions) }

  val locationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = AndroidLocationProvider(app),
        simulatedProvider = SimulatedLocationProvider(warpFactor = 3u),
    )
  }

  val ferrostar by lazy {
    FerrostarCore(
        customRouteProvider = OfflineRouteProvider(engine) { profiles.garage.value },
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
