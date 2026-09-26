package app.navmaster.truck.live

import android.util.Log
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import uniffi.ferrostar.GeographicCoordinate

/** A rectangle of the route ahead: west, south, east, north. */
data class GeoBox(val w: Double, val s: Double, val e: Double, val n: Double) {
  fun csv() = "${f(w)},${f(s)},${f(e)},${f(n)}"

  private fun f(v: Double) = String.format(java.util.Locale.ROOT, "%.4f", v)
}

/**
 * Official traffic information, from free sources:
 * - Autobahn GmbH (Germany): open data, no key, every motorway (warnings, queues, closures);
 * - TomTom Traffic and HERE Traffic: all of Europe, with a free key the driver creates (free tier:
 *   2,500 TomTom requests a day, far more than a day of driving needs).
 * No Waze data, ever.
 */
object TrafficFeeds {
  private const val TAG = "NavMasterLive"
  private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).addInterceptor(TomTomGuard).build()
  private val json = Json { ignoreUnknownKeys = true }

  private fun get(url: String): String? =
      client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/1.0 (truck navigation)").build()).execute().use {
        if (it.isSuccessful) it.body.string() else {
          Log.w(TAG, "HTTP ${it.code} ${url.substringBefore('?')}")
          null
        }
      }

  private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() }

  private fun time(s: String?): Long = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()

  /** GeoJSON coordinates ([lon, lat] or a list of them) as points. */
  private fun coords(e: JsonElement?): List<GeographicCoordinate> {
    val arr = e as? JsonArray ?: return emptyList()
    if (arr.isEmpty()) return emptyList()
    if (arr[0] is JsonPrimitive) {
      val lon = num(arr.getOrNull(0)) ?: return emptyList()
      val lat = num(arr.getOrNull(1)) ?: return emptyList()
      return listOf(GeographicCoordinate(lat, lon))
    }
    return arr.flatMap { coords(it) }
  }

  // ------------------------------------------------------------------------------ Autobahn

  /** Warnings and closures of the German motorways the route uses ("A8", "A93"). */
  fun autobahn(roads: Collection<String>): List<LiveEvent> {
    val out = mutableListOf<LiveEvent>()
    for (road in roads.take(8)) {
      for (service in listOf("warning", "closure")) {
        val text = runCatching { get("https://verkehr.autobahn.de/o/autobahn/$road/services/$service") }.getOrNull() ?: continue
        val list = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.get(service) as? JsonArray ?: continue
        for (item in list) {
          val o = item as? JsonObject ?: continue
          if ((o["future"] as? JsonPrimitive)?.booleanOrNull == true) continue
          val c = o["coordinate"] as? JsonObject
          val lat = num(c?.get("lat")) ?: continue
          val lon = num(c?.get("long")) ?: continue
          val line = coords((o["geometry"] as? JsonObject)?.get("coordinates"))
          val desc = (o["description"] as? JsonArray)?.mapNotNull { str(it) }?.filter { it.length > 2 }?.take(4)?.joinToString(" · ")
          val title = str(o["title"]) ?: road
          val traffic = str(o["abnormalTrafficType"])
          val delay = num(o["delayTimeValue"])?.let { (it * 60).toInt() } ?: 0
          val kind = when {
            service == "closure" -> LiveKind.CLOSED
            traffic != null || delay > 0 -> LiveKind.JAM
            (desc ?: "").contains("Unfall", true) -> LiveKind.ACCIDENT
            (desc ?: "").contains("Baustelle", true) -> LiveKind.ROADWORKS
            else -> LiveKind.HAZARD
          }
          out += LiveEvent(
              id = "ab:" + (str(o["identifier"]) ?: "$lat,$lon"), source = "Autobahn GmbH", kind = kind,
              title = title, detail = listOfNotNull(str(o["subtitle"]), desc).joinToString(" · ").ifBlank { null },
              lat = lat, lon = lon, line = line, timeMs = System.currentTimeMillis(), delayS = delay, official = true)
        }
      }
    }
    return out
  }

  // ------------------------------------------------------------------------------ TomTom

  /** Zoom of the incident tiles read along the route (a tile is about 7 × 10 km in Europe). */
  const val INCIDENT_ZOOM = 12

  private const val INCIDENT_TAGS = "[icon_category,description,delay,magnitude,id,road_category,end_date]"

  /**
   * The incidents of one TomTom vector tile (counted as a tile, the large free allowance), or null
   * when it was not asked (budget, no network): then the old answer is kept.
   */
  fun tomtomTile(key: String, z: Int, x: Int, y: Int): List<LiveEvent>? {
    val url = "https://api.tomtom.com/traffic/map/4/tile/incidents/$z/$x/$y.pbf?key=${enc(key)}&t=-1" +
        "&tags=${enc(INCIDENT_TAGS)}&language=it-IT"
    val bytes = (try {
      client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/1.0 (truck navigation)").build()).execute().use {
        when {
          it.code == 204 -> null
          it.isSuccessful -> it.body.bytes()
          else -> null.also { _ -> Log.w(TAG, "TomTom tile HTTP ${it.code}") }
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "TomTom tile: $e")
      null
    }) ?: return null
    val features = runCatching { Mvt.decode(bytes, z, x, y) }.getOrElse { Log.w(TAG, "TomTom tile decode: $it"); return null }
    // the stretches (queues, closures) by incident id, the points carry the description
    val lines = HashMap<String, MutableList<GeographicCoordinate>>()
    for (f in features) {
      if (f.type != 2) continue
      val id = f.props["id"]?.toString() ?: continue
      val pts = f.lines.flatten()
      lines.getOrPut(id) { mutableListOf() }.addAll(pts)
    }
    val out = mutableListOf<LiveEvent>()
    for (f in features) {
      if (f.type != 1) continue
      val p = f.lines.firstOrNull()?.firstOrNull() ?: continue
      val cat = (f.props["icon_category"] as? Number)?.toInt() ?: 0
      if (cat == 13) continue // a cluster of several incidents: the closer tiles have them one by one
      val kind = kindOf(cat)
      val id = f.props["id"]?.toString()
      val desc = (f.props["description"] as? String)?.takeIf { it.isNotBlank() }
      val delay = (f.props["delay"] as? Number)?.toInt() ?: 0
      val line = id?.let { lines[it] }?.takeIf { it.size >= 2 } ?: emptyList()
      out += LiveEvent(
          id = "tt:" + (id ?: "${p.lat},${p.lng}"), source = "TomTom Traffic", kind = kind,
          title = desc ?: kind.label, detail = null, lat = p.lat, lon = p.lng, line = line,
          timeMs = System.currentTimeMillis(), delayS = if (kind == LiveKind.CLOSED) 0 else delay, official = true)
    }
    return out
  }

  private fun kindOf(cat: Int): LiveKind = when (cat) {
    1 -> LiveKind.ACCIDENT
    2, 4, 5, 10, 11 -> LiveKind.WEATHER
    6 -> LiveKind.JAM
    8 -> LiveKind.CLOSED
    9 -> LiveKind.ROADWORKS
    14 -> LiveKind.BROKEN_VEHICLE
    else -> LiveKind.HAZARD
  }

  private const val TOMTOM_FIELDS =
      "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,events{description,code},startTime,from,to,delay,roadNumbers}}}"

  fun tomtom(key: String, boxes: List<GeoBox>): List<LiveEvent> {
    val out = mutableListOf<LiveEvent>()
    for (b in boxes) {
      val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=${enc(key)}&bbox=${b.csv()}" +
          "&fields=${enc(TOMTOM_FIELDS)}&language=it-IT&timeValidityFilter=present"
      val text = get(url) ?: continue
      val list = (json.parseToJsonElement(text) as? JsonObject)?.get("incidents") as? JsonArray ?: continue
      for (item in list) {
        val o = item as? JsonObject ?: continue
        val p = o["properties"] as? JsonObject ?: continue
        val pts = coords((o["geometry"] as? JsonObject)?.get("coordinates"))
        val first = pts.firstOrNull() ?: continue
        val cat = (p["iconCategory"] as? JsonPrimitive)?.intOrNull ?: 0
        val kind = when (cat) {
          1 -> LiveKind.ACCIDENT
          2, 4, 5, 10, 11 -> LiveKind.WEATHER
          6 -> LiveKind.JAM
          8 -> LiveKind.CLOSED
          9 -> LiveKind.ROADWORKS
          14 -> LiveKind.BROKEN_VEHICLE
          else -> LiveKind.HAZARD
        }
        val events = (p["events"] as? JsonArray)?.mapNotNull { str((it as? JsonObject)?.get("description")) } ?: emptyList()
        val roads = (p["roadNumbers"] as? JsonArray)?.mapNotNull { str(it) } ?: emptyList()
        val where = listOfNotNull(str(p["from"])?.let { "da $it" }, str(p["to"])?.let { "a $it" }).joinToString(" ")
        out += LiveEvent(
            id = "tt:" + (str(p["id"]) ?: "${first.lat},${first.lng}"), source = "TomTom Traffic", kind = kind,
            title = (roads.joinToString(" ") + " " + (events.firstOrNull() ?: kind.label)).trim(),
            detail = (events.drop(1) + listOf(where)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
            lat = first.lat, lon = first.lng, line = if (pts.size >= 2) pts else emptyList(),
            timeMs = time(str(p["startTime"])), delayS = num(p["delay"])?.toInt() ?: 0, official = true)
      }
    }
    return out
  }

  /** TomTom's traffic flow as map tiles (coloured lines over the roads), with the driver's key. */
  @Suppress("UNUSED_PARAMETER")
  fun tomtomFlowTiles(key: String, night: Boolean): String =
      // vector tiles, only the slowed roads ("relative-delay"): the map draws them sharp at any zoom
      // from zoom 12 tiles, so a handful of requests covers the whole trip (see MapStyles)
      "https://api.tomtom.com/traffic/map/4/tile/flow/relative-delay/{z}/{x}/{y}.pbf?key=${enc(key)}"

  // ------------------------------------------------------------------------------ HERE

  fun here(key: String, boxes: List<GeoBox>): List<LiveEvent> {
    val out = mutableListOf<LiveEvent>()
    for (b in boxes) {
      val url = "https://data.traffic.hereapi.com/v7/incidents?in=bbox:${b.csv()}&locationReferencing=shape&lang=it-IT&apiKey=${enc(key)}"
      val text = get(url) ?: continue
      val list = (json.parseToJsonElement(text) as? JsonObject)?.get("results") as? JsonArray ?: continue
      for (item in list) {
        val o = item as? JsonObject ?: continue
        val d = o["incidentDetails"] as? JsonObject ?: continue
        val links = (((o["location"] as? JsonObject)?.get("shape") as? JsonObject)?.get("links") as? JsonArray) ?: JsonArray(emptyList())
        val pts = links.flatMap { l ->
          ((l as? JsonObject)?.get("points") as? JsonArray)?.mapNotNull { pt ->
            val po = pt as? JsonObject ?: return@mapNotNull null
            val la = num(po["lat"]) ?: return@mapNotNull null
            val lo = num(po["lng"]) ?: return@mapNotNull null
            GeographicCoordinate(la, lo)
          } ?: emptyList()
        }
        val first = pts.firstOrNull() ?: continue
        val closed = (d["roadClosed"] as? JsonPrimitive)?.booleanOrNull == true
        val kind = if (closed) LiveKind.CLOSED else when (str(d["type"])) {
          "accident" -> LiveKind.ACCIDENT
          "congestion" -> LiveKind.JAM
          "construction" -> LiveKind.ROADWORKS
          "disabledVehicle" -> LiveKind.BROKEN_VEHICLE
          "weather" -> LiveKind.WEATHER
          "roadClosure" -> LiveKind.CLOSED
          else -> LiveKind.HAZARD
        }
        val summary = str((d["summary"] as? JsonObject)?.get("value"))
        val desc = str((d["description"] as? JsonObject)?.get("value"))
        out += LiveEvent(
            id = "here:" + (str(d["id"]) ?: "${first.lat},${first.lng}"), source = "HERE Traffic", kind = kind,
            title = summary ?: desc ?: kind.label, detail = desc?.takeIf { it != summary },
            lat = first.lat, lon = first.lng, line = if (pts.size >= 2) pts else emptyList(),
            timeMs = time(str(d["startTime"])), official = true)
      }
    }
    return out
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
