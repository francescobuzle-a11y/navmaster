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
    val roundabout: Boolean = false,
    /** Every name of the road, road numbers included ("A14", "Autostrada Adriatica"). */
    val names: List<String> = emptyList(),
    /** What the direction signs say at the start of this road (from the OSM destination tags). */
    val sign: EdgeSign? = null,
) {
  /** Road numbers only: "A14", "SS16", "E45", "DN1". */
  val refs: List<String>
    get() = names.filter { REF.matches(it.trim()) }.map { it.trim() }

  val isRamp: Boolean
    get() = use == "ramp"

  val isMajor: Boolean
    get() = roadClass in setOf("motorway", "trunk", "primary")
}

private val REF = Regex("^(?:[A-Z]{1,3}[ -]?\\d{1,4}[a-z]?(?:[ -]?[A-Z]{1,2})?|[A-Z]{1,2}\\d{1,4}[a-z]?)$")

/** A direction sign: exit number, road numbers of the branch, towns it leads to. */
data class EdgeSign(
    val exitNumbers: List<String>,
    val branches: List<String>,
    val towards: List<String>,
    val exitNames: List<String>,
    /** destination:colour of OSM ("green", "blue", "white" ...), when mapped. */
    val colour: String? = null,
) {
  val isEmpty: Boolean
    get() = exitNumbers.isEmpty() && branches.isEmpty() && towards.isEmpty() && exitNames.isEmpty()
}

/** A road that leaves a junction of the route, with its sign: the one not taken shows where it goes. */
data class SideSign(val sign: EdgeSign, /** -1 left of the route, 1 right, 0 straight on. */ val side: Int, val wayId: Long)

/** The signs of one junction of the route, from the OSM destination tags (limiti.sqlite). */
data class RouteSign(val atM: Double, val taken: EdgeSign?, val others: List<SideSign>)

