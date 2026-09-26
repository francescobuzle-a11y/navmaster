package app.navmaster.truck.live

import android.util.Log
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import uniffi.ferrostar.GeographicCoordinate

/**
 * The official traffic information of the European countries (their national access points, open
 * data), read by the tablet itself, only for the countries the road ahead crosses:
 *
 * - Spain: DGT (DATEX II 3, whole country but Catalonia and the Basque Country), Servei Català de
 *   Trànsit (Catalonia), Open Data Euskadi (Basque Country);
 * - Netherlands: NDW (DATEX II 3, every road manager, updated every minute);
 * - Belgium: Vlaams Verkeerscentrum (DATEX II 3, Flanders and the Brussels ring, Lambert 72);
 * - Luxembourg: CITA (DATEX II 3); France: Bison Futé (DATEX II 2, national non-toll roads);
 * - Germany: Autobahn GmbH (read by [TrafficFeeds.autobahn]); Finland: Fintraffic Digitraffic;
 * - Poland: GDDKiA (national roads, with the weight, width and height limits of the roadworks);
 * - Lithuania: Via Lietuva (roadworks, weak bridges, limits);
 * - Sweden: Trafikverket, with the free key the driver asks for on its site.
 *
 * Each file is asked at most every few minutes (it changes about every minute at the source, and
 * all are compressed: 20 - 550 kB) and only while driving; what is outside the road ahead is dropped.
 */
object NationalFeeds {
  private const val TAG = "NavMasterLive"
  private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  enum class Format { DATEX, DATEX_LAMBERT72, CATALONIA, EUSKADI, DIGITRAFFIC, POLAND, LITHUANIA, SWEDEN }

  data class Feed(
      val id: String,
      val country: String,
      /** Shown to the driver next to the event. */
      val source: String,
      val url: String,
      val format: Format,
      /** The area the feed covers (a rough box of the country or region). */
      val area: GeoBox,
      val everyMin: Int,
      val needsKey: Boolean = false,
  )

  val FEEDS = listOf(
      Feed("es-dgt", "ES", "DGT", "https://nap.dgt.es/datex2/v3/dgt/SituationPublication/datex2_v36.xml", Format.DATEX,
          GeoBox(-9.5, 35.8, 4.4, 43.9), 5),
      Feed("es-cat", "ES", "Trànsit Catalunya", "https://www.gencat.cat/transit/opendata/incidenciesGML.xml", Format.CATALONIA,
          GeoBox(0.15, 40.5, 3.35, 42.9), 10),
      Feed("es-eus", "ES", "Trafikoa Euskadi", "https://api.euskadi.eus/traffic/v1.0/incidences/byDate/", Format.EUSKADI,
          GeoBox(-3.5, 42.45, -1.7, 43.5), 10),
      Feed("nl-ndw", "NL", "NDW", "https://opendata.ndw.nu/actueel_beeld.xml.gz", Format.DATEX,
          GeoBox(3.3, 50.7, 7.3, 53.6), 5),
      Feed("be-vl", "BE", "Verkeerscentrum", "https://www.verkeerscentrum.be/uitwisseling/datex2v3", Format.DATEX_LAMBERT72,
          GeoBox(2.5, 50.65, 5.95, 51.55), 5),
      Feed("lu-cita", "LU", "CITA", "https://cita.lu/info_trafic/datex/situationrecord36", Format.DATEX,
          GeoBox(5.7, 49.4, 6.55, 50.2), 5),
      Feed("fr-bf", "FR", "Bison Futé", "https://tipi.bison-fute.gouv.fr/bison-fute-ouvert/publicationsDIR/Evenementiel-DIR/grt/RRN/content.xml",
          Format.DATEX, GeoBox(-5.2, 41.3, 9.6, 51.1), 10),
      Feed("fi-dt", "FI", "Fintraffic", "https://tie.digitraffic.fi/api/traffic-message/v1/messages", Format.DIGITRAFFIC,
          GeoBox(19.0, 59.7, 31.6, 70.1), 10),
      Feed("pl-gddkia", "PL", "GDDKiA", "https://www.archiwum.gddkia.gov.pl/dane/zima_html/utrdane.xml", Format.POLAND,
          GeoBox(14.1, 49.0, 24.2, 54.9), 15),
      Feed("lt-via", "LT", "Via Lietuva", "https://restrictions.eismoinfo.lt/", Format.LITHUANIA,
          GeoBox(20.9, 53.9, 26.9, 56.5), 15),
      Feed("se-tv", "SE", "Trafikverket", "https://api.trafikinfo.trafikverket.se/v2/data.json", Format.SWEDEN,
          GeoBox(10.9, 55.3, 24.2, 69.1), 5, needsKey = true),
  )

