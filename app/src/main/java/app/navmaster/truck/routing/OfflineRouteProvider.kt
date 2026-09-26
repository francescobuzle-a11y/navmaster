package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.vehicle.Garage
import app.navmaster.truck.vehicle.TripOptions
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.createOsrmResponseParser
import uniffi.ferrostar.createValhallaRequestGenerator

/**
 * Routes are computed on the tablet by Valhalla, with the measures and the weight of the vehicle
 * chosen for this trip and the trip's choices (tolls, points to avoid). Ferrostar's Valhalla request
 * generator writes the request (so it asks for everything guidance needs: banners, voice, lanes,
 * annotations) and its OSRM parser reads the answer; only the transport differs from the online
 * case - a function call instead of HTTP. Ferrostar also calls this when it recalculates after a
 * wrong turn, so the recalculated route keeps the same choices.
 */
class OfflineRouteProvider(
    private val engine: RoutingEngine,
    private val garage: () -> Garage,
    private val trip: () -> TripOptions,
    private val onRoutes: (List<Route>) -> Unit = {},
) : CustomRouteProvider {

  private val parser = createOsrmResponseParser(6u)

  override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
      routes(userLocation, waypoints, trip())

  suspend fun routes(userLocation: UserLocation, waypoints: List<Waypoint>, options: TripOptions): List<Route> =
      withContext(Dispatchers.IO) {
        val g = garage()
        val vehicle = g.active
        val opts = vehicle.valhallaOptions(g.loadT, options).toString()
        val generator = createValhallaRequestGenerator("https://offline.navmaster/route", vehicle.costing, opts)
        val body =
            when (val request = generator.generateRequest(userLocation, waypoints)) {
              is RouteRequest.HttpPost -> request.body.decodeToString()
              is RouteRequest.HttpGet -> throw IllegalStateException("richiesta GET non prevista")
            }
        Log.i(TAG, "route ${vehicle.costing} ${vehicle.name} tolls=${!options.avoidTolls} alt=${options.alternates} " +
            "avoid=${options.excludePolygons.size} request=${body.take(400)}")
        val started = System.currentTimeMillis()
        val c = userLocation.coordinates
        val raw = SpeedCap.apply(engine.use(c.lat, c.lng) { it.routeRaw(body) }, vehicle.topSpeedKmh)
        Log.i(TAG, "route computed in ${System.currentTimeMillis() - started} ms, ${raw.length} bytes")
        parser.parseResponse(raw.encodeToByteArray()).also(onRoutes)
      }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
