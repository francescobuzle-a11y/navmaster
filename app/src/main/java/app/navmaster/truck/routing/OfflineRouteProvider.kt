package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.routing.gh.GhEngine
import app.navmaster.truck.routing.gh.TruckSpec
import app.navmaster.truck.vehicle.Garage
import app.navmaster.truck.vehicle.TripOptions
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ferrostar.Route
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind
import uniffi.ferrostar.createOsrmResponseParser

/**
 * The routes of a trip, with the measures and the weight of the vehicle chosen for it and the trip's
 * choices (kind of route, tolls, points to avoid). Only openrouteservice and GraphHopper:
 *
 *  0. online (network and the key ORS_KEY): openrouteservice's routes (driving-hgv with the
 *     vehicle's measures, buses as "bus", campers and cars driving-car). GraphHopper follows each
 *     of them on the tablet's graph and checks it against the vehicle's measures and loads (a route
 *     of driving-car knows nothing of a camper's 3.2 m): a route that passes a limit is not offered;
 *  1. otherwise, or when no route of openrouteservice is left: GraphHopper's own routes on the
 *     tablet, computed with openrouteservice's rules (NmImport, OrsWeighting).
 *
 * The guidance (manoeuvres, Italian voice, banners, lanes, speed limits) always comes from
 * GraphHopper's graph (GhGuide), as an OSRM answer read by Ferrostar's parser. Ferrostar also calls
 * this when it recalculates after a wrong turn, so the recalculated route keeps the same choices.
 */
