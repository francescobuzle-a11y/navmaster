package app.navmaster.truck.live

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import uniffi.ferrostar.GeographicCoordinate

/**
 * PERSONAL TEST ONLY (the owner's demo, not for distribution): reads the reports of a server the
 * driver runs himself on his own computer, in the format of the "waze-api" server used by the
 * JMoore335/waze_traffic_api script: `GET <server>/waze/traffic-notifications?latBottom=..&latTop=..
 * &lonLeft=..&lonRight=..` → `{"alerts":[{type, subType, latitude, longitude, numOfThumbsUp,
 * placeNearBy}], "jams":[{street, startLatitude, startLongitude, endLatitude, endLongitude,
 * delayInSec, severity}]}`.
 *
 * Off unless the driver turns it on and writes the address of his server; what it reads stays on
 * this tablet (never sent on to the NavMaster reports), and it is asked at most every 2 minutes.
 */
object PersonalFeed {
  private const val TAG = "NavMasterLive"
  const val SOURCE = "Server personale (prova)"
  private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

  private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

  private fun kindOf(type: String?, sub: String?): LiveKind? {
    val t = type?.uppercase() ?: return null
    val s = sub?.uppercase() ?: ""
    return when {
      t == "POLICE" -> LiveKind.POLICE
      t == "ACCIDENT" -> LiveKind.ACCIDENT
      t == "JAM" -> LiveKind.JAM
      t == "ROAD_CLOSED" -> LiveKind.CLOSED
      t == "CONSTRUCTION" -> LiveKind.ROADWORKS
      "CAR_STOPPED" in s -> LiveKind.BROKEN_VEHICLE
      "WEATHER" in s || "FOG" in s || "ICE" in s || "FLOOD" in s || "HAIL" in s -> LiveKind.WEATHER
      "CONSTRUCTION" in s -> LiveKind.ROADWORKS
      t == "HAZARD" || t == "WEATHERHAZARD" -> LiveKind.HAZARD
      else -> LiveKind.HAZARD
    }
  }

  /** The reports inside [box]; null when the server does not answer. */
  fun read(server: String, box: GeoBox): List<LiveEvent>? {
    val base = server.trim().trimEnd('/')
    if (base.isBlank()) return null
    val f = { v: Double -> String.format(java.util.Locale.ROOT, "%.6f", v) }
    val url = "$base/waze/traffic-notifications?latBottom=${f(box.s)}&latTop=${f(box.n)}&lonLeft=${f(box.w)}&lonRight=${f(box.e)}"
    val text = (try {
      client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (r.isSuccessful) r.body.string() else null.also { Log.w(TAG, "personal feed: HTTP ${r.code}") }
      }
    } catch (e: Exception) {
      Log.w(TAG, "personal feed: $e")
      null
    }) ?: return null
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val now = System.currentTimeMillis()
    val out = mutableListOf<LiveEvent>()
    (root["alerts"] as? JsonArray)?.forEachIndexed { i, a ->
      val o = a as? JsonObject ?: return@forEachIndexed
      val lat = num(o["latitude"]) ?: return@forEachIndexed
      val lon = num(o["longitude"]) ?: return@forEachIndexed
      val kind = kindOf(str(o["type"]), str(o["subType"])) ?: return@forEachIndexed
      out += LiveEvent(
          id = "pf:a:${"%.5f".format(java.util.Locale.ROOT, lat)},${"%.5f".format(java.util.Locale.ROOT, lon)},${kind.name}:$i",
          source = SOURCE, kind = kind, title = kind.label, detail = str(o["placeNearBy"]),
          lat = lat, lon = lon, timeMs = now, official = false,
          confirms = ((o["numOfThumbsUp"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0))
    }
    (root["jams"] as? JsonArray)?.forEachIndexed { i, j ->
      val o = j as? JsonObject ?: return@forEachIndexed
      val sLat = num(o["startLatitude"]) ?: return@forEachIndexed
      val sLon = num(o["startLongitude"]) ?: return@forEachIndexed
      val eLat = num(o["endLatitude"])
      val eLon = num(o["endLongitude"])
      val line = if (eLat != null && eLon != null) listOf(GeographicCoordinate(sLat, sLon), GeographicCoordinate(eLat, eLon)) else emptyList()
      out += LiveEvent(
          id = "pf:j:$i:${"%.5f".format(java.util.Locale.ROOT, sLat)}", source = SOURCE, kind = LiveKind.JAM,
          title = "Coda" + (str(o["street"])?.let { " · $it" } ?: ""), lat = sLat, lon = sLon, line = line,
          timeMs = now, delayS = (o["delayInSec"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0, official = false)
    }
    Log.i(TAG, "personal feed: ${out.size} reports (${out.groupingBy { it.kind }.eachCount()})")
    return out
  }
}
