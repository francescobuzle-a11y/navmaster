package app.navmaster.truck.search

import java.net.URLDecoder

/**
 * A place shared from another map app (Google Maps above all, also Apple Maps, OpenStreetMap,
 * Waze, any "geo:" link): the precise point, and a name to show.
 *
 * What Google Maps shares, and where the point is in it, in order of precision:
 *  - the pin of a place:            .../maps/place/Name/@44.06,12.56,17z/data=...!3d44.0612!4d12.5634
 *  - a dropped pin / coordinates:   ...?q=44.0612,12.5634  ·  /maps/search/44.0612,+12.5634
 *  - directions:                    ...?daddr=44.06,12.56  ·  ?destination=44.06,12.56
 *  - only the view (least precise): /@44.06,12.56,15z
 *  - short links (maps.app.goo.gl/xxxx, goo.gl/maps/xxxx) carry none of these: they are opened
 *    (MapLinkResolver) and the address they lead to is read; when even that has no point (a
 *    place given only by name and id), the page itself is read, and as last resort the name and
 *    address of the shared text are searched.
 *
 * Pure Kotlin, tested on the computer.
 */
object MapLinks {
  data class Point(val lat: Double, val lon: Double, val label: String?, val precise: Boolean)

  private const val NUM = "(-?\\d{1,3}(?:\\.\\d+)?)"

  /** The first web address in a shared text ("Ristorante X\nVia Roma 1\nhttps://maps.app.goo.gl/abc"). */
  fun firstUrl(text: String): String? = Regex("(https?://|geo:|google\\.navigation:)[^\\s\"<>]+").find(text)?.value?.trimEnd('.', ',', ')')

