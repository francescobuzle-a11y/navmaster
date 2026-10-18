package app.navmaster.truck.ui

import android.content.Context
import app.navmaster.truck.data.InstalledRegion
import app.navmaster.truck.settings.NightMode
import java.io.File
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The map style: the NavMaster style from the assets, with one offline map source per installed
 * country (layers interleaved so that roads of one country are not hidden by the fields of the
 * next), and optionally the satellite view under roads and names (hybrid).
 */
object MapStyles {
  const val ESRI_IMAGERY = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
  const val ESRI_ATTRIBUTION = "Immagini © Esri, Maxar, Earthstar Geographics"
  private val json = Json { ignoreUnknownKeys = true }

  fun isNight(mode: NightMode, now: LocalTime = LocalTime.now()): Boolean =
      when (mode) {
        NightMode.DAY -> false
        NightMode.NIGHT -> true
        NightMode.AUTO -> now.hour >= 20 || now.hour < 7
      }

  /**
   * The map from the internet where no map is downloaded: OpenFreeMap (free, no key, no limit on
   * requests; the same OpenMapTiles schema as the downloaded maps, so the same style draws it).
   */
  const val ONLINE_TILES = "https://tiles.openfreemap.org/planet"
  const val ONLINE_ATTRIBUTION = "OpenFreeMap © OpenMapTiles · Dati © OpenStreetMap contributors"

  /**
   * [traffic]: tile address of the traffic colours drawn over the roads (TomTom, with the driver's key).
   * [online]: the map from the internet too, under the downloaded ones (where the vehicle or the
   * trip is in a country not downloaded).
   */
  fun styleUri(context: Context, regions: List<InstalledRegion>, night: Boolean, satellite: Boolean, traffic: String? = null, online: Boolean = false): String {
    val maps = regions.filter { it.map.exists() }
    val name = "style-${if (night) "n" else "d"}${if (satellite) "s" else ""}${if (traffic != null) "t" else ""}${if (online) "o" else ""}.json"
    val out = File(context.filesDir, name)
    val text = if (maps.isEmpty() && !satellite && !online) EMPTY.replace("{BG}", if (night) "#1B1F24" else "#F2EFE9")
        else build(context, maps, night, satellite, traffic, online)
    if (!out.exists() || out.readText() != text) out.writeText(text)
    return "file://" + out.absolutePath
  }

