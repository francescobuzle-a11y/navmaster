package app.navmaster.truck.data

import app.navmaster.truck.routing.NmEngine
import uniffi.ferrostar.GeographicCoordinate

/**
 * The roads around a point, from the engine's graph (navmaster-engine): for the junction view,
 * the road the vehicle is driving (RoadAhead) and the "is there a road here" checks. One road
 * per edge of the graph (a way is cut at every junction), with its lanes, arrows, speed limits
 * and the direction it can be driven.
 */
class RoadsIndex(private val engine: () -> NmEngine) {
  data class Road(
      val osm: Long, val hw: String, val ms: Double?, val msf: Double?, val msb: Double?, val msHgv: Double?,
      /** 1 one way along the line, -1 the other way, 0 both ways. */
      val oneway: Int, val lanes: Int?, val lanesF: Int?, val lanesB: Int?,
      val turnLanes: String?, val turnLanesF: String?, val turnLanesB: String?,
      val roundabout: Boolean, val toll: Boolean, val tunnel: Boolean, val bridge: Boolean,
      val ref: String?, val name: String?, val pts: List<GeographicCoordinate>,
  ) {
    /** The speed limit going [forward] along the way (km/h; 0 = none), null when not known. */
    fun limit(forward: Boolean, heavy: Boolean): Double? {
      val base = (if (forward) msf else msb) ?: ms
      if (heavy && msHgv != null && msHgv > 0) return if (base == null || base == 0.0) msHgv else minOf(base, msHgv)
      return base
    }

    /** The turn lanes going [forward] ("left|through|right"), null when not mapped. */
    fun turns(forward: Boolean): String? = when {
      oneway != 0 -> if (oneway < 0) turnLanes ?: turnLanesB else turnLanes ?: turnLanesF
      forward -> turnLanesF
      else -> turnLanesB
    }

    /** The lanes going [forward]. */
    fun laneCount(forward: Boolean): Int = when {
      oneway != 0 -> lanes ?: 1
      forward -> lanesF ?: lanes?.let { (it + 1) / 2 } ?: 1
      else -> lanesB ?: lanes?.let { it / 2 } ?: 1
    }

    val isLink: Boolean get() = hw.endsWith("_link")
    val roadClass: String get() = hw.removeSuffix("_link").let { if (it == "living_street") "residential" else if (it == "service" || it == "road" || it == "other") "service_other" else it }
  }

  /** The road of one piece of the route and the direction it is driven. */
  data class On(val road: Road, val forward: Boolean)

  /** There is a graph for some downloaded country. */
  fun available(): Boolean = true

  /** The roads within [radiusM] of a point. */
  fun near(lat: Double, lon: Double, radiusM: Double): List<Road> =
      engine().roadsAround(lat, lon, radiusM).mapNotNull { m -> toRoad(m) }

  private fun toRoad(m: Map<String, Any?>): Road? {
    fun num(k: String): Double? = (m[k] as? Number)?.toDouble()
    fun int(k: String): Int? = (m[k] as? Number)?.toInt()
    fun str(k: String): String? = (m[k] as? String)?.takeIf { it.isNotBlank() }
    fun bool(k: String): Boolean = m[k] == true
    @Suppress("UNCHECKED_CAST")
    val coords = m["coordinates"] as? List<DoubleArray> ?: return null
    if (coords.size < 2) return null
    val pts = coords.map { GeographicCoordinate(it[1], it[0]) }
    val accessAb = m["access_ab"] != false
    val accessBa = m["access_ba"] != false
    // one way as signed, else as the cars may drive it (a dual carriageway without an oneway tag)
    val ow = int("oneway") ?: 0
    val oneway = if (ow != 0) ow else if (accessAb && !accessBa) 1 else if (!accessAb && accessBa) -1 else 0
    val cls = str("class") ?: "other"
    val lf = int("lanes_ab"); val lb = int("lanes_ba")
    return Road(
        osm = (m["way"] as? Number)?.toLong() ?: 0L,
        hw = if (bool("link")) "${cls}_link" else cls,
        ms = null, msf = num("maxspeed_ab"), msb = num("maxspeed_ba"), msHgv = num("maxspeed_hgv"),
        oneway = oneway,
        lanes = if (oneway != 0) (if (oneway > 0) lf else lb) ?: lf ?: lb else if (lf != null && lb != null) lf + lb else null,
        lanesF = lf, lanesB = lb,
        turnLanes = null, turnLanesF = str("turn_lanes_ab"), turnLanesB = str("turn_lanes_ba"),
        roundabout = bool("roundabout"), toll = bool("toll"), tunnel = bool("tunnel"), bridge = bool("bridge"),
        ref = str("ref"), name = str("name"), pts = pts,
    )
  }
}
