package app.navmaster.truck.search

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uniffi.ferrostar.GeographicCoordinate

/**
 * Turns what another app shares (a Google Maps place or position, a geo: link, a Waze or
 * OpenStreetMap link, or just an address) into a destination: the precise point and its name.
 */
object MapLinkResolver {
  data class Target(val coordinate: GeographicCoordinate, val label: String, val how: String)

  private val client =
      OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(12, TimeUnit.SECONDS).build()

  // a browser, with Google's consent already given (EU), so that pages answer with the place itself
  private const val UA = "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"
  private const val CONSENT = "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb"

  private fun get(url: String): Pair<Int, Pair<String?, String?>> =
      client.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Cookie", CONSENT)
          .header("Accept-Language", "it-IT,it;q=0.9").build()).execute().use { r ->
        val loc = r.header("Location")?.let { r.request.url.resolve(it)?.toString() ?: it }
        val body = if (r.code in 200..299) r.body.string().take(2_000_000) else null
        r.code to (loc to body)
      }

  suspend fun resolve(text: String, near: GeographicCoordinate?): Target? = withContext(Dispatchers.IO) {
    val shared = MapLinks.sharedName(text)
    var url = MapLinks.firstUrl(text)
    if (url == null) {
      // only words (an address copied from somewhere): searched
      return@withContext search(text.trim(), near, text.trim())
    }
    MapLinks.parse(url)?.takeIf { it.precise }?.let { p ->
      return@withContext Target(GeographicCoordinate(p.lat, p.lon), p.label ?: shared ?: "Posizione condivisa", "link")
    }
    var rough = MapLinks.parse(url)
    var pageHtml: String? = null
    // short links and links without a point: followed hop by hop, every address read on the way
    try {
      for (hop in 0 until 8) {
        val u = url ?: break
        if (!u.startsWith("http")) break
        val (code, res) = get(u)
        val (loc, body) = res
        if (loc != null && code in 300..399) {
          url = loc
          val p = MapLinks.parse(loc)
          if (p != null && p.precise) return@withContext Target(GeographicCoordinate(p.lat, p.lon), p.label ?: shared ?: "Posizione condivisa", "link")
          if (p != null) rough = p
          continue
        }
        pageHtml = body
        break
      }
    } catch (e: Exception) {
      Log.w(TAG, "map link: $e")
    }
    // the page of the place: its pin
    val finalUrl = url ?: ""
    val html = pageHtml
    if (html != null) {
      val pin = MapLinks.fromPage(html)
      if (pin != null) {
        val name = MapLinks.searchText(finalUrl) ?: shared ?: rough?.label ?: "Posizione condivisa"
        return@withContext Target(GeographicCoordinate(pin.first, pin.second), name, "pagina")
      }
    }
    // last: the name/address of the shared text or of the link, searched near the view of the link
    val words = shared ?: MapLinks.searchText(finalUrl)
    val r = rough
    val around = if (r != null) GeographicCoordinate(r.lat, r.lon) else near
    if (words != null) search(words, around, words)?.let { return@withContext it }
    if (r != null) return@withContext Target(GeographicCoordinate(r.lat, r.lon), r.label ?: shared ?: "Posizione condivisa", "vista")
    null
  }

  private fun search(words: String, near: GeographicCoordinate?, label: String): Target? {
    val found = Geocoder.platform(words, near, 3).firstOrNull()
        ?: runCatching { kotlinx.coroutines.runBlocking { Geocoder.search(words, near) } }.getOrNull()?.firstOrNull()
        ?: return null
    return Target(found.coordinate, label.lineSequence().first().take(80), "ricerca")
  }

  private const val TAG = "NavMasterLinks"
}
