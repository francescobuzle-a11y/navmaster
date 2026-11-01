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
class NmRouting(private val engine: NmEngine) {
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
  fun routes(points: List<DoubleArray>, spec: RouteSpec, passThrough: Boolean = false): List<RouteResult>? {
    if (points.size < 2) return null
    val started = SystemClock.elapsedRealtime()
    val body = request(points, spec, passThrough).toString()
    val text = engine.directions(points[0][0], points[0][1], spec.profile, body)
    if (text == null) {
      note = engine.problem ?: "motore non disponibile"
      Log.w(TAG, "engine: ${note}")
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
        Log.i(TAG, "engine ${spec.profile}: ${it?.size ?: 0} routes in ${SystemClock.elapsedRealtime() - started} ms " +
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

  private fun request(points: List<DoubleArray>, s: RouteSpec, passThrough: Boolean): JsonObject {
    val options = mutableMapOf<String, JsonElement>()
    val avoid = mutableListOf<JsonElement>()
    if (s.avoidTolls) avoid += JsonPrimitive("tollways")
    if (s.avoidFerries) avoid += JsonPrimitive("ferries")
    if (s.avoidMotorways) avoid += JsonPrimitive("highways")
    if (avoid.isNotEmpty()) options["avoid_features"] = JsonArray(avoid)
    if (s.hgv) {
      options["vehicle_type"] = JsonPrimitive(s.vehicleType.ifBlank { "hgv" })
      val r = mutableMapOf<String, JsonElement>()
      if (s.heightM > 0) r["height"] = JsonPrimitive(s.heightM)
      if (s.widthM > 0) r["width"] = JsonPrimitive(s.widthM)
      if (s.lengthM > 0) r["length"] = JsonPrimitive(s.lengthM)
      if (s.weightT > 0) r["weight"] = JsonPrimitive(s.weightT)
      if (s.axleLoadT > 0) r["axleload"] = JsonPrimitive(s.axleLoadT)
      if (s.hazmat) r["hazmat"] = JsonPrimitive(true)
      options["profile_params"] = JsonObject(mapOf("restrictions" to JsonObject(r)))
    }
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
        "navmaster" to JsonObject(mapOf("roads" to JsonPrimitive(true))),
    )
    if (options.isNotEmpty()) o["options"] = JsonObject(options)
    // pass-through points (the driver going on along his road, or back onto the planned route): no u-turn at them
    if (passThrough && points.size > 2) o["continue_straight"] = JsonPrimitive(true)
    // the vehicle's top speed (openrouteservice accepts 80 km/h and more)
    if (s.topSpeedKmh > 0) o["maximum_speed"] = JsonPrimitive(s.topSpeedKmh.coerceAtLeast(80.0))
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
