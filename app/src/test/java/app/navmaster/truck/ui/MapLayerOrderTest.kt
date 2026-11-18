package app.navmaster.truck.ui

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names of roads, motorways (shields with the ref) and places over the blue route: the app's
 * route lines hang below MapStyles.ROUTES_SLOT (UnderLabels), so the slot must come before every
 * layer of names and after every layer of the ground (roads, tunnels, bridges), by day and by night,
 * with one or more map files, the satellite, the traffic and the map from the internet. Pure Kotlin
 * on the styles of the assets (the map itself cannot be drawn in a unit test).
 */
class MapLayerOrderTest {
  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun slotBeforeTheFirstLayerOfNames() {
    assertEquals(3, MapStyles.routesSlotIndex(listOf("background", "fill", "line", "symbol", "line", "symbol")))
    // a circle (a point) is not a name: the slot stays before the first symbol
    assertEquals(2, MapStyles.routesSlotIndex(listOf("background", "line", "symbol", "circle", "symbol")))
    assertEquals(0, MapStyles.routesSlotIndex(listOf("symbol", "line")))
    // no names at all (a style of satellite only, an empty one): at the end, over everything
    assertEquals(3, MapStyles.routesSlotIndex(listOf("background", "raster", "line")))
    assertEquals(0, MapStyles.routesSlotIndex(emptyList()))
    assertEquals(2, MapStyles.routesSlotIndex(listOf("background", null, "symbol")))
  }

  @Test
  fun styleWithoutNamesHasTheSlotLast() {
    val base = """{"version":8,"sources":{"omt":{"type":"vector"}},"layers":[{"id":"bg","type":"background"},""" +
        """{"id":"road","type":"line","source":"omt","source-layer":"transportation"}]}"""
    val ids = layers(MapStyles.compose(base, listOf("/a.pmtiles"), satellite = false, traffic = "https://t/{z}/{x}/{y}")).map { id(it) }
    assertEquals(listOf("bg", "road-0", "traffic", MapStyles.ROUTES_SLOT), ids)
  }

  @Test
  fun namesOverTheRoutesDayAndNight() {
    for (night in listOf(false, true)) {
      val base = asset(if (night) "style-night.json" else "style-day.json")
      for (maps in listOf(listOf("/m/it.pmtiles"), listOf("/m/it.pmtiles", "/m/at.pmtiles", "/m/pl.pmtiles")))
        for (satellite in listOf(false, true))
          for (traffic in listOf(null, "https://traffic/{z}/{x}/{y}.pbf"))
            for (online in listOf(false, true)) {
              val what = "night=$night maps=${maps.size} satellite=$satellite traffic=${traffic != null} online=$online"
              val ls = layers(MapStyles.compose(base, maps, satellite, traffic, online))
              val slots = ls.indices.filter { id(ls[it]) == MapStyles.ROUTES_SLOT }
              assertEquals(what, 1, slots.size)
              val slot = slots[0]
              for ((i, l) in ls.withIndex()) {
                if (i == slot) continue
                val type = type(l)
                val hasText = l["layout"]?.jsonObject?.containsKey("text-field") == true
                // every name (road, shield, place, river, one-way arrow) over the routes
                if (type == "symbol" || hasText) assertTrue("$what: ${id(l)} under the routes", i > slot)
                // the ground (fields, water, roads, tunnels, bridges, satellite, traffic) under them
                if (type in setOf("background", "fill", "fill-extrusion", "line", "raster")) assertTrue("$what: ${id(l)} over the routes", i < slot)
              }
              // the traffic just under the routes (and over the roads)
              if (traffic != null) assertEquals(what, "traffic", id(ls[slot - 1]))
              // the names that matter while driving, of every map file, over the routes
              val ids = ls.map { id(it) }
              for (k in maps.indices) {
                for (name in listOf("road-name-major", "road-name-minor", "road-shield-motorway", "road-shield-national", "road-shield-secondary",
                    "place-village", "place-town", "place-city", "place-suburb"))
                  assertTrue("$what: $name-$k", ids.indexOf("$name-$k") > slot)
                for (road in listOf("road-motorway-$k", "bridge-motorway-$k", "tunnel-motorway-$k", "road-minor-$k"))
                  assertTrue("$what: $road", ids.indexOf(road) in 0 until slot)
              }
            }
    }
  }

  /** The names of roads and places drawn over the blue line keep a halo wide enough to be read on it. */
  @Test
  fun namesHaveAHaloOverTheBlueLine() {
    for (file in listOf("style-day.json", "style-night.json")) {
      val ls = json.parseToJsonElement(asset(file)).jsonObject["layers"]!!.jsonArray.map { it.jsonObject }
      for (l in ls) {
        val name = id(l)
        if (!(name.startsWith("road-name") || name.startsWith("place-"))) continue
        val paint = l["paint"]?.jsonObject
        val halo = paint?.get("text-halo-color")?.jsonPrimitive?.contentOrNull
        val width = paint?.get("text-halo-width")?.jsonPrimitive?.doubleOrNull ?: 0.0
        assertTrue("$file $name: no halo", halo != null)
        assertTrue("$file $name: halo $width too thin", width >= 1.5)
        assertTrue("$file $name: halo as the text", halo != paint?.get("text-color")?.jsonPrimitive?.contentOrNull)
      }
      // the shields are drawn on their own sign (icon fitted to the text)
      for (l in ls.filter { id(it).startsWith("road-shield") }) {
        val layout = l["layout"]!!.jsonObject
        assertTrue("$file ${id(l)}", layout["icon-image"] != null && layout["icon-text-fit"]?.jsonPrimitive?.contentOrNull == "both")
      }
    }
  }

  private fun layers(style: String): List<JsonObject> = json.parseToJsonElement(style).jsonObject["layers"]!!.jsonArray.map { it.jsonObject }
  private fun id(l: JsonObject) = l["id"]?.jsonPrimitive?.contentOrNull ?: ""
  private fun type(l: JsonObject) = l["type"]?.jsonPrimitive?.contentOrNull

  private fun asset(name: String): String =
      listOf("src/main/assets/style/$name", "app/src/main/assets/style/$name").map(::File).first { it.exists() }.readText()
}