class OfflineRouteProvider(
    private val gh: GhRouting,
    private val garage: () -> Garage,
    private val trip: () -> TripOptions,
    /** openrouteservice, online (null in tests): the route first, when there is network and a key. */
    private val ors: OrsRouting? = null,
    private val onRoutes: (List<Route>) -> Unit = {},
) : CustomRouteProvider {

  private val parser = createOsrmResponseParser(6u)

  // which engine computed each route handed out (ORS = openrouteservice, GH = GraphHopper), for the route cards
  private val sources = ArrayDeque<Pair<java.lang.ref.WeakReference<Route>, String>>()

  /**
   * What the driver should know about the last trip's routes (shown under them): why they were
   * computed on the tablet and not by openrouteservice when it refused one for a limit; else null.
   */
  @Volatile var note: String? = null
    private set

  private fun mark(list: List<Route>, source: String) = synchronized(sources) {
    for (r in list) sources.addLast(java.lang.ref.WeakReference(r) to source)
    while (sources.size > 64) sources.removeFirst()
  }

  /** "ORS" or "GH": who computed [r] (null for a route not computed here). */
  fun sourceOf(r: Route): String? = synchronized(sources) { sources.lastOrNull { it.first.get() === r }?.second }

  // why GraphHopper's routes were not found, besides a missing graph (see GhRouting.problem)
  @Volatile private var ghNote: String? = null

  override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
      routes(userLocation, waypoints, trip())

  /** The points of the trip: where the vehicle is (with its direction of travel), then the waypoints. */
  private class Stops(val points: List<DoubleArray>, val headings: List<Double>, val kinds: List<String>)

  private fun stops(userLocation: UserLocation, waypoints: List<Waypoint>, options: TripOptions): Stops {
    val c = userLocation.coordinates
    val points = ArrayList<DoubleArray>()
    val headings = ArrayList<Double>()
    val kinds = ArrayList<String>()
    points += doubleArrayOf(c.lat, c.lng)
    headings += userLocation.courseOverGround?.degrees?.toDouble() ?: Double.NaN
    kinds += "break"
    var n = 0
    for (w in waypoints) {
      val p = w.coordinate
      points += doubleArrayOf(p.lat, p.lng)
      val h = options.viaHeadings[TripOptions.pointKey(p.lat, p.lng)]
      if (h != null) n++
      headings += h?.let { ((it % 360) + 360) % 360 } ?: Double.NaN
      kinds += if (w.kind == WaypointKind.VIA) "via" else "break"
    }
    if (n > 0) Log.i(TAG, "direction of travel on $n pass-through points")
    return Stops(points, headings, kinds)
  }

  suspend fun routes(userLocation: UserLocation, waypoints: List<Waypoint>, options: TripOptions): List<Route> =
      withContext(Dispatchers.IO) {
        val g = garage()
        val vehicle = g.active
        val stops = stops(userLocation, waypoints, options)
        val spec = vehicle.ghSpec(g.loadT, options)
        Log.i(TAG, "route ${vehicle.name} tolls=${!options.avoidTolls} alt=${options.alternates} more=${options.moreRoutes} " +
            "avoid=${options.excludePolygons.size} points=${stops.points.size} for $spec")
        note = null
        // 0. online: openrouteservice's routes, followed and checked by GraphHopper; not for the
        //    "more routes" the driver asks for (GraphHopper's alternatives)
        if (!options.moreRoutes && ors != null && ors.available()) {
          val online = runCatching { orsRoutes(stops, spec, options) }
              .onFailure { Log.w(TAG, "openrouteservice route failed: $it", it) }
              .getOrNull()?.takeIf { it.isNotEmpty() }
          if (online != null) {
            mark(online, "ORS")
            return@withContext online.also(onRoutes)
          }
        }
        // 1. GraphHopper on the tablet, with openrouteservice's rules
        ghNote = null
        val alternates = if (options.moreRoutes) options.alternates.coerceAtLeast(2) else options.alternates.coerceAtLeast(0)
        val local = runCatching { ghRoutes(stops, spec, alternates) }
            .onFailure {
              Log.w(TAG, "GraphHopper route failed: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it)
              ghNote = "errore di GraphHopper (${it.message?.take(80)})"
            }
            .getOrNull()?.takeIf { it.isNotEmpty() }
        if (local != null) {
          mark(local, "GH")
          return@withContext local.also(onRoutes)
        }
        throw IllegalStateException("Nessun percorso: ${gh.problem ?: ghNote ?: "GraphHopper non ha trovato un percorso"}")
      }

  /** GraphHopper's routes through the stops (with alternatives when asked), each guided from its graph; null when none. */
  private fun ghRoutes(s: Stops, spec: TruckSpec, alternates: Int): List<Route>? {
    val found = gh.routes(s.points, s.headings, spec, 1 + alternates) ?: return null
    found.firstOrNull()?.note?.let { Log.i(TAG, "GraphHopper $it") }
    val ok = found.filter { it.ok() }
    if (ok.isEmpty()) {
      Log.w(TAG, "GraphHopper: no route (${found.firstOrNull()?.error}) for $spec")
      ghNote = "nessuna strada percorribile con queste misure e questi divieti (${found.firstOrNull()?.error?.take(80) ?: "?"})"
      return null
    }
    val out = mutableListOf<Route>()
    for ((n, r) in ok.withIndex()) {
      Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper route %d/%d: %.1f km, %d min, %d points in %d ms",
          n + 1, ok.size, r.distanceM / 1000, r.timeMs / 60000, r.lat.size, r.computeMs))
      val guided = ghGuide(r, s, spec)
      if (guided != null) out += guided
      else if (n == 0) {
        ghNote = "la guida del percorso non è stata calcolata (grafo da aggiornare?)"
        return null
      }
    }
    return out
  }

  /**
   * GraphHopper's guidance along its route [r] (GhGuide: manoeuvres, voice, lanes, signs, limits
   * from the graph itself), read by Ferrostar's OSRM parser; the roads of the route are kept for
   * its analysis. Null when the graph has no guidance data.
   */
  private fun ghGuide(r: GhEngine.Result, s: Stops, spec: TruckSpec): Route? {
    val started = System.currentTimeMillis()
    // the stops (start and destination included) and the points asked for them
    val stops = java.util.TreeMap<Int, DoubleArray>()
    stops[0] = s.points.first()
    stops[r.lat.size - 1] = s.points.last()
    for ((k, idx) in r.waypointIndex.withIndex()) {
      if (k == 0 || k == r.waypointIndex.size - 1) continue
      if (s.kinds.getOrNull(k) != "via") s.points.getOrNull(k)?.let { stops[idx] = it }
    }
    val out = runCatching { gh.guide(r, spec, stops.keys.toIntArray(), stops.values.toList()) }
        .onFailure { Log.w(TAG, "GraphHopper guidance failed: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it) }
        .getOrNull() ?: return null
    val route = runCatching { parser.parseResponse(out.osrm.encodeToByteArray()).firstOrNull() }
        .onFailure { Log.w(TAG, "GraphHopper guidance not readable: $it") }.getOrNull() ?: return null
    gh.remember(route, out.attributes)
    Log.i(TAG, String.format(java.util.Locale.US, "route guided by GraphHopper in %d ms: %.1f km, %d steps",
        System.currentTimeMillis() - started, route.distance / 1000, route.steps.size))
    return route
  }

  /**
   * openrouteservice's routes (online): each one driven by GraphHopper along its line
   * (GhEngine.follow), checked against the vehicle's measures and loads on GraphHopper's graph and
   * guided from it. Null when none is left (the routes are then computed on the tablet).
   */
  private fun orsRoutes(s: Stops, spec: TruckSpec, options: TripOptions): List<Route>? {
    val found = ors?.routes(s.points, spec, options.alternates.coerceAtLeast(0)) ?: return null
    val out = mutableListOf<Route>()
    for ((n, r) in found.withIndex()) {
      Log.i(TAG, String.format(java.util.Locale.US, "openrouteservice route %d/%d: %.1f km, %d min, %d points",
          n + 1, found.size, r.distanceM / 1000, r.timeMs / 60000, r.lat.size))
      val f = runCatching { gh.follow(r, spec) }
          .onFailure { Log.w(TAG, "GraphHopper could not follow openrouteservice: $it") }.getOrNull()
      if (f == null || !f.ok() || f.error != null) {
        Log.w(TAG, "GraphHopper does not follow openrouteservice's route ${n + 1}: ${f?.error} (${f?.note})")
        if (n == 0) return null // the best one cannot be guided: computed on the tablet instead
        continue
      }
      Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper follows openrouteservice: %.1f km against %.1f km, %s, %d ms",
          f.distanceM / 1000, r.distanceM / 1000, f.note, f.computeMs))
      // the vehicle's measures and loads on every road of the route (the same check as GraphHopper's own routes)
      val closed = gh.closedOn(f, spec)
      if (closed != null) {
        Log.w(TAG, "openrouteservice's route ${n + 1} refused: $closed")
        if (n == 0) {
          note = "Il percorso di openrouteservice passava da $closed: calcolato sul tablet rispettando i limiti del mezzo"
          return null
        }
        continue
      }
      val guided = ghGuide(f, s, spec)
      if (guided != null) out += guided
      else if (n == 0) return null
    }
    return out
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
