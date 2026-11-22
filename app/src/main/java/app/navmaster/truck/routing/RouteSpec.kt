package app.navmaster.truck.routing

import app.navmaster.truck.vehicle.AdrTunnel
import app.navmaster.truck.vehicle.RouteKind
import app.navmaster.truck.vehicle.TripOptions
import app.navmaster.truck.vehicle.VehicleProfile

/**
 * The vehicle and the trip's choices in openrouteservice's terms, nothing more: the profile
 * (driving-hgv for lorries and buses, driving-car for campers, vans and cars), the measures and the
 * weight with the load (`profile_params.restrictions`), `avoid_features`, `avoid_polygons`,
 * `maximum_speed` and the `preference`. The engine (navmaster-engine) applies openrouteservice's
 * own rules to them; the app adds no rule of its own.
 */
data class RouteSpec(
    val hgv: Boolean,
    /** openrouteservice's vehicle_type on driving-hgv: "hgv" (lorry) or "bus". */
    val vehicleType: String,
    val heightM: Double,
    val widthM: Double,
    val lengthM: Double,
    /** Weight with the load, tonnes. */
    val weightT: Double,
    val axleLoadT: Double,
    /** Dangerous goods on board (ADR): openrouteservice's `hazmat` restriction. */
    val hazmat: Boolean,
    /**
     * The load's ADR tunnel code, "B" to "E" ("" none): a NavMaster extension of the engine
     * (`restrictions.adr_tunnel_code`). With it a tunnel closes only from its own category on
     * (code D: tunnels D and E), instead of every tunnel with an ADR category.
     */
    val adrTunnelCode: String = "",
    /** Goods polluting to water on board: roads with `hazmat:water=no` closed (`restrictions.hazmat_water`). */
    val hazmatWater: Boolean = false,
    /** Top speed of the vehicle, km/h (openrouteservice's `maximum_speed`, 80 km/h or more). */
    val topSpeedKmh: Double,
    val avoidTolls: Boolean,
    val avoidFerries: Boolean,
    val avoidMotorways: Boolean,
    /** openrouteservice's preference: "fastest", "recommended" or "shortest". */
    val preference: String,
    /** Rings of [lon, lat] the route must not touch (`avoid_polygons`). */
    val avoidZones: List<List<List<Double>>> = emptyList(),
    /** A recalculation while driving: the start in the vehicle's direction, never a turn round there (`navmaster.start_heading`). */
    val strictStart: Boolean = false,
) {
  val profile: String
    get() = if (hgv) "driving-hgv" else "driving-car"

  override fun toString(): String =
      "RouteSpec{$profile" + (if (hgv && vehicleType != "hgv") "/$vehicleType" else "") + ", h=$heightM, w=$widthM, l=$lengthM, t=$weightT, axle=$axleLoadT" +
          ", hazmat=$hazmat" + (if (adrTunnelCode.isNotEmpty()) "/$adrTunnelCode" else "") + (if (hazmatWater) "/water" else "") + ", v=$topSpeedKmh, $preference, tolls=${!avoidTolls}, ferries=${!avoidFerries}" +
          (if (avoidMotorways) ", noMotorway" else "") + (if (strictStart) ", strictStart" else "") + ", zones=${avoidZones.size}}"
}

/** The vehicle and the trip's choices as the engine needs them. */
fun VehicleProfile.routeSpec(loadT: Double, trip: TripOptions): RouteSpec {
  val v = this
  // the bans for lorries (hgv=no) are for goods vehicles over 3.5 t, not for campers; a bus is
  // routed as openrouteservice routes it: driving-hgv with vehicle_type "bus"
  return RouteSpec(
      hgv = v.isHgv || v.isBus,
      vehicleType = if (v.isBus) "bus" else "hgv",
      heightM = v.heightM, widthM = v.widthM, lengthM = v.lengthM,
      weightT = v.tripWeightT(loadT), axleLoadT = v.axleLoadT,
      hazmat = v.adr != AdrTunnel.NONE,
      adrTunnelCode = if (v.adr == AdrTunnel.NONE) "" else v.adr.name,
      hazmatWater = v.hazmatWater,
      topSpeedKmh = v.topSpeedKmh.toDouble(),
      avoidTolls = trip.avoidTolls,
      avoidFerries = v.avoidFerries,
      avoidMotorways = trip.avoidMotorways,
      preference = when {
        trip.shortest || trip.route == RouteKind.SHORTEST -> "shortest"
        trip.route == RouteKind.RECOMMENDED -> "recommended"
        else -> "fastest"
      },
      avoidZones = trip.excludePolygons,
      strictStart = trip.strictStart,
  )
}
