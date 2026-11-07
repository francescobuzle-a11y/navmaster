package app.navmaster.truck.search

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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


/**
 * Online search. Coordinates typed by the driver ("44.05, 12.56") work offline; everything else
 * goes, when there is a connection, to the geocoder of the tablet (Google's, on tablets with
 * Google services: the house numbers), to Photon (OpenStreetMap geocoder, typo tolerant) and to
 * HERE Search (the companies and places by their sign, see HereSearch), the answers put together
 * without doubles. The words a driver often glues together ("viaroma", "piazzagaribaldi") are
 * split first, as in NavMaster.
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

  suspend fun search(query: String, near: GeographicCoordinate?, withHere: Boolean = true): List<Found> =
      withContext(Dispatchers.IO) {
        parseCoordinates(query)?.let {
          return@withContext listOf(Found("Coordinate", String.format(java.util.Locale.ITALY, "%.5f, %.5f", it.lat, it.lng), it, "📌"))
        }
        val fixed = fixQuery(query)
        // the three at once: the geocoder of the tablet itself (Google's, on tablets with Google
        // services: it knows the house numbers as Google Maps does), Photon (OpenStreetMap) and
        // HERE (the companies)
        val hereKey = app.navmaster.truck.live.ApiKeys.here(app.navmaster.truck.AppGraph.settings.settings.value)
        val google = async { platform(fixed, near) }
        val here = async { if (withHere) HereSearch.discover(fixed, near, hereKey) else emptyList() }
        val osm = async {
          val first = photon(fixed, near)
          if (first.isNotEmpty() || fixed.length < 5) first
          // one more try without the last word (often a half-typed house number or town)
          else photon(fixed.substringBeforeLast(' '), near)
        }
        merge(listOf(google.await(), here.await(), osm.await()), query).take(12)
      }

  /**
   * The answers of the sources as one list without doubles: the ones whose name is what was
   * typed first (a company by its sign), each source's order kept otherwise; the same place from
   * two sources (same name within 150 m, or within 25 m) once, the first source's version.
   */
  fun merge(sources: List<List<Found>>, query: String): List<Found> {
    val out = ArrayList<Found>()
    for (list in sources) for (f in list) {
      if (out.any { Matching.samePlace(it.title, it.coordinate.lat, it.coordinate.lng, f.title, f.coordinate.lat, f.coordinate.lng) }) continue
      out += f
    }
    val toks = Matching.clean(TextNorm.tokens(query))
    if (toks.isEmpty()) return out
    return out.sortedByDescending { Matching.nameScore(it.title, null, toks) >= 80.0 }
  }

  /**
   * Android's own geocoder: on tablets with Google services it is Google's address search (the
   * one of Google Maps, house numbers included), free and with no key; elsewhere it may be absent.
   */
  fun platform(q: String, near: GeographicCoordinate?, max: Int = 5): List<Found> {
    if (q.isBlank() || !android.location.Geocoder.isPresent()) return emptyList()
    return try {
      val g = android.location.Geocoder(app.navmaster.truck.AppGraph.app, java.util.Locale.ITALY)
      val list: List<android.location.Address> =
          if (android.os.Build.VERSION.SDK_INT >= 33) {
            val latch = java.util.concurrent.CountDownLatch(1)
            var got: List<android.location.Address> = emptyList()
            val listener = object : android.location.Geocoder.GeocodeListener {
              override fun onGeocode(addresses: MutableList<android.location.Address>) { got = addresses; latch.countDown() }
              override fun onError(errorMessage: String?) { latch.countDown() }
            }
            if (near != null) g.getFromLocationName(q, max, near.lat - 1.5, near.lng - 2.0, near.lat + 1.5, near.lng + 2.0, listener)
            else g.getFromLocationName(q, max, listener)
            latch.await(6, TimeUnit.SECONDS)
            // nothing nearby: the whole world
            if (got.isEmpty() && near != null) {
              val l2 = java.util.concurrent.CountDownLatch(1)
              g.getFromLocationName(q, max, object : android.location.Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<android.location.Address>) { got = addresses; l2.countDown() }
                override fun onError(errorMessage: String?) { l2.countDown() }
              })
              l2.await(6, TimeUnit.SECONDS)
            }
            got
          } else {
            @Suppress("DEPRECATION") (g.getFromLocationName(q, max) ?: emptyList())
          }
      list.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
        val street = a.thoroughfare
        val number = a.subThoroughfare
        val title = when {
          street != null && number != null -> "$street $number"
          a.featureName != null && a.featureName != number -> a.featureName
          street != null -> street
          else -> a.locality ?: a.getAddressLine(0) ?: "Luogo"
        }
        val detail = listOfNotNull(a.postalCode, a.locality ?: a.subAdminArea, a.adminArea?.takeIf { it != a.locality }).distinct().joinToString(" · ")
        Found(title, detail.ifBlank { a.getAddressLine(0) ?: "" }, GeographicCoordinate(a.latitude, a.longitude), "🔎", offline = false)
      }
    } catch (e: Exception) {
      Log.w("NavMasterSearch", "platform geocoder: $e")
      emptyList()
    }
  }

  private fun photon(q: String, near: GeographicCoordinate?): List<Found> {
    if (q.isBlank()) return emptyList()
    val bias = near?.let { "&lat=${it.lat}&lon=${it.lng}" } ?: ""
    val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&lang=default&limit=8$bias"
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
          Found(title(props), detail(props), GeographicCoordinate(lat, lon), "🌐", offline = false)
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