  /** Countries with official information read directly (Germany through the Autobahn API). */
  val COUNTRIES = FEEDS.map { it.country }.toSortedSet() + "DE"

  private class Cached(val at: Long, val events: List<LiveEvent>)

  /** What is read is kept for a wider area than the road ahead (it moves on between two reads). */
  private fun grow(b: GeoBox, d: Double) = GeoBox(b.w - d * 1.5, b.s - d, b.e + d * 1.5, b.n + d)

  private val cache = HashMap<String, Cached>()

  /** The feeds that cover some of [boxes]. */
  fun feedsFor(boxes: List<GeoBox>, swedenKey: String): List<Feed> =
      FEEDS.filter { f -> (!f.needsKey || swedenKey.isNotBlank()) && boxes.any { overlaps(it, f.area) } }

  private fun overlaps(a: GeoBox, b: GeoBox) = a.w <= b.e && a.e >= b.w && a.s <= b.n && a.n >= b.s

  /**
   * The events of the countries on the road ahead ([boxes]). A feed is asked again only after its
   * interval (or when the road ahead left the area read last time); [moving] false: only what was
   * read before. Returns the events and, per source, how many.
   */
  fun read(boxes: List<GeoBox>, swedenKey: String, moving: Boolean): Pair<List<LiveEvent>, List<String>> {
    val now = System.currentTimeMillis()
    val wanted = feedsFor(boxes, swedenKey)
    cache.keys.retainAll(wanted.map { it.id }.toSet())
    for (f in wanted) {
      val old = cache[f.id]
      val stale = old == null || now - old.at > f.everyMin * 60_000L
      if (!stale || (!moving && old != null)) continue
      val wide = boxes.map { grow(it, 1.0) }
      val got = runCatching { fetch(f, wide, swedenKey, now) }.onFailure { Log.w(TAG, "${f.id}: $it") }.getOrNull()
      if (got != null) {
        cache[f.id] = Cached(now, got)
        Log.i(TAG, "national ${f.id}: ${got.size} events near the route")
      } else if (old == null) cache[f.id] = Cached(now, emptyList())
    }
    // the events of the road ahead, from what was read
    val events = wanted.flatMap { f -> (cache[f.id]?.events ?: emptyList()).filter { e -> boxes.any { Datex.inBox(e, it) } } }
    val counts = wanted.map { f -> "${f.source} ${events.count { it.id.startsWith(f.id + ":") }}" }
    return events to counts
  }

