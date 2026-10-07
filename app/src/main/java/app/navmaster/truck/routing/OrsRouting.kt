package app.navmaster.truck.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import app.navmaster.truck.BuildConfig
import app.navmaster.truck.routing.gh.GhEngine
import app.navmaster.truck.routing.gh.TruckSpec
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The route computed online by openrouteservice (HeiGIT, free "Standard" plan: 2,000 routes a day,
 * 40 a minute), when the tablet is online and the app was built with a key (GitHub secret ORS_KEY):
 * lorries with the profile driving-hgv and the vehicle's measures, campers and cars driving-car.
 * Without network, without key, over the quota or on any error: null, and the route is computed on
 * the tablet as before (GraphHopper, then Valhalla). The path found is then guided by Valhalla
 * exactly as GraphHopper's (see OfflineRouteProvider.guide).
 */
class OrsRouting(private val context: Context, private val enabled: () -> Boolean) {
  private val client = OkHttpClient.Builder()
      .connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

  /** No more requests until this moment (elapsed realtime): the daily quota was used up, or the key refused. */
  @Volatile private var pausedUntil = 0L

  /** Why the last trip did not use openrouteservice (for the log). */
  @Volatile var note: String? = null
    private set

  val key: String
    get() = BuildConfig.ORS_KEY

  fun available(): Boolean {
    if (key.isBlank() || !enabled()) return false
    if (SystemClock.elapsedRealtime() < pausedUntil) return false
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  /**
   * Up to 1 + [alternates] routes through [points] ([lat, lon]) for [spec], best first, as
   * GraphHopper's results (points, stops, distance, time); null when openrouteservice cannot be used.
   */
  fun routes(points: List<DoubleArray>, spec: TruckSpec, alternates: Int): List<GhEngine.Result>? {
    if (!available() || points.size < 2) return null
    val started = SystemClock.elapsedRealtime()
    val profile = if (spec.hgv) "driving-hgv" else "driving-car"
    val body = request(points, spec, alternates, profile).toString()
    return try {
      val req = Request.Builder()
          .url("https://api.openrouteservice.org/v2/directions/$profile/geojson")
          .header("Authorization", key)
          .header("Accept", "application/geo+json, application/json")
          .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .build()
      client.newCall(req).execute().use { rsp ->
        val text = rsp.body.string()
        when {
          rsp.code == 429 || rsp.code == 403 -> {
            // daily quota used up, or the key refused: not again before tomorrow
            pausedUntil = SystemClock.elapsedRealtime() + msToMidnight()
            note = "openrouteservice ${rsp.code}: ${text.take(160)}"
            Log.w(TAG, note!!)
            null
          }
          !rsp.isSuccessful -> {
            note = "openrouteservice ${rsp.code}: ${text.take(200)}"
            Log.w(TAG, note!!)
            null
          }
          else -> parse(text, points.size).also {
            note = null
            Log.i(TAG, "openrouteservice $profile: ${it?.size ?: 0} routes in ${SystemClock.elapsedRealtime() - started} ms " +
                it?.joinToString { r -> String.format(java.util.Locale.US, "%.1f km/%d min", r.distanceM / 1000, r.timeMs / 60000) })
          }
        }
      }
    } catch (e: Exception) {
      note = "openrouteservice: $e"
      Log.w(TAG, note!!)
      null
    }
  }

  private fun request(points: List<DoubleArray>, s: TruckSpec, alternates: Int, profile: String): JsonObject {
    val options = mutableMapOf<String, JsonElement>()
    val avoid = mutableListOf<JsonElement>()
    if (s.avoidTolls) avoid += JsonPrimitive("tollways")
    if (s.avoidFerries) avoid += JsonPrimitive("ferries")
    if (avoid.isNotEmpty()) options["avoid_features"] = JsonArray(avoid)
    if (s.hgv) {
      options["vehicle_type"] = JsonPrimitive("hgv")
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
      // rings of [lat, lon] → GeoJSON polygons of [lon, lat]
      options["avoid_polygons"] = JsonObject(mapOf(
          "type" to JsonPrimitive("MultiPolygon"),
          "coordinates" to JsonArray(s.avoidZones.map { ring ->
            JsonArray(listOf(JsonArray(ring.map { p -> JsonArray(listOf(JsonPrimitive(p[1]), JsonPrimitive(p[0]))) })))
          }),
      ))
    }
    val o = mutableMapOf<String, JsonElement>(
        "coordinates" to JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it[1]), JsonPrimitive(it[0]))) }),
        "preference" to JsonPrimitive(when {
          s.route == GhEngine.ROUTE_SHORT || s.shortest -> "shortest"
          s.route == GhEngine.ROUTE_MOTORWAY -> "recommended"
          else -> "fastest"
        }),
        "units" to JsonPrimitive("m"),
        "instructions" to JsonPrimitive(false),
        "geometry" to JsonPrimitive(true),
    )
    if (options.isNotEmpty()) o["options"] = JsonObject(options)
    // the vehicle's top speed (openrouteservice accepts 80 km/h and more)
    if (s.topSpeedKmh > 0) o["maximum_speed"] = JsonPrimitive(s.topSpeedKmh.coerceAtLeast(80.0))
    // alternatives: only between two points closer than 100 km (openrouteservice's limit)
    if (alternates > 0 && points.size == 2 && crow(points[0], points[1]) < 95_000) {
      o["alternative_routes"] = JsonObject(mapOf(
          "target_count" to JsonPrimitive(1 + alternates), "weight_factor" to JsonPrimitive(1.4), "share_factor" to JsonPrimitive(0.6),
      ))
    }
    return JsonObject(o)
  }

  private fun parse(text: String, nPoints: Int): List<GhEngine.Result>? {
    val root = Json.parseToJsonElement(text).jsonObject
    val features = root["features"]?.jsonArray ?: return null
    val out = mutableListOf<GhEngine.Result>()
    for (f in features) {
      val fo = f.jsonObject
      val coords = fo["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: continue
      if (coords.size < 2) continue
      val props = fo["properties"]?.jsonObject
      val summary = props?.get("summary")?.jsonObject
      val r = GhEngine.Result()
      r.lat = DoubleArray(coords.size) { coords[it].jsonArray[1].jsonPrimitive.double }
      r.lon = DoubleArray(coords.size) { coords[it].jsonArray[0].jsonPrimitive.double }
      val wp = props?.get("way_points")?.jsonArray?.map { it.jsonPrimitive.int }
      r.waypointIndex = if (wp != null && wp.size == nPoints) wp.toIntArray() else intArrayOf(0, coords.size - 1)
      r.distanceM = summary?.get("distance")?.jsonPrimitive?.doubleOrNull ?: 0.0
      r.timeMs = ((summary?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
      out += r
    }
    return out.takeIf { it.isNotEmpty() }
  }

  private fun crow(a: DoubleArray, b: DoubleArray): Double {
    val k = Math.cos(Math.toRadians((a[0] + b[0]) / 2))
    val dy = (b[0] - a[0]) * 111_195
    val dx = (b[1] - a[1]) * 111_195 * k
    return Math.sqrt(dx * dx + dy * dy)
  }

  private fun msToMidnight(): Long {
    val c = Calendar.getInstance()
    val now = c.timeInMillis
    c.add(Calendar.DAY_OF_YEAR, 1)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 5); c.set(Calendar.SECOND, 0)
    return (c.timeInMillis - now).coerceAtLeast(60_000)
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