  private fun build(context: Context, maps: List<InstalledRegion>, night: Boolean, satellite: Boolean, traffic: String?, online: Boolean = false): String {
    val asset = if (night) "style/style-night.json" else "style/style-day.json"
    val base = json.parseToJsonElement(context.assets.open(asset).bufferedReader().use { it.readText() }).jsonObject
    val omt = base["sources"]?.jsonObject?.get("omt")?.jsonObject
    val layers = base["layers"]?.jsonArray ?: JsonArray(emptyList())
    return buildJsonObject {
      for ((k, v) in base) if (k != "sources" && k != "layers") put(k, v)
      putJsonObject("sources") {
        maps.forEachIndexed { i, r ->
          if (omt != null) put("omt$i", JsonObject(omt + ("url" to JsonPrimitive("pmtiles://file://" + r.map.absolutePath))))
        }
        if (online && omt != null) {
          put("online", JsonObject(omt + mapOf("url" to JsonPrimitive(ONLINE_TILES), "attribution" to JsonPrimitive(ONLINE_ATTRIBUTION))))
        }
        if (satellite) {
          putJsonObject("sat") {
            put("type", "raster")
            putJsonArray("tiles") { add(JsonPrimitive(ESRI_IMAGERY)) }
            put("tileSize", 256)
            put("maxzoom", 19)
            put("attribution", ESRI_ATTRIBUTION)
          }
        }
        put("nm-empty", json.parseToJsonElement(EMPTY_SOURCE))
        if (traffic != null) {
          putJsonObject("traffic") {
            put("type", "vector")
            putJsonArray("tiles") { add(JsonPrimitive(traffic)) }
            put("minzoom", 8)
            // zoom 12 tiles drawn also at the closer zooms (vector lines stay sharp): a few
            // requests cover the whole view and the free allowance lasts
            put("maxzoom", 12)
            put("attribution", "Traffico © TomTom")
          }
        }
      }
      putJsonArray("layers") {
        var trafficAdded = traffic == null
        var slotAdded = false
        for (l in layers) {
          val layer = l.jsonObject
          val type = layer["type"]?.jsonPrimitive?.contentOrNull
          val src = layer["source"]?.jsonPrimitive?.contentOrNull
          // the traffic colours and the routes over the roads, UNDER the names (of roads, towns,
          // motorways): the route lines of the app hang below ROUTES_SLOT (Anchor.Below)
          if (!slotAdded && type == "symbol") {
            slotAdded = true
            if (!trafficAdded) {
              trafficAdded = true
              add(json.parseToJsonElement(TRAFFIC_LAYER))
            }
            add(json.parseToJsonElement(SLOT_LAYER))
          }
          if (src == null) {
            add(layer)
            if (type == "background" && satellite) {
              add(buildJsonObject {
                put("id", "satellite")
                put("type", "raster")
                put("source", "sat")
              })
            }
            continue
          }
          // on the satellite view the imagery replaces fields, water and buildings
          if (satellite && (type == "fill" || type == "fill-extrusion")) continue
          // the map from the internet first: the downloaded ones are drawn over it
          if (online && omt != null) {
            add(JsonObject(layer + mapOf("id" to JsonPrimitive("${layer["id"]?.jsonPrimitive?.contentOrNull}-online"), "source" to JsonPrimitive("online"))))
          }
          maps.forEachIndexed { i, _ ->
            add(JsonObject(layer + mapOf("id" to JsonPrimitive("${layer["id"]?.jsonPrimitive?.contentOrNull}-$i"), "source" to JsonPrimitive("omt$i"))))
          }
        }
        if (!slotAdded) add(json.parseToJsonElement(SLOT_LAYER))
      }
    }.toString()
  }

  /** The layer the app's route lines are drawn below: under the names, over the roads. */
  const val ROUTES_SLOT = "nm-routes-slot"
  private const val EMPTY_SOURCE = """{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}"""
  private const val SLOT_LAYER = """{"id":"$ROUTES_SLOT","type":"line","source":"nm-empty","paint":{"line-opacity":0}}"""

  /** Satellite only, for the small views of a difficult point (no offline map needed). */
  fun satelliteUri(context: Context): String {
    val out = File(context.filesDir, "style-satellite.json")
    val text =
        """{"version":8,"name":"Satellite","sources":{"sat":{"type":"raster","tiles":["$ESRI_IMAGERY"],"tileSize":256,"maxzoom":19,""" +
            """"attribution":"$ESRI_ATTRIBUTION"}},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#20262C"}},""" +
            """{"id":"sat","type":"raster","source":"sat"}]}"""
    if (!out.exists() || out.readText() != text) out.writeText(text)
    return "file://" + out.absolutePath
  }

  /** Slowed roads: yellow a little, orange slow, red very slow, dark red nearly stopped; beside the road, on its side. */
  private const val TRAFFIC_LAYER =
      """{"id":"traffic","type":"line","source":"traffic","source-layer":"Traffic flow",""" +
          """"filter":["<",["get","traffic_level"],0.85],"layout":{"line-cap":"round","line-join":"round"},""" +
          """"paint":{"line-color":["interpolate",["linear"],["get","traffic_level"],0.0,"#7F0000",0.25,"#D50000",0.5,"#FF6D00",0.75,"#FFC400"],""" +
          """"line-width":["interpolate",["linear"],["zoom"],9,2,14,5,18,9],"line-offset":["interpolate",["linear"],["zoom"],9,1,14,4,18,8],""" +
          """"line-opacity":0.9}}"""

  private const val EMPTY =
      """{"version":8,"name":"NavMaster vuota","sources":{"nm-empty":$EMPTY_SOURCE},"layers":[{"id":"bg","type":"background","paint":{"background-color":"{BG}"}},$SLOT_LAYER]}"""
}
