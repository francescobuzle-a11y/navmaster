package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.core.Geo
import app.navmaster.truck.vehicle.VehicleProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route

/** One road of the route as the graph knows it, with where it is along the route. */
data class EdgeInfo(
    val startM: Double,
    val endM: Double,
    val roadClass: String,
    val use: String,
    val toll: Boolean,
    val surface: String,
    val lanes: Int,
    val wayId: Long,
    val name: String?,
    val country: String?,
    val tunnel: Boolean,
    val bridge: Boolean,
    val maxUpGrade: Double?,
    val maxDownGrade: Double?,
) {
  val isRamp: Boolean
    get() = use == "ramp"

  val isMajor: Boolean
    get() = roadClass in setOf("motorway", "trunk", "primary")
}

/** A stretch of the route (metres from the start). */
data class Span(val startM: Double, val endM: Double) {
  val length: Double
    get() = endM - startM
}

/**
 * What the route is made of: toll stretches, exit ramps, countries crossed, surface, road classes.
 * Read with Valhalla's trace_attributes on the route's own shape, on the tablet.
 */
class RouteAnalysis(val route: Route, val edges: List<EdgeInfo>) {
  val cum: DoubleArray = Geo.cumulative(route.geometry)
  val length: Double
    get() = cum.lastOrNull() ?: 0.0

  val durationS: Double = route.steps.sumOf { it.duration }

  val tolls: List<Span> = merge(edges.filter { it.toll })
  val tollKm: Double
    get() = tolls.sumOf { it.length } / 1000.0

  val ramps: List<Pair<Span, List<EdgeInfo>>> =
      mergeGroups(edges.filter { it.isRamp })

  val unpaved: List<Span> = merge(edges.filter { it.surface in UNPAVED })

  val motorwayKm: Double
    get() = edges.filter { it.roadClass == "motorway" }.sumOf { it.endM - it.startM } / 1000.0

  /** Countries in the order they are crossed, with where the route enters each one. */
  val countries: List<Pair<String, Double>> =
      buildList {
        for (e in edges) {
          val c = e.country ?: continue
          if (isEmpty() || last().first != c) add(c to e.startM)
        }
      }

  fun edgeAt(alongM: Double): EdgeInfo? = edges.firstOrNull { alongM >= it.startM && alongM < it.endM }

  fun pointAt(alongM: Double): GeographicCoordinate = Geo.pointAt(route.geometry, cum, alongM)

  companion object {
    private const val TAG = "NavMasterAnalysis"
    val UNPAVED = setOf("compacted", "dirt", "gravel", "path", "impassable")
    private val json = Json { ignoreUnknownKeys = true }

    private fun merge(list: List<EdgeInfo>): List<Span> {
      val out = mutableListOf<Span>()
      for (e in list.sortedBy { it.startM }) {
        val last = out.lastOrNull()
        if (last != null && e.startM - last.endM < 30) out[out.size - 1] = Span(last.startM, maxOf(last.endM, e.endM))
        else out += Span(e.startM, e.endM)
      }
      return out
    }

    private fun mergeGroups(list: List<EdgeInfo>): List<Pair<Span, List<EdgeInfo>>> {
      val out = mutableListOf<Pair<Span, MutableList<EdgeInfo>>>()
      for (e in list.sortedBy { it.startM }) {
        val last = out.lastOrNull()
        if (last != null && e.startM - last.first.endM < 5) {
          last.second += e
          out[out.size - 1] = Span(last.first.startM, maxOf(last.first.endM, e.endM)) to last.second
        } else {
          out += Span(e.startM, e.endM) to mutableListOf(e)
        }
      }
      return out
    }

    /** trace_attributes on the route shape; an analysis without edges when the graph refuses it. */
    fun analyse(engine: RoutingEngine, route: Route, vehicle: VehicleProfile, loadT: Double): RouteAnalysis {
      if (route.geometry.size < 2) return RouteAnalysis(route, emptyList())
      val started = System.currentTimeMillis()
      val options = vehicle.valhallaOptions(loadT)
      val first = route.geometry.first()
      for (match in listOf("edge_walk", "map_snap")) {
        try {
          val req = buildJsonObject {
            put("encoded_polyline", Geo.encodePolyline6(route.geometry))
            put("shape_match", match)
            put("costing", vehicle.costing)
            options["costing_options"]?.let { put("costing_options", it) }
            put("filters", buildJsonObject {
              put("action", "include")
              put("attributes", JsonArray(ATTRS.map { JsonPrimitive(it) }))
            })
          }
          val raw = engine.use(first.lat, first.lng) { it.traceAttributesRaw(req.toString()) }
          val edges = parse(raw)
          if (edges.isNotEmpty()) {
            Log.i(TAG, "$match: ${edges.size} edges in ${System.currentTimeMillis() - started} ms")
            return RouteAnalysis(route, edges)
          }
        } catch (e: Exception) {
          Log.w(TAG, "trace_attributes $match: $e")
        }
      }
      return RouteAnalysis(route, emptyList())
    }

    private val ATTRS =
        listOf(
            "edge.way_id", "edge.road_class", "edge.use", "edge.toll", "edge.surface", "edge.lane_count",
            "edge.length", "edge.begin_shape_index", "edge.end_shape_index", "edge.names", "edge.tunnel",
            "edge.bridge", "edge.max_upward_grade", "edge.max_downward_grade", "edge.end_node.admin_index",
            "node.admin_index", "admin.country_code", "admin.country_text", "shape",
        )

    private fun parse(raw: String): List<EdgeInfo> {
      val root = json.parseToJsonElement(raw).jsonObject
      val shape = root["shape"]?.jsonPrimitive?.contentOrNull?.let { Geo.decodePolyline6(it) } ?: return emptyList()
      val cum = Geo.cumulative(shape)
      val admins = root["admins"]?.jsonArray?.map { it.jsonObject["country_code"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
      val edges = root["edges"]?.jsonArray ?: return emptyList()
      return edges.mapNotNull { el ->
        val e = el.jsonObject
        val b = e.int("begin_shape_index") ?: return@mapNotNull null
        val en = e.int("end_shape_index") ?: return@mapNotNull null
        if (b !in cum.indices || en !in cum.indices) return@mapNotNull null
        val adminIdx = (e["end_node"] as? JsonObject)?.int("admin_index")
        EdgeInfo(
            startM = cum[b],
            endM = cum[en],
            roadClass = e.str("road_class") ?: "",
            use = e.str("use") ?: "road",
            toll = e.bool("toll") ?: false,
            surface = e.str("surface") ?: "",
            lanes = e.int("lane_count") ?: 1,
            wayId = e["way_id"]?.jsonPrimitive?.longOrNull ?: 0,
            name = e["names"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            country = adminIdx?.let { admins.getOrNull(it) },
            tunnel = e.bool("tunnel") ?: false,
            bridge = e.bool("bridge") ?: false,
            maxUpGrade = e["max_upward_grade"]?.jsonPrimitive?.doubleOrNull,
            maxDownGrade = e["max_downward_grade"]?.jsonPrimitive?.doubleOrNull,
        )
      }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull
  }
}
