package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.vehicle.Garage
import app.navmaster.truck.vehicle.TripOptions
import app.navmaster.truck.vehicle.VehicleProfile
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteRequest
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.createOsrmResponseParser
import uniffi.ferrostar.createValhallaRequestGenerator

/**
 * Routes are computed on the tablet, with the measures and the weight of the vehicle chosen for this
 * trip and the trip's choices (tolls, points to avoid).
 *
 * The route itself is GraphHopper's (when the country has its GraphHopper graph): GraphHopper finds
 * the path with the vehicle's height, width, length, weight, axle load and dangerous goods
 * (GhRouting, gh/NmWeightingFactory). Valhalla then follows exactly that path on its own map
 * (trace_route) and gives what guidance needs on it: manoeuvres, Italian voice, banners, lanes,
 * speed limits - the same as for its own routes. Alternatives are Valhalla's (also with the
 * vehicle's measures). Without a GraphHopper graph, or if it finds nothing, the route is Valhalla's.
 *
 * Ferrostar's Valhalla request generator writes the request and its OSRM parser reads the answer;
 * only the transport differs from the online case - a function call instead of HTTP. Ferrostar also
 * calls this when it recalculates after a wrong turn, so the recalculated route keeps the same choices.
 */
class OfflineRouteProvider(
    private val engine: RoutingEngine,
    private val gh: GhRouting,
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
        var body =
            when (val request = generator.generateRequest(userLocation, waypoints)) {
              is RouteRequest.HttpPost -> request.body.decodeToString()
              is RouteRequest.HttpGet -> throw IllegalStateException("richiesta GET non prevista")
            }
        if (options.viaHeadings.isNotEmpty()) body = withHeadings(body, options.viaHeadings)
        Log.i(TAG, "route ${vehicle.costing} ${vehicle.name} tolls=${!options.avoidTolls} alt=${options.alternates} " +
            "avoid=${options.excludePolygons.size} request=${body.take(400)}")
        val started = System.currentTimeMillis()
        val c = userLocation.coordinates
        // 1. GraphHopper's route with the vehicle's measures, guided by Valhalla along it
        val ghRoutes = runCatching { ghRoute(body, vehicle, g.loadT, options, c.lat, c.lng) }
            .onFailure { Log.w(TAG, "GraphHopper route failed: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it) }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (ghRoutes != null && options.alternates <= 0) return@withContext ghRoutes.also(onRoutes)
        // 2. Valhalla's own routes: the alternatives, or the route when GraphHopper has none
        val raw = runCatching { SpeedCap.apply(engine.use(c.lat, c.lng) { it.routeRaw(body) }, vehicle.topSpeedKmh) }
        if (ghRoutes != null && raw.isFailure) return@withContext ghRoutes.also(onRoutes)
        Log.i(TAG, "route computed in ${System.currentTimeMillis() - started} ms, ${raw.getOrThrow().length} bytes")
        val valhalla = parser.parseResponse(raw.getOrThrow().encodeToByteArray())
        val out = if (ghRoutes == null) valhalla else {
          val main = ghRoutes.first()
          // a Valhalla route the same as GraphHopper's (within 1%) is not offered twice
          ghRoutes + valhalla.filter { kotlin.math.abs(it.distance - main.distance) > main.distance * 0.01 }
        }
        out.also(onRoutes)
      }

  /**
   * GraphHopper's route for [vehicle] through the locations of the Valhalla request [body], with
   * Valhalla's guidance along it; null when there is no GraphHopper graph or no route.
   */
  private fun ghRoute(body: String, vehicle: VehicleProfile, loadT: Double, options: TripOptions, lat: Double, lon: Double): List<Route>? {
    val root = Json.parseToJsonElement(body).jsonObject
    val locs = root["locations"]?.jsonArray ?: return null
    val points = locs.map { val o = it.jsonObject; doubleArrayOf(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double) }
    val headings = locs.map { it.jsonObject["heading"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN }
    val kinds = locs.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "break" }
    val spec = vehicle.ghSpec(loadT, options)
    val r = gh.route(points, headings, spec) ?: return null
    if (!r.ok()) {
      Log.w(TAG, "GraphHopper: no route (${r.error}) for $spec")
      return null
    }
    Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper route %.1f km, %d min, %d points in %d ms for %s",
        r.distanceM / 1000, r.timeMs / 60000, r.lat.size, r.computeMs, spec))
    // the points where a leg ends (the stops); the other pass-through points do not split the route
    val breaks = HashSet<Int>()
    breaks += 0
    breaks += r.lat.size - 1
    for ((k, idx) in r.waypointIndex.withIndex()) if (kinds.getOrNull(k) != "via") breaks += idx
    val shape = JsonArray(r.lat.indices.map { i ->
      JsonObject(mapOf(
          "lat" to JsonPrimitive(r.lat[i]), "lon" to JsonPrimitive(r.lon[i]),
          "type" to JsonPrimitive(if (i in breaks) "break" else "through"),
      ))
    })
    val waypoints = JsonArray(breaks.sorted().map { i ->
      JsonObject(mapOf("location" to JsonArray(listOf(JsonPrimitive(r.lon[i]), JsonPrimitive(r.lat[i]))), "name" to JsonPrimitive("")))
    })
    val base = root - "locations" - "alternates" - "exclude_polygons"
    for (match in listOf("edge_walk", "map_snap")) {
      val started = System.currentTimeMillis()
      val trace = JsonObject(base + mapOf(
          "shape" to shape,
          "shape_match" to JsonPrimitive(match),
          "trace_options" to JsonObject(mapOf(
              "search_radius" to JsonPrimitive(30), "gps_accuracy" to JsonPrimitive(5),
              "breakage_distance" to JsonPrimitive(5000), "interpolation_distance" to JsonPrimitive(0),
          )),
      )).toString()
      val raw = runCatching { engine.use(lat, lon) { it.traceRouteRaw(trace) } }
          .onFailure { Log.w(TAG, "Valhalla trace ($match) failed: $it") }.getOrNull() ?: continue
      val res = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
      val matchings = res["matchings"]?.jsonArray
      if (matchings.isNullOrEmpty()) {
        Log.w(TAG, "Valhalla trace ($match): ${raw.take(300)}")
        continue
      }
      // Valhalla's map-matching answer, as an OSRM route answer (the format Ferrostar reads)
      val routes = JsonArray(matchings.map { m ->
        val mo = m.jsonObject
        val legs = mo["legs"]?.jsonArray?.map { l -> JsonObject(l.jsonObject + ("via_waypoints" to JsonArray(emptyList()))) }
        JsonObject(mo - "confidence" + ("legs" to JsonArray(legs ?: emptyList())))
      })
      val dist = routes.firstOrNull()?.jsonObject?.get("distance")?.jsonPrimitive?.doubleOrNull ?: 0.0
      if (routes.size != 1 || kotlin.math.abs(dist - r.distanceM) > r.distanceM * 0.03 + 200) {
        Log.w(TAG, String.format(java.util.Locale.US, "Valhalla trace (%s): %d pieces, %.1f km against %.1f km of GraphHopper",
            match, routes.size, dist / 1000, r.distanceM / 1000))
        continue
      }
      val answer = JsonObject(mapOf("code" to JsonPrimitive("Ok"), "routes" to routes, "waypoints" to waypoints)).toString()
      val capped = SpeedCap.apply(answer, vehicle.topSpeedKmh)
      val parsed = parser.parseResponse(capped.encodeToByteArray())
      Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper route guided by Valhalla (%s) in %d ms: %.1f km, %d steps",
          match, System.currentTimeMillis() - started, dist / 1000, parsed.firstOrNull()?.steps?.size ?: 0))
      return parsed
    }
    return null
  }

  /** The direction of travel on the pass-through points that have one (see TripOptions.viaHeadings). */
  private fun withHeadings(body: String, headings: Map<String, Double>): String {
    return try {
        val root = Json.parseToJsonElement(body).jsonObject
        val locs = root["locations"]?.jsonArray ?: return body
        var n = 0
        val out = JsonArray(locs.mapIndexed { i, l ->
          val o = l.jsonObject
          val lat = o["lat"]?.jsonPrimitive?.doubleOrNull
          val lon = o["lon"]?.jsonPrimitive?.doubleOrNull
          val h = if (i == 0 || lat == null || lon == null) null else headings[TripOptions.pointKey(lat, lon)]
          if (h == null) o else {
            n++
            JsonObject(o + mapOf("heading" to JsonPrimitive(((h % 360) + 360) % 360), "heading_tolerance" to JsonPrimitive(60)))
          }
        })
        if (n > 0) Log.i(TAG, "direction of travel on $n pass-through points")
        JsonObject(root + ("locations" to out)).toString()
      } catch (e: Exception) {
        Log.w(TAG, "headings: $e")
        body
      }
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
