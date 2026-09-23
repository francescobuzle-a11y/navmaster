package app.navmaster.truck.ui

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import uniffi.ferrostar.GeographicCoordinate

data class Place(val title: String, val detail: String, val coordinate: GeographicCoordinate)

/**
 * Address search. Coordinates typed by the driver ("44.05, 12.56") work offline; everything else
 * goes to Photon (OpenStreetMap geocoder, typo tolerant) when there is a connection. The words a
 * driver often glues together ("viaroma", "piazzagaribaldi") are split first, as in NavMaster.
 */
object Geocoder {
  private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }
  private val glued =
      listOf("piazzale", "piazza", "viale", "vicolo", "corso", "strada", "contrada", "localita", "località", "frazione", "largo", "borgo", "via")

  fun fixQuery(q: String): String =
      q.trim().split(Regex("\\s+")).joinToString(" ") { word ->
        val w = word.lowercase()
        val p = glued.firstOrNull { w.startsWith(it) && w.length > it.length + 2 }
        if (p != null) word.substring(0, p.length) + " " + word.substring(p.length) else word
      }

  fun parseCoordinates(q: String): GeographicCoordinate? {
    val m = Regex("^\\s*(-?\\d{1,2}[.,]\\d+)\\s*[,; ]\\s*(-?\\d{1,3}[.,]\\d+)\\s*$").find(q) ?: return null
    val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeographicCoordinate(lat, lon) else null
  }

  suspend fun search(query: String, near: GeographicCoordinate?): List<Place> =
      withContext(Dispatchers.IO) {
        parseCoordinates(query)?.let {
          return@withContext listOf(Place("Coordinate", String.format("%.5f, %.5f", it.lat, it.lng), it))
        }
        val fixed = fixQuery(query)
        val first = photon(fixed, near)
        if (first.isNotEmpty() || fixed.length < 5) first
        // one more try without the last word (often a half-typed house number or town)
        else photon(fixed.substringBeforeLast(' '), near)
      }

  private fun photon(q: String, near: GeographicCoordinate?): List<Place> {
    if (q.isBlank()) return emptyList()
    val bias = near?.let { "&lat=${it.lat}&lon=${it.lng}" } ?: ""
    val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&lang=it&limit=8$bias"
    return try {
      client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/0.1").build()).execute().use { resp ->
        if (!resp.isSuccessful) return emptyList()
        val root = json.parseToJsonElement(resp.body.string()).jsonObject
        root["features"]?.jsonArray?.mapNotNull { f ->
          val obj = f.jsonObject
          val props = obj["properties"]?.jsonObject ?: return@mapNotNull null
          val coords = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
          val lon = coords[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
          val lat = coords[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
          Place(title(props), detail(props), GeographicCoordinate(lat, lon))
        } ?: emptyList()
      }
    } catch (e: Exception) {
      Log.w("NavMasterSearch", "photon: $e")
      emptyList()
    }
  }

  private fun JsonObject.s(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun title(p: JsonObject): String {
    val street = p.s("street")
    val number = p.s("housenumber")
    val name = p.s("name")
    return when {
      street != null && number != null -> "$street $number"
      name != null -> name
      street != null -> street
      else -> p.s("city") ?: "Luogo"
    }
  }

  private fun detail(p: JsonObject): String =
      listOfNotNull(p.s("postcode"), p.s("city") ?: p.s("county"), p.s("state")).distinct().joinToString(" · ")
}