  /** The lines of a shared text that are not the link: usually the name and the address. */
  fun sharedName(text: String): String? =
      text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("://") && !it.startsWith("geo:") }
          .take(2).joinToString(", ").takeIf { it.isNotBlank() }?.take(120)

  fun isShortLink(url: String): Boolean =
      Regex("^https?://(maps\\.app\\.goo\\.gl|goo\\.gl/maps|g\\.co/kgs|maps\\.google\\.com/\\?cid|share\\.google|waze\\.com/ul/)", RegexOption.IGNORE_CASE)
          .containsMatchIn(url)

  private fun ok(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

  private fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").replace("%2B", "+") }.getOrDefault(s)

  /** "Ristorante+Da+Mario" from /maps/place/Ristorante+Da+Mario/@... */
  private fun placeName(url: String): String? =
      Regex("/maps/place/([^/@?]+)").find(url)?.groupValues?.get(1)?.let { decode(it).replace('+', ' ').trim() }
          ?.takeIf { it.isNotBlank() && Regex("^-?\\d").find(it) == null }

  private fun query(url: String, key: String): String? =
      Regex("[?&]$key=([^&#]+)").find(url)?.groupValues?.get(1)?.let { decode(it).replace('+', ' ') }

  private fun latLonIn(text: String?): Pair<Double, Double>? {
    if (text == null) return null
    val m = Regex("^\\s*(?:loc:)?\\s*$NUM\\s*,\\s*\\+?$NUM").find(text) ?: return null
    val lat = m.groupValues[1].toDouble()
    val lon = m.groupValues[2].toDouble()
    return if (ok(lat, lon)) lat to lon else null
  }

  /**
   * The point of a map link, without going on the network; null when the link has none (a short
   * link, or a place given only by name).
   */
  fun parse(link: String): Point? {
    val url = link.trim()
    // the Google consent page (EU) carries the real address in "continue"
    if (url.contains("consent.google.") && url.contains("continue=")) query(url, "continue")?.let { return parse(it) }
    val name = placeName(url) ?: query(url, "q")?.takeIf { latLonIn(it) == null }

    // geo:44.06,12.56?q=44.0612,12.5634(Name)  ·  geo:0,0?q=Via+Roma+1  ·  google.navigation:q=44.06,12.56
    if (url.startsWith("geo:", true) || url.startsWith("google.navigation:", true)) {
      val q = query(url.replace("google.navigation:", "google.navigation:?"), "q")
      latLonIn(q)?.let { (a, b) ->
        val label = Regex("\\(([^)]+)\\)").find(q ?: "")?.groupValues?.get(1)
        return Point(a, b, label, true)
      }
      latLonIn(url.removePrefix("geo:").removePrefix("GEO:"))?.let { (a, b) -> return Point(a, b, null, true) }
      return null
    }
    // the pin of a place: !3d<lat>!4d<lon> (the last pair is the place itself)
    Regex("!3d$NUM!4d$NUM").findAll(url).lastOrNull()?.let { m ->
      val a = m.groupValues[1].toDouble()
      val b = m.groupValues[2].toDouble()
      if (ok(a, b)) return Point(a, b, name, true)
    }
    // explicit points: q=, query=, destination=, daddr=, ll=, sll=, center=
    for (key in listOf("q", "query", "destination", "daddr", "ll", "sll", "center", "coordinates")) {
      latLonIn(query(url, key))?.let { (a, b) -> return Point(a, b, name, true) }
    }
    // /maps/search/44.0612,+12.5634 · /maps/dir/.../44.06,12.56 (the last one is the destination)
    Regex("/maps/(?:search|place|dir)/((?:[^?#])+)").find(url)?.groupValues?.get(1)?.split('/')?.reversed()?.forEach { part ->
      latLonIn(decode(part))?.let { (a, b) -> return Point(a, b, name, true) }
    }
    // OpenStreetMap: #map=17/44.06/12.56 · ?mlat=..&mlon=..
    val mlat = query(url, "mlat")?.toDoubleOrNull()
    val mlon = query(url, "mlon")?.toDoubleOrNull()
    if (mlat != null && mlon != null && ok(mlat, mlon)) return Point(mlat, mlon, name, true)
    // Waze: ?ll=44.06,12.56 (done above) · Apple Maps: ?ll= / ?coordinate= (done above)
    // only the view: /@44.06,12.56,17z (good for a dropped pin in the middle, less for a place)
    Regex("/@$NUM,$NUM(?:,|$)").find(url)?.let { m ->
      val a = m.groupValues[1].toDouble()
      val b = m.groupValues[2].toDouble()
      if (ok(a, b)) return Point(a, b, name, name == null)
    }
    Regex("#map=\\d+(?:\\.\\d+)?/$NUM/$NUM").find(url)?.let { m ->
      val a = m.groupValues[1].toDouble()
      val b = m.groupValues[2].toDouble()
      if (ok(a, b)) return Point(a, b, name, true)
    }
    return null
  }

  /** What can be searched when a link has no point: the place name or the "q=" text of the link. */
  fun searchText(link: String): String? =
      placeName(link) ?: query(link, "q")?.takeIf { latLonIn(it) == null } ?: query(link, "query")?.takeIf { latLonIn(it) == null }
          ?: query(link, "daddr")?.takeIf { latLonIn(it) == null }

  /**
   * The point written in a Google Maps page (when the link itself has none): the place pin
   * (!3d!4d or "[null,null,lat,lon]") rather than the centre of the view.
   */
  fun fromPage(html: String): Pair<Double, Double>? {
    Regex("!3d$NUM!4d$NUM").find(html)?.let { m -> m.groupValues[1].toDouble() to m.groupValues[2].toDouble() }?.takeIf { ok(it.first, it.second) }
        ?.let { return it }
    Regex("\\[null,null,$NUM,$NUM\\]").find(html)?.let { m -> m.groupValues[1].toDouble() to m.groupValues[2].toDouble() }
        ?.takeIf { ok(it.first, it.second) }?.let { return it }
    Regex("<meta[^>]+content=\"https?://[^\"]*center=$NUM(?:%2C|,)$NUM").find(html)?.let { m ->
      m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }?.takeIf { ok(it.first, it.second) }?.let { return it }
    return null
  }
}