/** A toll booth, a toll gantry or a border control on the route (at the end of an edge). */
data class RouteNode(val alongM: Double, val type: String) {
  val isToll: Boolean
    get() = type == "toll_booth" || type == "toll_gantry"
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
class RouteAnalysis(
    val route: Route,
    val edges: List<EdgeInfo>,
    val nodes: List<RouteNode> = emptyList(),
    /** Where the route crosses other drivable roads (metres along the route, in order). */
    val junctions: DoubleArray = DoubleArray(0),
) {
  val cum: DoubleArray = Geo.cumulative(route.geometry)
  val length: Double
    get() = cum.lastOrNull() ?: 0.0

  val durationS: Double = route.steps.sumOf { it.duration }

  val tolls: List<Span> = merge(edges.filter { it.toll })
  val tollKm: Double
    get() = tolls.sumOf { it.length } / 1000.0

  /** Toll booths (and gantries) along the route, in order. */
  val tollBooths: List<RouteNode> = nodes.filter { it.isToll }

  val borders: List<RouteNode> = nodes.filter { it.type == "border_control" }

  /**
   * "entrata" / "uscita" / null for a booth in the middle of a toll stretch (a barrier). The
   * ramps between a booth and the motorway are usually not tagged as toll roads in OSM, so the
   * toll stretch is looked for within 3 km on each side of the booth.
   */
  fun boothRole(b: RouteNode): String? {
    val before = tolls.any { it.startM < b.alongM - 1 && it.endM > b.alongM - 3000 }
    val after = tolls.any { it.endM > b.alongM + 1 && it.startM < b.alongM + 3000 }
    return when {
      !before && after -> "entrata"
      before && !after -> "uscita"
      else -> null
    }
  }

  /** OSM ways the route drives on: what lets a mapped limit or difficulty be matched exactly. */
  val wayIds: Set<Long> = edges.mapNotNullTo(HashSet<Long>()) { it.wayId.takeIf { id -> id > 0 } }

  /** Where the route drives on this OSM way: first and last metre along the route. */
  fun wayExtent(wayId: Long): Span? {
    var lo = Double.MAX_VALUE
    var hi = -1.0
    for (e in edges) if (e.wayId == wayId) {
      if (e.startM < lo) lo = e.startM
      if (e.endM > hi) hi = e.endM
    }
    return if (hi >= 0) Span(lo, hi) else null
  }

  /** Is there a crossing with another drivable road within tolM of this point of the route? */
  fun junctionNear(alongM: Double, tolM: Double): Boolean {
    var i = java.util.Arrays.binarySearch(junctions, alongM - tolM)
    if (i < 0) i = -i - 1
    return i < junctions.size && junctions[i] <= alongM + tolM
  }

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

  /**
   * The direction sign of a junction: the first sign on the roads the route takes from shortly
   * before the manoeuvre to a few hundred metres after it (where OSM puts the destination tags).
   */
  fun signNear(alongM: Double): Pair<EdgeInfo, EdgeSign>? =
      edges.firstOrNull { it.sign != null && it.startM >= alongM - 40 && it.startM <= alongM + 300 }?.let { it to it.sign!! }

  /** Signs of the junctions of the route from the OSM destination tags, filled by LimitsIndex.scan. */
  @Volatile var osmSigns: List<RouteSign> = emptyList()

  /** The junction signs closest to this point of the route (within 60 m). */
  fun osmSignNear(alongM: Double): RouteSign? =
      osmSigns.filter { kotlin.math.abs(it.atM - alongM) < 60 }.minByOrNull { kotlin.math.abs(it.atM - alongM) }

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

    /**
     * Diagnosis: the speed the graph gives [vehicle] on every road of [route] (map_snap, so the
     * vehicle's own rules apply), summed per OSM way: "way:metres@kmh", and the total time.
     */
    fun speedsDebug(engine: RoutingEngine, route: Route, vehicle: VehicleProfile, loadT: Double, match: String = "map_snap"): String {
      val options = vehicle.valhallaOptions(loadT)
      val first = route.geometry.first()
      val req = buildJsonObject {
        put("encoded_polyline", Geo.encodePolyline6(route.geometry))
        put("shape_match", match)
        put("costing", vehicle.costing)
        options["costing_options"]?.let { put("costing_options", it) }
        put("filters", buildJsonObject {
          put("action", "include")
          put("attributes", JsonArray(listOf("edge.way_id", "edge.length", "edge.speed", "edge.truck_speed", "edge.speed_limit",
              "edge.road_class", "edge.truck_route", "edge.toll", "edge.use").map { JsonPrimitive(it) }))
        })
      }
      val raw = engine.use(first.lat, first.lng) { it.traceAttributesRaw(req.toString()) }
      val root = json.parseToJsonElement(raw).jsonObject
      val edges = root["edges"]?.jsonArray ?: return "no edges: ${raw.take(300)}"
      var total = 0.0
      val parts = mutableListOf<String>()
      var lastWay = -1L
      var wayLen = 0.0
      var waySpeed = 0.0
      fun flush() {
        if (lastWay >= 0) parts += "$lastWay:${wayLen.toInt()}@${waySpeed.toInt()}"
      }
      for (e in edges) {
        val o = e.jsonObject
        val way = o["way_id"]?.jsonPrimitive?.longOrNull ?: 0L
        val lenM = (o["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000
        val sp = o["speed"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        if (sp > 0) total += lenM / (sp / 3.6)
        if (way != lastWay) { flush(); lastWay = way; wayLen = 0.0; waySpeed = sp }
        wayLen += lenM
        waySpeed = minOf(waySpeed, sp)
      }
      flush()
      val t = edges.firstOrNull()?.jsonObject
      return "$match ${vehicle.name}: ${edges.size} edges, ${total.toInt()} s at the graph speeds; truck_speed/limit of first " +
          "${t?.get("truck_speed")}/${t?.get("speed_limit")}; " + parts.joinToString(" ")
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
          val p = parse(raw)
          if (p.edges.isNotEmpty()) {
            val a = RouteAnalysis(route, p.edges, p.nodes, p.junctions)
            Log.i(TAG, "$match: ${p.edges.size} edges in ${System.currentTimeMillis() - started} ms, toll ${"%.1f".format(a.tollKm)} km, " +
                "booths ${a.tollBooths.joinToString { "${it.type}@${it.alongM.toInt()}:${a.boothRole(it)}" }}, borders ${a.borders.size}, " +
                "junctions ${p.junctions.size}, signs " +
                p.edges.filter { it.sign != null }.joinToString(prefix = "[", postfix = "]") { e ->
                  "${e.startM.toInt()}:${e.sign?.exitNumbers?.joinToString("/")} ${e.sign?.branches?.joinToString("/")} > ${e.sign?.towards?.joinToString("/")}"
                })
            return a
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
            "edge.bridge", "edge.roundabout", "edge.sign.exit_number", "edge.sign.exit_branch", "edge.sign.exit_toward",
            "edge.sign.exit_name", "edge.max_upward_grade", "edge.max_downward_grade", "edge.end_node.admin_index",
            "node.admin_index", "node.type", "node.intersecting_edge.driveability", "node.intersecting_edge.use",
            "admin.country_code", "admin.country_text", "shape",
        )

    private class Parsed(val edges: List<EdgeInfo>, val nodes: List<RouteNode>, val junctions: DoubleArray)

    private fun parse(raw: String): Parsed {
      val none = Parsed(emptyList(), emptyList(), DoubleArray(0))
      val root = json.parseToJsonElement(raw).jsonObject
      val shape = root["shape"]?.jsonPrimitive?.contentOrNull?.let { Geo.decodePolyline6(it) } ?: return none
      val cum = Geo.cumulative(shape)
      val admins = root["admins"]?.jsonArray?.map { it.jsonObject["country_code"]?.jsonPrimitive?.contentOrNull } ?: emptyList()
      val edges = root["edges"]?.jsonArray ?: return none
      val nodes = mutableListOf<RouteNode>()
      val junctions = mutableListOf<Double>()
      val list = edges.mapNotNull { el ->
        val e = el.jsonObject
        val b = e.int("begin_shape_index") ?: return@mapNotNull null
        val en = e.int("end_shape_index") ?: return@mapNotNull null
        if (b !in cum.indices || en !in cum.indices) return@mapNotNull null
        val endNode = e["end_node"] as? JsonObject
        val adminIdx = endNode?.int("admin_index")
        endNode?.str("type")?.takeIf { it in NODE_TYPES }?.let { nodes += RouteNode(cum[en], it) }
        // a crossing: another road a vehicle can drive leaves this node (footways and the like do not count)
        val signObj = e["sign"] as? JsonObject
        fun texts(k: String): List<String> =
            (signObj?.get(k) as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("text")?.takeIf { t -> t.isNotBlank() } } ?: emptyList()
        val sign = signObj?.let { EdgeSign(texts("exit_number"), texts("exit_branch"), texts("exit_toward"), texts("exit_name")) }
            ?.takeIf { !it.isEmpty }
        val crossing = (endNode?.get("intersecting_edges") as? JsonArray)?.any { x ->
          val o = x as? JsonObject ?: return@any false
          o.str("driveability") in DRIVABLE && o.str("use") !in NOT_ROADS
        } ?: false
        if (crossing) junctions += cum[en]
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
            country = adminIdx?.let { admins.getOrNull(it) }?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) },
            tunnel = e.bool("tunnel") ?: false,
            bridge = e.bool("bridge") ?: false,
            maxUpGrade = e["max_upward_grade"]?.jsonPrimitive?.doubleOrNull,
            maxDownGrade = e["max_downward_grade"]?.jsonPrimitive?.doubleOrNull,
            roundabout = e.bool("roundabout") ?: false,
            names = e["names"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            sign = sign,
        )
      }
      return Parsed(list, nodes, junctions.sorted().toDoubleArray())
    }

    private val NODE_TYPES = setOf("toll_booth", "toll_gantry", "border_control")
    private val DRIVABLE = setOf("forward", "backward", "both")
    private val NOT_ROADS = setOf("driveway", "parking_aisle", "drive_through", "emergency_access", "footway", "cycleway", "path", "steps")

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull
  }
}
