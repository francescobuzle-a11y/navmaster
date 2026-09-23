package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.vehicle.Garage
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
 * chosen for this trip. Ferrostar's own Valhalla request generator writes the request (so it asks
 * for everything guidance needs: banners, voice, lanes, annotations) and its OSRM parser reads the
 * answer; only the transport differs from the online case — a function call instead of HTTP.
 */
class OfflineRouteProvider(
    private val engine: RoutingEngine,
    private val garage: () -> Garage,
) : CustomRouteProvider {

  private val parser = createOsrmResponseParser(6u)

  override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
      withContext(Dispatchers.IO) {
        val valhalla =
            engine.get() ?: throw IllegalStateException("Mappe offline non installate: scarica prima la regione")
        val g = garage()
        val vehicle = g.active
        val options = vehicle.valhallaOptions(g.loadT).toString()
        val generator =
            createValhallaRequestGenerator("https://offline.navmaster/route", vehicle.type.costing, options)
        val body =
            when (val request = generator.generateRequest(userLocation, waypoints)) {
              is RouteRequest.HttpPost -> request.body.decodeToString()
              is RouteRequest.HttpGet -> throw IllegalStateException("richiesta GET non prevista")
            }
        Log.i(TAG, "route ${vehicle.type.costing} ${vehicle.name} request=${body.take(600)}")
        val started = System.currentTimeMillis()
        val raw = valhalla.routeRaw(body)
        Log.i(TAG, "route computed in ${System.currentTimeMillis() - started} ms, ${raw.length} bytes")
        parser.parseResponse(raw.encodeToByteArray())
      }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
