package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.routing.gh.GhEngine
import app.navmaster.truck.vehicle.Garage
import app.navmaster.truck.vehicle.TripOptions
import app.navmaster.truck.vehicle.VehicleProfile
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * speed limits - the same as for its own routes. The alternatives are GraphHopper's too; Valhalla's
 * own routes (also with the vehicle's measures) come only when the driver asks for more routes
 * (TripOptions.valhallaOnly), or when there is no GraphHopper graph or it finds nothing.
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
    /** openrouteservice, online (null in tests): the route first, when there is network and a key. */
    private val ors: OrsRouting? = null,
    private val onRoutes: (List<Route>) -> Unit = {},
) : CustomRouteProvider {

  private val parser = createOsrmResponseParser(6u)

  // which engine computed each route handed out (GH = GraphHopper, VH = Valhalla), for the route cards
  private val sources = ArrayDeque<Pair<java.lang.ref.WeakReference<Route>, String>>()

  /** Why the last trip's routes are Valhalla's and not GraphHopper's (null when they are GraphHopper's). */
  @Volatile var whyValhalla: String? = null
    private set

  private fun mark(list: List<Route>, source: String) = synchronized(sources) {
    for (r in list) sources.addLast(java.lang.ref.WeakReference(r) to source)
    while (sources.size > 64) sources.removeFirst()
  }

  /** "GH" or "VH": who computed [r] (null for a route not computed here). */
  fun sourceOf(r: Route): String? = synchronized(sources) { sources.lastOrNull { it.first.get() === r }?.second }

  // why GraphHopper's routes were not used, besides a missing graph (see GhRouting.problem)
  @Volatile private var ghNote: String? = null

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
        // 1. GraphHopper's routes with the vehicle's measures (the alternatives too), guided by
        //    Valhalla along each of them. Only Valhalla when the driver asked for more routes.
        // 0. online: openrouteservice's routes with the vehicle's measures (guided by Valhalla as
        //    GraphHopper's); without network, key or quota the tablet computes them as below
        if (!options.valhallaOnly && ors != null && ors.available()) {
          val online = runCatching { orsRoutes(body, vehicle, g.loadT, options, c.lat, c.lng) }
              .onFailure { Log.w(TAG, "openrouteservice route failed: $it", it) }
              .getOrNull()?.takeIf { it.isNotEmpty() }
          if (online != null) {
            whyValhalla = null
            mark(online, "ORS")
            return@withContext online.also(onRoutes)
          }
        }
        if (!options.valhallaOnly) {
          ghNote = null
          val ghRoutes = runCatching { ghRoutes(body, vehicle, g.loadT, options, c.lat, c.lng) }
              .onFailure {
                Log.w(TAG, "GraphHopper route failed: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it)
                ghNote = "errore di GraphHopper (${it.message?.take(80)})"
              }
              .getOrNull()?.takeIf { it.isNotEmpty() }
          if (ghRoutes != null) {
            whyValhalla = null
            mark(ghRoutes, "GH")
            return@withContext ghRoutes.also(onRoutes)
          }
          whyValhalla = gh.problem ?: ghNote ?: "GraphHopper non ha trovato un percorso"
          Log.i(TAG, "Valhalla instead of GraphHopper: $whyValhalla")
        }
        // 2. Valhalla's own routes: the "more routes" asked by the driver, or the routes when
        //    GraphHopper has no graph here or found nothing
        val raw = SpeedCap.apply(engine.use(c.lat, c.lng) { it.routeRaw(body) }, vehicle.topSpeedKmh)
        Log.i(TAG, "route computed in ${System.currentTimeMillis() - started} ms, ${raw.length} bytes (Valhalla)")
        parser.parseResponse(raw.encodeToByteArray()).also { mark(it, "VH") }.also(onRoutes)
      }

  /**
   * GraphHopper's routes for [vehicle] through the locations of the Valhalla request [body] (with
   * its alternatives when the request asks for them), each with Valhalla's guidance along it;
   * null when there is no GraphHopper graph or no route.
   */
  private fun ghRoutes(body: String, vehicle: VehicleProfile, loadT: Double, options: TripOptions, lat: Double, lon: Double): List<Route>? {
    val root = Json.parseToJsonElement(body).jsonObject
    val locs = root["locations"]?.jsonArray ?: return null
    val points = locs.map { val o = it.jsonObject; doubleArrayOf(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double) }
    val headings = locs.map { it.jsonObject["heading"]?.jsonPrimitive?.doubleOrNull ?: Double.NaN }
    val kinds = locs.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "break" }
    val spec = vehicle.ghSpec(loadT, options)
    val found = gh.routes(points, headings, spec, 1 + options.alternates.coerceAtLeast(0)) ?: return null
    found.firstOrNull()?.note?.let { Log.i(TAG, "GraphHopper $it") }
    val ok = found.filter { it.ok() }
    if (ok.isEmpty()) {
      Log.w(TAG, "GraphHopper: no route (${found.firstOrNull()?.error}) for $spec")
      ghNote = "GraphHopper non ha trovato un percorso con queste misure (${found.firstOrNull()?.error?.take(80) ?: "?"})"
      return null
    }
    val base = root - "locations" - "alternates" - "exclude_polygons"
    val out = mutableListOf<Route>()
    for ((n, r) in ok.withIndex()) {
      Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper route %d/%d: %.1f km, %d min, %d points in %d ms for %s",
          n + 1, ok.size, r.distanceM / 1000, r.timeMs / 60000, r.lat.size, r.computeMs, spec))
      // the guidance from GraphHopper's own graph; Valhalla only for a graph without guidance data
      val guided = ghGuide(r, kinds, points, spec) ?: guide(r, kinds, base, vehicle, lat, lon)
      if (guided != null) out += guided
      else if (n == 0) {
        // the best route could not be guided: Valhalla's routes instead
        ghNote = "il percorso di GraphHopper non combacia con la mappa di guida"
        return null
      }
    }
    return out
  }

  /**
   * GraphHopper's guidance along its route [r] (GhGuide: manoeuvres, voice, lanes, signs, limits
   * from the graph itself), read by Ferrostar as Valhalla's was; the roads of the route are kept
   * for its analysis. Null when the graph has no guidance data.
   */
  private fun ghGuide(r: GhEngine.Result, kinds: List<String>, points: List<DoubleArray>, spec: app.navmaster.truck.routing.gh.TruckSpec): Route? {
    val started = System.currentTimeMillis()
    // the stops (start and destination included) and the points asked for them
    val stops = java.util.TreeMap<Int, DoubleArray>()
    stops[0] = points.first()
    stops[r.lat.size - 1] = points.last()
    for ((k, idx) in r.waypointIndex.withIndex()) {
      if (k == 0 || k == r.waypointIndex.size - 1) continue
      if (kinds.getOrNull(k) != "via") points.getOrNull(k)?.let { stops[idx] = it }
    }
    val out = runCatching { gh.guide(r, spec, stops.keys.toIntArray(), stops.values.toList()) }
        .onFailure { Log.w(TAG, "GraphHopper guidance failed: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it) }
        .getOrNull() ?: return null
    val route = runCatching { parser.parseResponse(out.osrm.encodeToByteArray()).firstOrNull() }
        .onFailure { Log.w(TAG, "GraphHopper guidance not readable: $it") }.getOrNull() ?: return null
    gh.remember(route, out.attributes)
    Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper route guided by GraphHopper in %d ms: %.1f km, %d steps",
        System.currentTimeMillis() - started, route.distance / 1000, route.steps.size))
    return route
  }

  /** openrouteservice's routes (online), each with Valhalla's guidance along it; null when none. */
  private fun orsRoutes(body: String, vehicle: VehicleProfile, loadT: Double, options: TripOptions, lat: Double, lon: Double): List<Route>? {
    val root = Json.parseToJsonElement(body).jsonObject
    val locs = root["locations"]?.jsonArray ?: return null
    val points = locs.map { val o = it.jsonObject; doubleArrayOf(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double) }
    val kinds = locs.map { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "break" }
    val spec = vehicle.ghSpec(loadT, options)
    val found = ors?.routes(points, spec, options.alternates.coerceAtLeast(0)) ?: return null
    val base = root - "locations" - "alternates" - "exclude_polygons"
    val out = mutableListOf<Route>()
    for ((n, r) in found.withIndex()) {
      Log.i(TAG, String.format(java.util.Locale.US, "openrouteservice route %d/%d: %.1f km, %d min, %d points for %s",
          n + 1, found.size, r.distanceM / 1000, r.timeMs / 60000, r.lat.size, spec))
      val guided = guide(r, kinds, base, vehicle, lat, lon)
      if (guided != null) out += guided
      else if (n == 0) return null // the best one could not be guided: computed on the tablet instead
    }
    return out
  }

  /**
   * The request without the vehicle's measures, for the map matching only: the path is already
   * chosen (with the measures), and a wrong limit in Valhalla's map must not break it.
   */
  private fun relaxed(base: Map<String, JsonElement>): Map<String, JsonElement> {
    val co = base["costing_options"] as? JsonObject ?: return base
    val drop = setOf("height", "width", "length", "weight", "axle_load", "axle_count", "hazmat")
    val out = JsonObject(co.mapValues { (_, v) -> if (v is JsonObject) JsonObject(v.filterKeys { it !in drop }) else v })
    return base + ("costing_options" to out)
  }

  /** Valhalla's guidance (manoeuvres, voice, lanes, limits) along GraphHopper's route [r]. */
  private fun guide(r: GhEngine.Result, kinds: List<String>, base: Map<String, JsonElement>, vehicle: VehicleProfile, lat: Double, lon: Double): Route? {
    // the points where a leg ends (the stops); the other pass-through points do not split the route
    val breaks = HashSet<Int>()
    breaks += 0
    breaks += r.lat.size - 1
    for ((k, idx) in r.waypointIndex.withIndex()) if (kinds.getOrNull(k) != "via") breaks += idx
    val waypoints = JsonArray(breaks.sorted().map { i ->
      JsonObject(mapOf("location" to JsonArray(listOf(JsonPrimitive(r.lon[i]), JsonPrimitive(r.lat[i]))), "name" to JsonPrimitive("")))
    })
    val started = System.currentTimeMillis()
    // 1. map matching along the path (map_snap: GraphHopper stores the road shapes slightly
    //    simplified, the exact walk along Valhalla's edges never matched). Valhalla breaks a trace
    //    where two points are more than 2 km apart (meili breakage_distance, which a request cannot
    //    change) and GraphHopper has no point along km of straight motorway: points are added
    //    every 300 m, on the road itself (the shape is the road's)
    val traced = runCatching { traceAlong(r, breaks, base, vehicle, waypoints, lat, lon) }
        .onFailure { Log.w(TAG, "Valhalla trace failed: $it") }.getOrNull()
    if (traced != null) {
      Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper route guided by Valhalla (map_snap) in %d ms: %.1f km, %d steps",
          System.currentTimeMillis() - started, traced.distance / 1000, traced.steps.size))
      return traced
    }
    // 2. the two maps differ somewhere on the path (a road changed between the two extracts):
    //    Valhalla's own route through points of GraphHopper's path, with the direction of travel
    val through = runCatching { routeThrough(r, breaks, base, vehicle, lat, lon) }
        .onFailure { Log.w(TAG, "Valhalla route through GraphHopper's path failed: $it") }.getOrNull()
    if (through != null) {
      Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper route guided by Valhalla (through points) in %d ms: %.1f km, %d steps",
          System.currentTimeMillis() - started, through.distance / 1000, through.steps.size))
    }
    return through
  }

  private fun traceAlong(r: GhEngine.Result, breaks: Set<Int>, base: Map<String, JsonElement>, vehicle: VehicleProfile,
                         waypoints: JsonArray, lat: Double, lon: Double): Route? {
    val pts = ArrayList<JsonElement>(r.lat.size * 2)
    fun point(la: Double, lo: Double, type: String) =
        JsonObject(mapOf("lat" to JsonPrimitive(la), "lon" to JsonPrimitive(lo), "type" to JsonPrimitive(type)))
    for (i in r.lat.indices) {
      if (i > 0) {
        val d = metres(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i])
        val n = kotlin.math.ceil(d / DENSE_M).toInt()
        for (k in 1 until n) {
          val f = k.toDouble() / n
          pts += point(r.lat[i - 1] + (r.lat[i] - r.lat[i - 1]) * f, r.lon[i - 1] + (r.lon[i] - r.lon[i - 1]) * f, "through")
        }
      }
      pts += point(r.lat[i], r.lon[i], if (i in breaks) "break" else "through")
    }
    val trace = JsonObject(relaxed(base) + mapOf(
        "shape" to JsonArray(pts),
        "shape_match" to JsonPrimitive("map_snap"),
        "trace_options" to JsonObject(mapOf(
            "search_radius" to JsonPrimitive(30), "gps_accuracy" to JsonPrimitive(5),
            "interpolation_distance" to JsonPrimitive(0),
        )),
    )).toString()
    val raw = engine.use(lat, lon) { it.traceRouteRaw(trace) }
    val res = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val matchings = res["matchings"]?.jsonArray
    if (matchings.isNullOrEmpty()) {
      Log.w(TAG, "Valhalla trace (map_snap, ${pts.size} points): ${raw.take(300)}")
      return null
    }
    // Valhalla's map-matching answer, as an OSRM route answer (the format Ferrostar reads)
    val routes = JsonArray(matchings.map { m ->
      val mo = m.jsonObject
      val legs = mo["legs"]?.jsonArray?.map { l -> JsonObject(l.jsonObject + ("via_waypoints" to JsonArray(emptyList()))) }
      JsonObject(mo - "confidence" + ("legs" to JsonArray(legs ?: emptyList())))
    })
    val dist = routes.firstOrNull()?.jsonObject?.get("distance")?.jsonPrimitive?.doubleOrNull ?: 0.0
    if (routes.size != 1 || kotlin.math.abs(dist - r.distanceM) > r.distanceM * 0.03 + 200) {
      Log.w(TAG, String.format(java.util.Locale.US, "Valhalla trace (map_snap, %d points): %d pieces, %.1f km against %.1f km of GraphHopper",
          pts.size, routes.size, routes.sumOf { it.jsonObject["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0 } / 1000, r.distanceM / 1000))
      return null
    }
    val answer = JsonObject(mapOf("code" to JsonPrimitive("Ok"), "routes" to routes, "waypoints" to waypoints)).toString()
    val capped = SpeedCap.apply(answer, vehicle.topSpeedKmh)
    return parser.parseResponse(capped.encodeToByteArray()).firstOrNull()
  }

  /**
   * Valhalla's route through points taken along GraphHopper's path (at most [MAX_THROUGH], evenly,
   * each with the direction of travel there), keeping the stops: the same roads, unless the maps
   * differ there. Accepted only when it is as long as GraphHopper's (5%).
   */
  private fun routeThrough(r: GhEngine.Result, breaks: Set<Int>, base: Map<String, JsonElement>, vehicle: VehicleProfile, lat: Double, lon: Double): Route? {
    val n = r.lat.size
    if (n < 2) return null
    val cum = DoubleArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + metres(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i])
    val total = cum[n - 1]
    val stops = breaks.sorted()
    val count = (total / 15_000).toInt().coerceAtLeast(1).coerceAtMost((MAX_THROUGH - stops.size).coerceAtLeast(0))
    val wanted = (1..count).map { total * it / (count + 1) }
    val picked = HashSet<Int>()
    var j = 0
    for (w in wanted) {
      while (j < n - 1 && cum[j] < w) j++
      // not next to a stop (the stop itself is there)
      if (stops.any { kotlin.math.abs(cum[it] - cum[j]) < 1500 }) continue
      picked += j
    }
    val locs = (stops + picked).sorted().map { i ->
      if (i in breaks) {
        JsonObject(mapOf("lat" to JsonPrimitive(r.lat[i]), "lon" to JsonPrimitive(r.lon[i]), "type" to JsonPrimitive("break")))
      } else {
        // the direction of travel on the path here (over the next 20-40 m)
        var k = i + 1
        while (k < n - 1 && cum[k] - cum[i] < 20) k++
        val h = bearing(r.lat[i], r.lon[i], r.lat[k], r.lon[k])
        JsonObject(mapOf(
            "lat" to JsonPrimitive(r.lat[i]), "lon" to JsonPrimitive(r.lon[i]), "type" to JsonPrimitive("through"),
            "heading" to JsonPrimitive(h.toInt()), "heading_tolerance" to JsonPrimitive(45), "radius" to JsonPrimitive(0),
        ))
      }
    }
    val req = JsonObject(base + mapOf("locations" to JsonArray(locs))).toString()
    val raw = SpeedCap.apply(engine.use(lat, lon) { it.routeRaw(req) }, vehicle.topSpeedKmh)
    val route = parser.parseResponse(raw.encodeToByteArray()).firstOrNull() ?: return null
    if (kotlin.math.abs(route.distance - r.distanceM) > r.distanceM * 0.05 + 500) {
      Log.w(TAG, String.format(java.util.Locale.US, "Valhalla through %d points of GraphHopper: %.1f km against %.1f km",
          locs.size, route.distance / 1000, r.distanceM / 1000))
      return null
    }
    return route
  }

  private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dp / 2).let { it * it } + kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dl / 2).let { it * it }
    return 2 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
  }

  private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = kotlin.math.sin(dl) * kotlin.math.cos(p2)
    val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dl)
    return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
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

    /** At most this far between two points of the shape given to Valhalla's map matching. */
    private const val DENSE_M = 300.0

    /** Valhalla's limit of locations for a route is 20: stops plus points along the path. */
    private const val MAX_THROUGH = 20
  }
}
