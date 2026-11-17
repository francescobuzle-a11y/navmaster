package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.vehicle.Garage
import app.navmaster.truck.vehicle.TripOptions
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import it.navmaster.engine.api.RequestContext
import it.navmaster.engine.routing.CancelToken
import it.navmaster.engine.routing.SearchCancelledException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ferrostar.Route
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.createOsrmResponseParser

/**
 * The routes of a trip, with the measures and the weight of the vehicle chosen for it and the
 * trip's choices (kind of route, tolls, points to avoid): computed on the tablet by navmaster-engine
 * with openrouteservice's own logic, nothing added. The guidance (manoeuvres, Italian voice,
 * banners, lanes, speed limits) comes from the engine's instructions and the facts about the roads
 * of the route (OrsGuide), read by Ferrostar's OSRM parser. Ferrostar also calls this when it
 * recalculates after a wrong turn, so the recalculated route keeps the same choices.
 */
class OfflineRouteProvider(
    private val nm: NmRouting,
    private val garage: () -> Garage,
    private val trip: () -> TripOptions,
    private val onRoutes: (List<Route>) -> Unit = {},
) : CustomRouteProvider {

  private val parser = createOsrmResponseParser(6u)

  // the routes handed out (for the route cards) and the roads of each one (for RouteAnalysis)
  private val sources = ArrayDeque<Pair<java.lang.ref.WeakReference<Route>, String>>()
  private val attributes = LinkedHashMap<String, String>()

  /** What the driver should know about the last trip's routes (shown under them); else null. */
  @Volatile var note: String? = null
    private set

  private fun mark(list: List<Route>, source: String) = synchronized(sources) {
    for (r in list) sources.addLast(java.lang.ref.WeakReference(r) to source)
    while (sources.size > 64) sources.removeFirst()
  }

  /** "NM" (navmaster-engine) for a route computed here, null for one that was not. */
  fun sourceOf(r: Route): String? = synchronized(sources) { sources.lastOrNull { it.first.get() === r }?.second }

  private fun key(r: Route): String {
    val g = r.geometry
    val f = g.firstOrNull(); val l = g.lastOrNull()
    return "${g.size}:${f?.lat}:${f?.lng}:${l?.lat}:${l?.lng}:${r.distance}"
  }

  /** The roads of [route] (OrsGuide's attributes) kept when it was computed, null when it was not. */
  fun attributesFor(route: Route): String? = synchronized(attributes) { attributes[key(route)] }

  private fun remember(route: Route, json: String?) = synchronized(attributes) {
    if (json == null) return@synchronized
    attributes[key(route)] = json
    while (attributes.size > 6) attributes.remove(attributes.keys.first())
  }

  override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
      routes(userLocation, waypoints, trip())

  /** The points of the trip: where the vehicle is, then the waypoints. */
  private class Stops(val points: List<DoubleArray>, val kinds: List<String>)

  private fun stops(userLocation: UserLocation, waypoints: List<Waypoint>): Stops {
    val c = userLocation.coordinates
    val points = ArrayList<DoubleArray>()
    val kinds = ArrayList<String>()
    points += doubleArrayOf(c.lat, c.lng)
    kinds += "break"
    for (w in waypoints) {
      val p = w.coordinate
      points += doubleArrayOf(p.lat, p.lng)
      kinds += if (w.kind == WaypointKind.VIA) "via" else "break"
    }
    return Stops(points, kinds)
  }

  /**
   * [block] on a thread of its own with the engine's cancel token: the coroutine cancelled (a new
   * route asked, the planning left), the token stops the search at once. Over [EngineCall.deadlineMs]
   * the engine gives up ([EngineTimeoutException]). The routes no longer wait for each other: the
   * engine itself keeps at most two searches at a time, the route on screen first.
   */
  private suspend fun <T> cancellable(call: EngineCall, block: (CancelToken) -> T): T = coroutineScope {
    val token = CancelToken(call.deadlineMs?.let { System.nanoTime() + it * 1_000_000 } ?: Long.MAX_VALUE)
    val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { token.cancel() } }
    try {
      withContext(Dispatchers.IO) { block(token) }
    } catch (e: SearchCancelledException) {
      // cancelled from here: the coroutine's own cancellation; else the engine's reason, a failure
      // of this computation (not a cancellation: the caller goes on with the next one)
      ensureActive()
      if (e.reason == SearchCancelledException.Reason.DEADLINE) throw EngineTimeoutException(e)
      throw IllegalStateException("Nessun percorso: calcolo interrotto (${e.reason}).", e)
    } finally {
      watch.cancel()
    }
  }

  /** The search took longer than its call allowed (a choice computed next to the route on screen). */
  class EngineTimeoutException(cause: Throwable) : IllegalStateException("Nessun percorso: calcolo troppo lungo, lasciato perdere.", cause)

  /** How a route with [options] is asked: the choices next to the main route in the background, with a time limit. */
  private fun callFor(options: TripOptions): EngineCall =
      if (options.quick) EngineCall(RequestContext.BACKGROUND, 15_000) else EngineCall()

  /** The headings of the points of the trip: at the start (moving, or the last one while it moved there) and at the points passed through. */
  private fun headings(userLocation: UserLocation, stops: Stops, options: TripOptions): List<Double?> {
    // the route starts on the vehicle's carriageway and goes on its way (ORS bearings), not back the way it came
    val c0 = userLocation.coordinates
    val startHeading = userLocation.courseOverGround?.degrees?.toDouble()?.takeIf { (userLocation.speed?.value ?: 0.0) >= 2.0 }
        ?: runCatching { app.navmaster.truck.AppGraph.smartLocation.courseNear(c0.lat, c0.lng) }.getOrNull()
    return stops.points.mapIndexed { i, p ->
      if (i == 0) startHeading else options.viaHeadings[TripOptions.pointKey(p[0], p[1])]
    }
  }

  /** The routes of [answer] guided (OrsGuide on the text of that answer) and read by Ferrostar's parser. */
  private fun guide(answer: NmAnswer, hgv: Boolean, kinds: List<String>, started: Long): List<Pair<Route, RouteResult>> {
    val text = answer.text ?: return emptyList()
    val found = answer.results ?: return emptyList()
    val countryOf = { lat: Double, lon: Double -> app.navmaster.truck.AppGraph.catalog.countryAt(lat, lon)?.iso?.uppercase() }
    val out = mutableListOf<Pair<Route, RouteResult>>()
    for ((i, r) in found.withIndex()) {
      val guided = runCatching { OrsGuide.build(text, r.feature, hgv, countryOf, kinds) }
          .onFailure { Log.w(TAG, "guidance not written: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it) }.getOrNull() ?: break
      val tParse = System.currentTimeMillis()
      // Ferrostar's parser, one route at a time (the routes are computed side by side now)
      val route = runCatching { synchronized(parser) { parser.parseResponse(guided.osrm.encodeToByteArray()).firstOrNull() } }
          .onFailure { Log.w(TAG, "guidance not readable: $it") }.getOrNull() ?: break
      val tRemember = System.currentTimeMillis()
      remember(route, guided.attributes)
      Log.i(TAG, String.format(java.util.Locale.US, "route %d/%d guided in %d ms: %.1f km, %d steps, %d roads (guida: %s; Ferrostar %d ms, analisi %d ms)",
          i + 1, found.size, System.currentTimeMillis() - started, route.distance / 1000, route.steps.size, guided.roads, guided.timings,
          tRemember - tParse, System.currentTimeMillis() - tRemember))
      out += route to r
    }
    return out
  }

  suspend fun routes(userLocation: UserLocation, waypoints: List<Waypoint>, options: TripOptions): List<Route> =
      cancellable(callFor(options)) { cancel ->
        val g = garage()
        val vehicle = g.active
        val stops = stops(userLocation, waypoints)
        val spec = vehicle.routeSpec(g.loadT, options)
        Log.i(TAG, "route ${vehicle.name} tolls=${!options.avoidTolls} avoid=${options.excludePolygons.size} points=${stops.points.size} for $spec")
        val started = System.currentTimeMillis()
        val alternates = if (options.moreRoutes) options.alternates.coerceIn(1, 2) else options.alternates.coerceIn(0, 2)
        val answer = nm.routes(stops.points, spec, stops.kinds, alternates, headings(userLocation, stops, options), call = callFor(options), cancel = cancel)
        if (answer.results == null) {
          val why = answer.note ?: "il motore non ha trovato un percorso"
          Log.w(TAG, "no route: $why")
          throw IllegalStateException("Nessun percorso: $why.")
        }
        if (answer.text == null) throw IllegalStateException("Nessun percorso: risposta del motore assente.")
        val out = guide(answer, spec.hgv, stops.kinds, started).map { it.first }
        if (out.isEmpty()) throw IllegalStateException("Nessun percorso: la guida del percorso non è stata calcolata.")
        mark(out, "NM")
        out.also(onRoutes)
      }

  /**
   * The engine's variants of the trip ([ask]: those computed by themselves right after the best
   * route, or the driver's "Altri percorsi"), without the best one, each with what the engine says
   * of it; in the background (a route on screen goes first) and stopped as soon as the coroutine is.
   * Empty when there are none (not an error).
   */
  suspend fun variants(userLocation: UserLocation, waypoints: List<Waypoint>, options: TripOptions, ask: VariantAsk): List<Pair<Route, VariantInfo?>> {
    val call = EngineCall(RequestContext.BACKGROUND)
    return cancellable(call) { cancel ->
      val g = garage()
      val vehicle = g.active
      val stops = stops(userLocation, waypoints)
      val spec = vehicle.routeSpec(g.loadT, options)
      Log.i(TAG, "variants ${vehicle.name} count=${ask.count} points=${stops.points.size} for $spec")
      val started = System.currentTimeMillis()
      val answer = nm.routes(stops.points, spec, stops.kinds, 0, headings(userLocation, stops, options), ask, call, cancel)
      if (answer.results == null) {
        Log.w(TAG, "no variants: ${answer.note}")
        return@cancellable emptyList()
      }
      val out = guide(answer, spec.hgv, stops.kinds, started)
      mark(out.map { it.first }, "NM")
      onRoutes(out.map { it.first })
      out.map { (route, r) -> route to r.variant }
    }
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
