package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.vehicle.Garage
import app.navmaster.truck.vehicle.TripOptions
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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
    while (attributes.size > 24) attributes.remove(attributes.keys.first())
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

  /** One computation at a time: the notes of a route (why it failed) are its own. */
  private val busy = kotlinx.coroutines.sync.Mutex()

  suspend fun routes(userLocation: UserLocation, waypoints: List<Waypoint>, options: TripOptions): List<Route> =
      busy.withLock { routesNow(userLocation, waypoints, options) }

  private suspend fun routesNow(userLocation: UserLocation, waypoints: List<Waypoint>, options: TripOptions): List<Route> =
      withContext(Dispatchers.IO) {
        val g = garage()
        val vehicle = g.active
        val stops = stops(userLocation, waypoints)
        val spec = vehicle.routeSpec(g.loadT, options)
        Log.i(TAG, "route ${vehicle.name} tolls=${!options.avoidTolls} avoid=${options.excludePolygons.size} points=${stops.points.size} for $spec")
        note = null
        val started = System.currentTimeMillis()
        val found = nm.routes(stops.points, spec, stops.kinds.any { it == "via" })
        if (found == null) {
          val why = nm.note ?: "il motore non ha trovato un percorso"
          Log.w(TAG, "no route: $why")
          throw IllegalStateException("Nessun percorso: $why.")
        }
        val answer = nm.lastText ?: throw IllegalStateException("Nessun percorso: risposta del motore assente.")
        val countryOf = { lat: Double, lon: Double -> app.navmaster.truck.AppGraph.catalog.countryAt(lat, lon)?.iso?.uppercase() }
        val out = mutableListOf<Route>()
        for (i in found.indices) {
          val guided = runCatching { OrsGuide.build(answer, i, spec.hgv, countryOf) }
              .onFailure { Log.w(TAG, "guidance not written: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it) }.getOrNull() ?: break
          val route = runCatching { parser.parseResponse(guided.osrm.encodeToByteArray()).firstOrNull() }
              .onFailure { Log.w(TAG, "guidance not readable: $it") }.getOrNull() ?: break
          remember(route, guided.attributes)
          Log.i(TAG, String.format(java.util.Locale.US, "route %d/%d guided in %d ms: %.1f km, %d steps, %d roads",
              i + 1, found.size, System.currentTimeMillis() - started, route.distance / 1000, route.steps.size, guided.roads))
          out += route
        }
        if (out.isEmpty()) throw IllegalStateException("Nessun percorso: la guida del percorso non è stata calcolata.")
        mark(out, "NM")
        out.also(onRoutes)
      }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
