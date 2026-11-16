package app.navmaster.truck.routing

import android.os.SystemClock
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A route as the engine returns it: its line, where each stop is on it, length and time. */
class RouteResult(val lat: DoubleArray, val lon: DoubleArray, val waypointIndex: IntArray, val distanceM: Double, val timeMs: Long)

/**
 * The route computed by navmaster-engine on the tablet with openrouteservice's own logic: the
 * request is the JSON openrouteservice's API takes (profile driving-hgv with the vehicle's measures
 * for lorries and buses, driving-car for campers and cars), the answer its GeoJSON with the
 * Italian instructions and, next to it, the facts about the roads of the route (`navmaster.roads`).
 */
class NmRouting(
    private val engine: NmEngine,
    /** The NavMaster server, for the trips the tablet's graphs cannot cover (null in tests). */
    private val server: NmServer? = null,
    /** The country (catalog id) at a point, null when unknown. */
    private val countryOf: (Double, Double) -> String? = { _, _ -> null },
) {
  /** Why the last trip got no route (for the driver and the log). */
  @Volatile var note: String? = null
    private set

  /** The last answer (GeoJSON) and the number of points asked: its instructions guide the route (OrsGuide). */
  @Volatile var lastText: String? = null
    private set
  @Volatile var lastPoints: Int = 0
    private set

  /**
   * The routes through [points] ([lat, lon]) for [spec], best first; null when there is none (then
   * [note] says why). [passThrough]: the vehicle goes on through the intermediate points in its
   * direction of travel (openrouteservice's `continue_straight`).
   */
  fun routes(points: List<DoubleArray>, spec: RouteSpec, passThrough: Boolean = false, alternates: Int = 0, headings: List<Double?>? = null): List<RouteResult>? {
    if (points.size < 2) return null
    val started = SystemClock.elapsedRealtime()
    val body = request(points, spec, passThrough, alternates, headings).toString()
    // the engine on the tablet (also across borders, with the graphs of the countries crossed); the
    // NavMaster server when a country of the trip is not on the tablet and a server is set
    var from = "engine"
    var text: String? = engine.directions(points, spec.profile, body)
    if (text == null && server != null && server.unavailable() == null) {
      // a server with an older engine refuses alternatives beyond 100 km (openrouteservice's limit):
      // there the request goes without them
      val serverBody = if (alternates > 0 && points.size == 2 && app.navmaster.truck.core.Geo.dist(points[0][0], points[0][1], points[1][0], points[1][1]) > 95_000)
        request(points, spec, passThrough, 0, headings).toString() else body
      text = server.directions(spec.profile, serverBody)
      from = "server"
      if (text == null) Log.w(TAG, "server: ${server.note}")
    }
    if (text == null) {
      note = engine.problem ?: server?.note ?: "motore non disponibile"
      Log.w(TAG, "engine: $note")
      return null
    }
    return try {
      val root = Json.parseToJsonElement(text).jsonObject
      val error = root["error"]?.jsonObject
      if (error != null) {
        val code = error["code"]?.jsonPrimitive?.intOrNull ?: 0
        val message = error["message"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
        note = words(code, message, points.size)
        Log.w(TAG, "engine $code: $message")
        null
      } else parse(root, points.size).also {
        note = null
        lastText = text
        lastPoints = points.size
        Log.i(TAG, "$from ${spec.profile}: ${it?.size ?: 0} routes in ${SystemClock.elapsedRealtime() - started} ms " +
            it?.joinToString { r -> String.format(java.util.Locale.US, "%.1f km/%d min", r.distanceM / 1000, r.timeMs / 60000) })
      }
    } catch (e: Exception) {
      note = "risposta del motore non leggibile: $e"
      Log.w(TAG, note!!, e)
      null
    }
  }

  private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

  /** openrouteservice's error in words for the driver. */
  private fun words(code: Int, message: String, nPoints: Int): String = when (code) {
    2009 -> "nessun percorso tra i punti con le misure e le scelte del mezzo"
    2010 -> {
      val which = Regex("coordinate (\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
      val where = when (which) { 0 -> "dalla partenza"; nPoints - 1 -> "dalla destinazione"; null -> "da un punto"; else -> "dalla tappa $which" }
      "nessuna strada percorribile entro 400 m $where"
    }
    2004 -> "percorso troppo lungo per il calcolo sul tablet"
    else -> "errore del motore ($code): ${message.take(120)}"
  }

  private fun request(points: List<DoubleArray>, s: RouteSpec, passThrough: Boolean, alternates: Int, headings: List<Double?>?): JsonObject {
    val options = mutableMapOf<String, JsonElement>()
    val avoid = mutableListOf<JsonElement>()
    if (s.avoidTolls) avoid += JsonPrimitive("tollways")
    if (s.avoidFerries) avoid += JsonPrimitive("ferries")
    if (s.avoidMotorways) avoid += JsonPrimitive("highways")
    if (avoid.isNotEmpty()) options["avoid_features"] = JsonArray(avoid)
    // the vehicle's measures: for lorries and buses as openrouteservice takes them; for campers and
    // vans too (a NavMaster extension of the engine: the physical limits of the roads, height,
    // width, length, weight, axle load, apply to them, the bans for goods vehicles over 3.5 t do not)
    if (s.hgv) options["vehicle_type"] = JsonPrimitive(s.vehicleType.ifBlank { "hgv" })
    val r = mutableMapOf<String, JsonElement>()
    if (s.heightM > 0) r["height"] = JsonPrimitive(s.heightM)
    if (s.widthM > 0) r["width"] = JsonPrimitive(s.widthM)
    if (s.lengthM > 0) r["length"] = JsonPrimitive(s.lengthM)
    if (s.weightT > 0) r["weight"] = JsonPrimitive(s.weightT)
    if (s.axleLoadT > 0) r["axleload"] = JsonPrimitive(s.axleLoadT)
    if (s.hgv && s.hazmat) r["hazmat"] = JsonPrimitive(true)
    // NavMaster extensions of the engine (an older engine or server ignores them): the tunnel code
    // compared with each tunnel's ADR category, and the roads closed to goods polluting to water
    if (s.hgv && s.adrTunnelCode.isNotEmpty()) r["adr_tunnel_code"] = JsonPrimitive(s.adrTunnelCode)
    if (s.hgv && s.hazmatWater) r["hazmat_water"] = JsonPrimitive(true)
    if (r.isNotEmpty()) options["profile_params"] = JsonObject(mapOf("restrictions" to JsonObject(r)))
    if (s.avoidZones.isNotEmpty()) {
      // rings of [lon, lat] → GeoJSON MultiPolygon
      options["avoid_polygons"] = JsonObject(mapOf(
          "type" to JsonPrimitive("MultiPolygon"),
          "coordinates" to JsonArray(s.avoidZones.map { ring ->
            JsonArray(listOf(JsonArray(ring.map { p -> JsonArray(listOf(JsonPrimitive(p[0]), JsonPrimitive(p[1]))) })))
          }),
      ))
    }
    val o = mutableMapOf<String, JsonElement>(
        "coordinates" to JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it[1]), JsonPrimitive(it[0]))) }),
        "preference" to JsonPrimitive(s.preference),
        "units" to JsonPrimitive("m"),
        "instructions" to JsonPrimitive(true),
        "language" to JsonPrimitive("it"),
        "maneuvers" to JsonPrimitive(true),
        "extra_info" to JsonArray(listOf(JsonPrimitive("tollways"), JsonPrimitive("waycategory"), JsonPrimitive("waytype"))),
        "geometry" to JsonPrimitive(true),
        // NavMaster: the facts about the roads of the route (limits, lanes, signs, tolls), from the graph
        // NavMaster: «avoid» as a heavy penalty, not a ban (as TomTom's avoid): a road to avoid is used
        // only when there is no other way (the vehicle already on the motorway, an island, a pass
        // reached only through a toll), so the trip never fails nor starts on a road it cannot reach
        "navmaster" to JsonObject(if (avoid.isEmpty()) mapOf("roads" to JsonPrimitive(true)) else mapOf("roads" to JsonPrimitive(true), "avoid_penalty" to JsonPrimitive(true))),
    )
    if (options.isNotEmpty()) o["options"] = JsonObject(options)
    // ORS bearings: the direction of travel at the start and at the points passed through ([] where none)
    if (headings != null && headings.size == points.size && headings.any { it != null }) {
      o["bearings"] = JsonArray(headings.map { h ->
        if (h == null || h.isNaN()) JsonArray(emptyList()) else JsonArray(listOf(JsonPrimitive(((h % 360) + 360) % 360), JsonPrimitive(45)))
      })
    }
    // pass-through points (the driver going on along his road, or back onto the planned route): no u-turn at them
    if (passThrough && points.size > 2) o["continue_straight"] = JsonPrimitive(true)
    // the vehicle's top speed (openrouteservice accepts 80 km/h and more): only when it is below the
    // profile's own top speeds (lorries 85 km/h on motorways, cars 130), else it changes nothing and
    // would only make the search slower
    if (s.topSpeedKmh > 0 && s.topSpeedKmh < (if (s.hgv) 85.0 else 130.0)) o["maximum_speed"] = JsonPrimitive(s.topSpeedKmh.coerceAtLeast(80.0))
    // alternatives (openrouteservice's alternative_routes), between two points: the engine picks the
    // method, openrouteservice's plateau up to 60 km (its full search does not fit the tablet's memory
    // on longer trips, NmEngine's limit), beyond that and across borders routes through points to the
    // side of the trip (navmaster-engine LongAlternatives)
    if (alternates > 0 && points.size == 2) {
      o["alternative_routes"] = JsonObject(mapOf(
          "target_count" to JsonPrimitive((1 + alternates).coerceIn(2, 3)), "weight_factor" to JsonPrimitive(1.4), "share_factor" to JsonPrimitive(0.6),
      ))
    }
    return JsonObject(o)
  }

  private fun parse(root: JsonObject, nPoints: Int): List<RouteResult>? {
    val features = root["features"]?.jsonArray ?: return null
    val out = mutableListOf<RouteResult>()
    for (f in features) {
      val fo = f.jsonObject
      val coords = fo["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: continue
      if (coords.size < 2) continue
      val props = fo["properties"]?.jsonObject
      val summary = props?.get("summary")?.jsonObject
      val lat = DoubleArray(coords.size) { coords[it].jsonArray[1].jsonPrimitive.double }
      val lon = DoubleArray(coords.size) { coords[it].jsonArray[0].jsonPrimitive.double }
      val wp = props?.get("way_points")?.jsonArray?.map { it.jsonPrimitive.int }
      val idx = if (wp != null && wp.size == nPoints) wp.toIntArray() else intArrayOf(0, coords.size - 1)
      out += RouteResult(lat, lon, idx, summary?.get("distance")?.jsonPrimitive?.doubleOrNull ?: 0.0,
          ((summary?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong())
    }
    return out.takeIf { it.isNotEmpty() }
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
