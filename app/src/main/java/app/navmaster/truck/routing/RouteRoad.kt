package app.navmaster.truck.routing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One road of the route as the engine describes it (`properties.navmaster.roads` of its answer):
 * the piece of the route's line from point [from] to point [to], and what the graph knows of it.
 * Only facts: the engine already computed the route with openrouteservice's rules.
 */
data class RouteRoad(
    val from: Int,
    val to: Int,
    val way: Long,
    /** highway without `_link`: motorway, trunk, primary … service, other. */
    val cls: String,
    val link: Boolean,
    val roundabout: Boolean,
    val tunnel: Boolean,
    val bridge: Boolean,
    val ferry: Boolean,
    val toll: Boolean,
    val surface: String,
    val oneway: Boolean,
    /** Speed limit driving this way (km/h; 0 = "no limit" sign), null when not mapped. */
    val maxspeed: Int?,
    val maxspeedHgv: Int?,
    val lanes: Int?,
    /** OSM turn:lanes driving this way ("left|through|through;right"), null when not mapped. */
    val turnLanes: String?,
    val ref: String?,
    val name: String?,
    val destination: String?,
    val destinationRef: String?,
    /** The motorway junction at the start of this road ("ref|name"), for exits. */
    val exit: String?,
    /** maxheight, maxwidth, maxlength (m), maxweight, maxaxleload (t) signed on this road. */
    val limits: Map<String, Double>,
    val hazmat: Boolean,
    /** Toll booths, toll gantries and border controls on this road (type, point index). */
    val nodes: List<Pair<String, Int>>,
) {
  /** The speed limit for a lorry or a bus ([heavy]) or for the others (km/h), null when not known. */
  fun limit(heavy: Boolean): Double? {
    val base = maxspeed
    if (heavy && maxspeedHgv != null && maxspeedHgv > 0) return if (base == null || base == 0) maxspeedHgv.toDouble() else minOf(base, maxspeedHgv).toDouble()
    return base?.toDouble()
  }

  /** The class as RouteAnalysis names it. */
  val roadClass: String
    get() = when (cls) { "living_street" -> "residential"; "service", "road", "other", "track" -> "service_other"; else -> cls }

  val names: List<String>
    get() = listOfNotNull(ref, name).flatMap { it.split(";", ", ").map(String::trim) }.filter { it.isNotEmpty() }.distinct()

  companion object {
    /** The roads of route feature [props] (its `properties`), in order; empty when the engine gave none. */
    fun parse(props: JsonObject): List<RouteRoad> {
      val list = (props["navmaster"] as? JsonObject)?.get("roads")?.jsonArray ?: return emptyList()
      return list.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
        fun bool(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
        val from = int("from") ?: return@mapNotNull null
        val to = int("to") ?: return@mapNotNull null
        val limits = (o["limits"] as? JsonObject)?.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }?.toMap() ?: emptyMap()
        val nodes = o["nodes"]?.jsonArray?.mapNotNull { n ->
          val no = n as? JsonObject ?: return@mapNotNull null
          val t = no["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val i = no["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
          t to i
        } ?: emptyList()
        RouteRoad(from, to, o["way"]?.jsonPrimitive?.longOrNull ?: 0L, str("class") ?: "other", bool("link"), bool("roundabout"),
            bool("tunnel"), bool("bridge"), bool("ferry"), bool("toll"), str("surface") ?: "unknown", bool("oneway"),
            int("maxspeed"), int("maxspeed_hgv"), int("lanes"), str("turn_lanes"), str("ref"), str("name"),
            str("destination"), str("destination_ref"), str("exit"), limits, bool("hazmat"), nodes)
      }
    }

    /** For every piece i → i+1 of a line of [n] points, the road it is on (null where the engine said nothing). */
    fun perSegment(roads: List<RouteRoad>, n: Int): Array<RouteRoad?> {
      val out = arrayOfNulls<RouteRoad>(maxOf(0, n - 1))
      for (r in roads) for (i in r.from.coerceAtLeast(0) until r.to.coerceAtMost(n - 1)) out[i] = r
      return out
    }
  }
}
