package app.navmaster.truck.search

import android.util.Log
import app.navmaster.truck.live.HereGuard
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
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

/**
 * The companies and places of HERE (Geocoding & Search v7, "discover": the search of HERE WeGo),
 * for what the open data of the tablet do not have: a haulier's yard, a workshop, a warehouse
 * known by its sign. Asked with the fleet's HERE key, through HereGuard (its own allowance,
 * HERE_SEARCH_MONTH_FREE): over the limit the answer is empty and nothing is sent. The answers
 * are kept (the last 64 queries), so retyping costs nothing.
 *
 * Not Google Places: its terms forbid showing its results on a map that is not Google's.
 */
object HereSearch {
  private const val TAG = "NavMasterSearch"
  private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).addInterceptor(HereGuard).build()
  private val json = Json { ignoreUnknownKeys = true }
  private val cache = object : LinkedHashMap<String, List<Found>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Found>>?): Boolean = size > 64
  }

  /** What HERE answered for the query (empty without a key, over the allowance, or offline). */
  fun discover(query: String, near: GeographicCoordinate?, key: String, limit: Int = 8): List<Found> {
    val q = query.trim()
    if (q.length < 3 || key.isBlank()) return emptyList()
    val cacheKey = TextNorm.norm(q) + "|" + (near?.let { "%.2f,%.2f".format(java.util.Locale.ROOT, it.lat, it.lng) } ?: "")
    synchronized(cache) { cache[cacheKey] }?.let { return it }
    if (!HereGuard.canSearch()) return emptyList()
    // "at" is required with a free text; without a position, the middle of the installed country (Italy)
    val at = near ?: GeographicCoordinate(45.0, 10.5)
    val url = "https://discover.search.hereapi.com/v1/discover?q=${URLEncoder.encode(q, "UTF-8")}" +
        "&at=${"%.5f,%.5f".format(java.util.Locale.ROOT, at.lat, at.lng)}&limit=$limit&lang=it&apiKey=${URLEncoder.encode(key, "UTF-8")}"
    return try {
      client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/1.0").build()).execute().use { resp ->
        if (!resp.isSuccessful) {
          if (resp.code != 204) Log.w(TAG, "HERE discover: ${resp.code}")
          return emptyList()
        }
        val out = parse(resp.body.string())
        synchronized(cache) { cache[cacheKey] = out }
        out
      }
    } catch (e: Exception) {
      Log.w(TAG, "HERE discover: $e")
      emptyList()
    }
  }

  /** The items of a "discover" answer as results (pure: tested on the computer). */
  fun parse(body: String): List<Found> {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
    return root["items"]?.jsonArray?.mapNotNull { e ->
      val it = e as? JsonObject ?: return@mapNotNull null
      val title = it.s("title") ?: return@mapNotNull null
      val pos = it["position"]?.jsonObject ?: return@mapNotNull null
      val lat = pos["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
      val lng = pos["lng"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
      val type = it.s("resultType") ?: ""
      val addr = it["address"]?.jsonObject
      val category = it["categories"]?.jsonArray?.mapNotNull { c -> c.jsonObject }
          ?.let { cs -> cs.firstOrNull { c -> c["primary"]?.jsonPrimitive?.contentOrNull == "true" } ?: cs.firstOrNull() }?.s("name")
      val street = listOfNotNull(addr?.s("street"), addr?.s("houseNumber")).joinToString(" ").takeIf { s -> s.isNotBlank() }
      val city = addr?.s("city") ?: addr?.s("county")
      val detail = when (type) {
        // an address: the town and the province are the news, the title has the street
        "houseNumber", "street", "locality", "administrativeArea" -> listOfNotNull(addr?.s("postalCode"), city, addr?.s("state")).distinct().joinToString(" · ")
        else -> listOfNotNull(category, street, city).distinct().joinToString(" · ")
      }
      val icon = when (type) {
        "place" -> iconOf(category ?: "")
        "houseNumber", "street" -> "📍"
        "locality", "administrativeArea" -> "🏙"
        else -> "🔎"
      }
      Found(title, detail, GeographicCoordinate(lat, lng), icon, offline = false)
    } ?: emptyList()
  }

  private fun JsonObject.s(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  /** The icon of a HERE category (its Italian name). */
  fun iconOf(category: String): String {
    val c = category.lowercase()
    return when {
      c.contains("trasport") || c.contains("logistic") || c.contains("spedizion") || c.contains("corrier") || c.contains("camion") || c.contains("autocarr") -> "🚚"
      c.contains("magazzin") || c.contains("deposit") || c.contains("industr") || c.contains("fabbric") || c.contains("produz") -> "🏭"
      c.contains("gomm") || c.contains("pneumat") || c.contains("officin") || c.contains("riparaz") || c.contains("ricambi") || c.contains("concession") -> "🔧"
      c.contains("ristor") || c.contains("pizz") || c.contains("trattor") -> "🍽"
      c.contains("bar") || c.contains("caff") -> "☕"
      c.contains("hotel") || c.contains("alberg") || c.contains("motel") -> "🏨"
      c.contains("benzin") || c.contains("carburant") || c.contains("distribut") -> "⛽"
      c.contains("parchegg") -> "🅿"
      c.contains("ospedal") || c.contains("clinic") || c.contains("farmac") -> "🏥"
      c.contains("supermerc") || c.contains("negoz") || c.contains("centro commerc") || c.contains("alimentar") -> "🛒"
      c.contains("stazion") || c.contains("ferrov") -> "🚉"
      c.contains("aeroport") -> "✈"
      c.isEmpty() -> "🔎"
      else -> "🏢"
    }
  }
}