  private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
    val b = Request.Builder().url(url).header("User-Agent", "NavMaster/1.0 (truck navigation; Android)")
    for ((k, v) in headers) b.header(k, v)
    return client.newCall(b.build()).execute().use { r ->
      if (!r.isSuccessful) {
        Log.w(TAG, "HTTP ${r.code} ${url.substringBefore('?')}")
        null
      } else if (url.endsWith(".gz")) {
        java.util.zip.GZIPInputStream(r.body.byteStream()).bufferedReader().readText()
      } else r.body.string()
    }
  }

  /** One feed, kept to [boxes] (null: everything, for the tests). */
  fun fetch(f: Feed, boxes: List<GeoBox>?, swedenKey: String, now: Long = System.currentTimeMillis()): List<LiveEvent>? {
    val out = when (f.format) {
      Format.DATEX -> get(f.url)?.let { Datex.parse(StringReader(it), f.source, f.id, boxes, Datex.Crs.WGS84, now) }
      Format.DATEX_LAMBERT72 -> get(f.url)?.let { Datex.parse(StringReader(it), f.source, f.id, boxes, Datex.Crs.LAMBERT72, now) }
      Format.CATALONIA -> get(f.url)?.let { catalonia(it, f) }
      Format.EUSKADI -> euskadi(f, now)
      Format.DIGITRAFFIC -> digitraffic(f, now)
      Format.POLAND -> get(f.url)?.let { poland(it, f, now) }
      Format.LITHUANIA -> get(f.url)?.let { lithuania(it, f, now) }
      Format.SWEDEN -> if (swedenKey.isBlank() || boxes == null) null else sweden(f, swedenKey, boxes, now)
    } ?: return null
    return if (boxes == null) out else out.filter { e -> boxes.any { Datex.inBox(e, it) } }
  }

  // ------------------------------------------------------------------------------ helpers

  private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

  private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() }

  private fun iso(s: String?): Long? = s?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
    runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
        // "2026-09-26T18:38:01+0200"
        ?: runCatching { OffsetDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant().toEpochMilli() }.getOrNull()
        // local time without zone (Euskadi): Madrid time
        ?: runCatching { LocalDateTime.parse(if (t.length == 16) "$t:00" else t).atZone(ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli() }.getOrNull()
  }

  private fun ev(f: Feed, id: String, kind: LiveKind, road: String?, detail: String?, lat: Double, lon: Double,
                 line: List<GeographicCoordinate> = emptyList(), start: Long?, now: Long, bothWays: Boolean = true) =
      LiveEvent(id = "${f.id}:$id", source = f.source, kind = kind, title = kind.label + (road?.let { " · $it" } ?: ""),
          detail = detail, lat = lat, lon = lon, line = line, timeMs = start ?: now, official = true, bothWays = bothWays)

  private fun lower(s: String?) = s?.lowercase(Locale.ROOT) ?: ""

  // ------------------------------------------------------------------------------ Catalonia (GML)

  private fun catalonia(xml: String, f: Feed): List<LiveEvent> {
    val p = XmlPullParserFactory.newInstance().newPullParser()
    p.setInput(StringReader(xml))
    val out = mutableListOf<LiveEvent>()
    var cur: HashMap<String, String>? = null
    var tag = ""
    val now = System.currentTimeMillis()
    while (p.next() != XmlPullParser.END_DOCUMENT) {
      when (p.eventType) {
        XmlPullParser.START_TAG -> {
          tag = p.name.substringAfter(':')
          if (tag == "featureMember") cur = HashMap()
        }
        XmlPullParser.TEXT -> cur?.let { m -> p.text?.trim()?.takeIf { it.isNotEmpty() }?.let { m[tag] = (m[tag]?.plus(" ") ?: "") + it } }
        XmlPullParser.END_TAG -> if (p.name.substringAfter(':') == "featureMember") {
          val m = cur ?: continue
          cur = null
          val (lon, lat) = m["coordinates"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size >= 2 } ?: continue
          val d = lower(m["descripcio"])
          val t = lower(m["descripcio_tipus"])
          val cause = lower(m["causa"])
          val kind = when {
            "tallada" in d -> LiveKind.CLOSED
            "accident" in t || "accident" in cause -> LiveKind.ACCIDENT
            "retenci" in t || "retenci" in d -> LiveKind.JAM
            "obres" in t -> LiveKind.ROADWORKS
            "meteo" in t || "neu" in cause || "gel" in cause || "boira" in cause -> LiveKind.WEATHER
            else -> LiveKind.HAZARD
          }
          out += ev(f, m["identificador"] ?: "$lat,$lon", kind, m["carretera"], listOfNotNull(m["descripcio"], m["causa"], m["cap_a"]?.let { "verso $it" })
              .joinToString(" · "), lat, lon, start = null, now = now)
        }
      }
    }
    return out
  }

  // ------------------------------------------------------------------------------ Basque Country (JSON)

  private fun euskadi(f: Feed, now: Long): List<LiveEvent>? {
    val day = ZonedDateTime.now(ZoneId.of("Europe/Madrid"))
    val base = f.url + String.format(Locale.ROOT, "%04d/%02d/%02d", day.year, day.monthValue, day.dayOfMonth)
    val out = mutableListOf<LiveEvent>()
    var page = 1
    var pages = 1
    while (page <= pages && page <= 10) {
      val text = get(if (page == 1) base else "$base?_page=$page") ?: return if (out.isEmpty()) null else out
      val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: break
      pages = num(root["totalPages"])?.toInt() ?: 1
      for (item in (root["incidences"] as? JsonArray).orEmpty()) {
        val o = item as? JsonObject ?: continue
        val lat = num(o["latitude"]) ?: continue
        val lon = num(o["longitude"]) ?: continue
        val start = iso(str(o["startDate"]))
        val end = iso(str(o["endDate"]))
        if (end != null && end < now) continue
        val type = lower(str(o["incidenceType"]))
        val cause = lower(str(o["cause"]))
        val kind = when {
          "obra" in type || "obra" in cause -> LiveKind.ROADWORKS
          "accidente" in cause -> LiveKind.ACCIDENT
          "retenci" in type || "retenci" in cause -> LiveKind.JAM
          "avería" in cause || "averia" in cause -> LiveKind.BROKEN_VEHICLE
          "meteo" in type || "nieve" in cause || "hielo" in cause || "niebla" in cause -> LiveKind.WEATHER
          "cerrad" in cause || "corte" in cause -> LiveKind.CLOSED
          else -> LiveKind.HAZARD
        }
        // an incident with no end is kept for a few hours from its start (the feed keeps the day)
        if (end == null && kind != LiveKind.ROADWORKS && start != null && now - start > kind.ttlMin * 60_000L) continue
        out += ev(f, str(o["incidenceId"]) ?: "$lat,$lon", kind, str(o["road"]),
            listOfNotNull(str(o["cause"]), str(o["cityTown"]), str(o["direction"])?.let { "verso $it" }).joinToString(" · "),
            lat, lon, start = start, now = now)
      }
      page++
    }
    return out
  }

  // ------------------------------------------------------------------------------ Finland (GeoJSON)

  private fun geo(e: JsonElement?): List<GeographicCoordinate> {
    val arr = e as? JsonArray ?: return emptyList()
    if (arr.isEmpty()) return emptyList()
    if (arr[0] is JsonPrimitive) {
      val lon = num(arr[0]) ?: return emptyList()
      val lat = num(arr.getOrNull(1)) ?: return emptyList()
      return listOf(GeographicCoordinate(lat, lon))
    }
    return arr.flatMap { geo(it) }
  }

  private fun digitraffic(f: Feed, now: Long): List<LiveEvent>? {
    val out = mutableListOf<LiveEvent>()
    var any = false
    for (type in listOf("TRAFFIC_ANNOUNCEMENT", "ROAD_WORK", "WEIGHT_RESTRICTION")) {
      val text = get("${f.url}?inactiveHours=0&includeAreaGeometry=false&situationType=$type",
          mapOf("Digitraffic-User" to "NavMaster/1.0")) ?: continue
      any = true
      val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
      for (item in (root["features"] as? JsonArray).orEmpty()) {
        val o = item as? JsonObject ?: continue
        val pts = geo((o["geometry"] as? JsonObject)?.get("coordinates"))
        val first = pts.firstOrNull() ?: continue
        val props = o["properties"] as? JsonObject ?: continue
        val ann = (props["announcements"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
        val tad = ann["timeAndDuration"] as? JsonObject
        val end = iso(str(tad?.get("endTime")))
        val start = iso(str(tad?.get("startTime")))
        if (end != null && end < now) continue
        if (start != null && start > now + 30 * 60_000L) continue
        val feats = (ann["features"] as? JsonArray).orEmpty().mapNotNull { str((it as? JsonObject)?.get("name")) }.joinToString(" ").lowercase(Locale.ROOT)
        val annType = lower(str(props["trafficAnnouncementType"]))
        val kind = when {
          type == "WEIGHT_RESTRICTION" -> LiveKind.TRUCK_BAN
          "suljettu" in feats -> LiveKind.CLOSED
          type == "ROAD_WORK" -> LiveKind.ROADWORKS
          "accident" in annType -> LiveKind.ACCIDENT
          "jono" in feats || "ruuh" in feats -> LiveKind.JAM
          "keli" in feats || "liukas" in feats -> LiveKind.WEATHER
          else -> LiveKind.HAZARD
        }
        out += ev(f, str(props["situationId"]) ?: "${first.lat},${first.lng}", kind, null,
            listOfNotNull(str(ann["title"]), str(ann["comment"])).joinToString(" · ").take(200),
            first.lat, first.lng, line = if (pts.size >= 2) pts.take(200) else emptyList(), start = start, now = now)
      }
    }
    return if (any) out else null
  }

  // ------------------------------------------------------------------------------ Poland (GDDKiA XML)

  private fun poland(xml: String, f: Feed, now: Long): List<LiveEvent> {
    val p = XmlPullParserFactory.newInstance().newPullParser()
    p.setInput(StringReader(xml))
    val out = mutableListOf<LiveEvent>()
    var cur: HashMap<String, String>? = null
    var tag = ""
    var n = 0
    while (p.next() != XmlPullParser.END_DOCUMENT) {
      when (p.eventType) {
        XmlPullParser.START_TAG -> {
          tag = p.name
          if (tag == "utr") cur = HashMap()
        }
        XmlPullParser.TEXT -> cur?.let { m -> p.text?.trim()?.takeIf { it.isNotEmpty() }?.let { if (tag !in m) m[tag] = it } }
        XmlPullParser.END_TAG -> if (p.name == "utr") {
          val m = cur ?: continue
          cur = null
          n++
          val lat = m["geo_lat"]?.replace(',', '.')?.toDoubleOrNull() ?: continue
          val lon = m["geo_long"]?.replace(',', '.')?.toDoubleOrNull() ?: continue
          val end = iso(m["data_likwidacji"])
          val start = iso(m["data_powstania"])
          if (end != null && end < now) continue
          if (start != null && start > now + 30 * 60_000L) continue
          val limits = listOfNotNull(
              m["ogr_nosnosc"]?.let { "portata $it t" }, m["ogr_nacisk"]?.let { "asse $it t" },
              m["ogr_skrajnia_pionowa"]?.let { "altezza $it m" }, m["ogr_skrajnia_pozioma"]?.let { "larghezza $it m" },
              m["ogr_szerokosc"]?.let { "larghezza $it m" }, m["ogr_predkosc"]?.let { "$it km/h" })
          val kind = when {
            m["droga_zamknieta"] == "true" -> LiveKind.CLOSED
            m["ogr_nosnosc"] != null || m["ogr_nacisk"] != null || m["ogr_skrajnia_pionowa"] != null || m["awaria_mostu"] == "true" -> LiveKind.TRUCK_BAN
            m["typ"] == "I" -> LiveKind.ACCIDENT
            m["typ"] == "W" -> LiveKind.WEATHER
            else -> LiveKind.ROADWORKS
          }
          out += ev(f, "${m["nr_drogi"]}:${m["km"]}:$n", kind, m["nr_drogi"],
              (listOfNotNull(m["nazwa_odcinka"]) + limits).joinToString(" · "), lat, lon, start = start, now = now)
        }
      }
    }
    return out
  }

  // ------------------------------------------------------------------------------ Lithuania (JSON)

  private fun lithuania(text: String, f: Feed, now: Long): List<LiveEvent> {
    val arr = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
    val out = mutableListOf<LiveEvent>()
    for (item in arr) {
      val o = item as? JsonObject ?: continue
      val end = iso(str(o["endTime"]))
      val start = iso(str(o["startTime"]))
      if (end != null && end < now) continue
      if (start != null && start > now + 30 * 60_000L) continue
      val loc = o["location"] as? JsonObject ?: continue
      val nums = str(loc["polyline"])?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() } ?: continue
      val pts = (0 until nums.size / 2).map { GeographicCoordinate(nums[2 * it], nums[2 * it + 1]) }
      val first = pts.firstOrNull() ?: continue
      val restr = (o["restrictions"] as? JsonArray).orEmpty().mapNotNull { r ->
        val ro = r as? JsonObject ?: return@mapNotNull null
        val t = str(ro["restrictionType"]) ?: return@mapNotNull null
        val v = str(ro["value"])
        when (t) {
          "speedLimit" -> "$v km/h"
          "widthLimit" -> "larghezza $v m"
          "heightLimit" -> "altezza $v m"
          "weightLimit", "axleLoadLimit" -> "peso $v t"
          "roadClosed", "closed" -> "chiusa"
          else -> null
        }
      }
      val sub = lower(str(o["subtype"]))
      val kind = when {
        "closed" in sub || restr.contains("chiusa") -> LiveKind.CLOSED
        "weakbridge" in sub || restr.any { it.startsWith("peso") || it.startsWith("altezza") } -> LiveKind.TRUCK_BAN
        str(o["type"]) == "CONSTRUCTION" -> LiveKind.ROADWORKS
        else -> LiveKind.HAZARD
      }
      out += ev(f, str(o["_id"]) ?: "${first.lat},${first.lng}", kind, str(loc["street"])?.substringBefore(' '),
          (listOfNotNull(str(o["shortDescription"]) ?: str(o["description"]), str(loc["street"])) + restr).joinToString(" · "),
          first.lat, first.lng, line = if (pts.size >= 2) pts.take(200) else emptyList(), start = start, now = now,
          bothWays = str(loc["direction"]) != "POSITIVE" && str(loc["direction"]) != "NEGATIVE")
    }
    return out
  }

  // ------------------------------------------------------------------------------ Sweden (Trafikverket, key)

  private fun sweden(f: Feed, key: String, boxes: List<GeoBox>, now: Long): List<LiveEvent>? {
    val out = mutableListOf<LiveEvent>()
    var any = false
    for (b in boxes.take(4)) {
      val body = """<REQUEST><LOGIN authenticationkey="$key"/><QUERY objecttype="Situation" schemaversion="1.5" limit="500">""" +
          """<FILTER><WITHIN name="Deviation.Geometry.Point.WGS84" shape="box" value="${b.w} ${b.s}, ${b.e} ${b.n}"/></FILTER>""" +
          """</QUERY></REQUEST>"""
      val text = runCatching {
        client.newCall(Request.Builder().url(f.url).post(body.toRequestBody("text/xml".toMediaType()))
            .header("User-Agent", "NavMaster/1.0").build()).execute().use { r ->
          if (r.isSuccessful) r.body.string() else null.also { Log.w(TAG, "Trafikverket HTTP ${r.code}") }
        }
      }.getOrNull() ?: continue
      any = true
      val result = ((json.parseToJsonElement(text) as? JsonObject)?.get("RESPONSE") as? JsonObject)?.get("RESULT") as? JsonArray ?: continue
      for (res in result) {
        for (sit in ((res as? JsonObject)?.get("Situation") as? JsonArray).orEmpty()) {
          for (dev in ((sit as? JsonObject)?.get("Deviation") as? JsonArray).orEmpty()) {
            val d = dev as? JsonObject ?: continue
            val end = iso(str(d["EndTime"]))
            val start = iso(str(d["StartTime"]))
            if (end != null && end < now) continue
            if (start != null && start > now + 30 * 60_000L) continue
            val wkt = str(((d["Geometry"] as? JsonObject)?.get("Point") as? JsonObject)?.get("WGS84")) ?: continue
            val nums = Regex("-?\\d+(\\.\\d+)?").findAll(wkt).map { it.value.toDouble() }.toList()
            if (nums.size < 2) continue
            val lon = nums[0]
            val lat = nums[1]
            val type = lower(str(d["MessageType"]))
            val code = lower(str(d["MessageCode"]))
            val kind = when {
              "olycka" in type -> LiveKind.ACCIDENT
              "avstängd" in code || "avstängning" in code -> LiveKind.CLOSED
              "vägarbete" in type -> LiveKind.ROADWORKS
              "kö" in code || "köbildning" in code -> LiveKind.JAM
              "hinder" in type || "fordon" in code -> LiveKind.HAZARD
              "restriktion" in type -> LiveKind.TRUCK_BAN
              "väglag" in type || "halka" in code -> LiveKind.WEATHER
              else -> null
            } ?: continue
            out += ev(f, str(d["Id"]) ?: "$lat,$lon", kind, str(d["RoadNumber"]),
                listOfNotNull(str(d["Header"]), str(d["Message"])).joinToString(" · ").take(200), lat, lon, start = start, now = now)
          }
        }
      }
    }
    return if (any) out.distinctBy { it.id } else null
  }

  // ------------------------------------------------------------------------------ test (emulator)

  /** Every open feed read whole, with how many events of each kind: for the emulator test log. */
  fun selfTest(): List<String> = FEEDS.filter { !it.needsKey }.map { f ->
    val t0 = System.currentTimeMillis()
    val got = runCatching { fetch(f, null, "") }
    val ev = got.getOrNull()
    val ms = System.currentTimeMillis() - t0
    if (ev == null) "${f.id}: ERROR ${got.exceptionOrNull() ?: "no data"} ($ms ms)"
    else "${f.id}: ${ev.size} events ${ev.groupingBy { it.kind }.eachCount()} in $ms ms; e.g. " +
        ev.take(2).joinToString(" | ") { "${it.title} @${"%.5f".format(Locale.ROOT, it.lat)},${"%.5f".format(Locale.ROOT, it.lon)} line=${it.line.size} ${it.detail?.take(60) ?: ""}" }
  }
}
