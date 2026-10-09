package app.navmaster.truck.vehicle

import kotlinx.serialization.Serializable

/** Kinds of vehicle, each with the routing profile it uses and its typical geometry. */
@Serializable
enum class VehicleType(val label: String, val icon: String, val heavy: Boolean) {
  AUTOARTICOLATO("Autoarticolato", "🚛", true),
  AUTOTRENO("Autotreno (con rimorchio)", "🚚", true),
  MOTRICE("Camion / motrice", "🚚", true),
  FURGONE("Furgone ≤ 3,5 t", "🚐", false),
  AUTOBUS("Autobus", "🚌", true),
  CAMPER("Camper", "🚐", false),
}

/** ADR tunnel restriction code of the load (B is the strictest). */
@Serializable
enum class AdrTunnel(val label: String) {
  NONE("Nessuna merce pericolosa"),
  E("Codice galleria E"),
  D("Codice galleria D"),
  C("Codice galleria C"),
  B("Codice galleria B"),
}

/**
 * A vehicle as stored on the tablet. Measures are the real ones; the load is set at the start of
 * every trip, so the weight used for routing is tare + load, not the maximum.
 * The geometry (wheelbases, overhangs) is what the swept-path check uses to tell whether a curve or
 * a junction is too tight for this vehicle.
 */
@Serializable
data class VehicleProfile(
    val id: String,
    val name: String,
    val type: VehicleType,
    val heightM: Double,
    val widthM: Double,
    val lengthM: Double,
    val tareT: Double,
    val maxWeightT: Double,
    val axleLoadT: Double,
    val axleCount: Int,
    val trailer: Boolean = false,
    val adr: AdrTunnel = AdrTunnel.NONE,
    val hazmatWater: Boolean = false,
    val topSpeedKmh: Int = 90,
    val avoidFerries: Boolean = true,
    val avoidUnpaved: Boolean = true,
    val preferTruckRoutes: Boolean = true,
    /** Distance front axle -> rear (drive) axle. */
    val wheelbaseM: Double = 3.8,
    /** Articulated: kingpin -> middle of the trailer axles. Drawbar trailer: hitch -> trailer axles. */
    val trailerWheelbaseM: Double = 0.0,
    /** Where the trailer is hooked, measured backwards from the rear axle (negative = in front: fifth wheel). */
    val couplingOffsetM: Double = 0.0,
    val frontOverhangM: Double = 1.4,
    /** Outer turning radius from the registration papers (EU: 12.5 m at most). */
    val turnRadiusM: Double = 12.5,
    val emissionClass: String = "EURO 6",
) {
  val hazmat: Boolean
    get() = adr != AdrTunnel.NONE || hazmatWater

  /** Weight used for routing and for the weight-limit warnings. */
  fun tripWeightT(loadT: Double): Double = (tareT + loadT).coerceAtLeast(tareT)

  /** Goods vehicle over 3.5 t for the law (HGV bans and restrictions use the maximum mass). */
  val isHgv: Boolean
    get() = maxWeightT > 3.5 && type in setOf(VehicleType.AUTOARTICOLATO, VehicleType.AUTOTRENO, VehicleType.MOTRICE)

  /** A bus: routed as openrouteservice routes buses (driving-hgv, vehicle_type "bus"). */
  val isBus: Boolean
    get() = type == VehicleType.AUTOBUS

  companion object {
    /** Typical geometry of each kind of vehicle for its length (used when type or length change). */
    fun withTypicalGeometry(p: VehicleProfile): VehicleProfile = with(p) {
      when (type) {
        VehicleType.AUTOARTICOLATO ->
            copy(wheelbaseM = 3.8, couplingOffsetM = -0.5, trailerWheelbaseM = (7.8 * lengthM / 16.5).coerceIn(5.0, 10.5),
                frontOverhangM = 1.4, trailer = true, turnRadiusM = 12.5)
        VehicleType.AUTOTRENO ->
            copy(wheelbaseM = 5.0, couplingOffsetM = 2.2, trailerWheelbaseM = 6.0, frontOverhangM = 1.4, trailer = true, turnRadiusM = 12.5)
        VehicleType.MOTRICE ->
            copy(wheelbaseM = (lengthM * 0.5).coerceIn(3.5, 7.0), trailerWheelbaseM = 0.0, frontOverhangM = 1.4, trailer = false,
                turnRadiusM = 11.0)
        VehicleType.AUTOBUS ->
            copy(wheelbaseM = (lengthM * 0.47).coerceIn(4.5, 7.2), trailerWheelbaseM = 0.0, frontOverhangM = 2.7, trailer = false,
                turnRadiusM = 12.0)
        VehicleType.CAMPER ->
            copy(wheelbaseM = (lengthM * 0.55).coerceIn(3.0, 5.0), trailerWheelbaseM = 0.0, frontOverhangM = 0.9, trailer = false,
                turnRadiusM = 8.5)
        VehicleType.FURGONE ->
            copy(wheelbaseM = (lengthM * 0.58).coerceIn(2.8, 4.4), trailerWheelbaseM = 0.0, frontOverhangM = 0.9, trailer = false,
                turnRadiusM = 7.0)
      }
    }

    fun defaults(): List<VehicleProfile> =
        listOf(
            VehicleProfile(
                id = "camion", name = "Autoarticolato", type = VehicleType.AUTOARTICOLATO,
                heightM = 4.0, widthM = 2.55, lengthM = 16.5, tareT = 15.0, maxWeightT = 40.0,
                axleLoadT = 11.5, axleCount = 5, trailer = true, topSpeedKmh = 80,
                wheelbaseM = 3.8, couplingOffsetM = -0.5, trailerWheelbaseM = 7.8, frontOverhangM = 1.4,
            ),
            VehicleProfile(
                id = "motrice", name = "Motrice 3 assi", type = VehicleType.MOTRICE,
                heightM = 3.8, widthM = 2.55, lengthM = 10.5, tareT = 11.0, maxWeightT = 26.0,
                axleLoadT = 10.0, axleCount = 3, topSpeedKmh = 80,
                wheelbaseM = 5.2, frontOverhangM = 1.4, turnRadiusM = 10.5,
            ),
            VehicleProfile(
                id = "autobus", name = "Autobus turistico", type = VehicleType.AUTOBUS,
                heightM = 3.8, widthM = 2.55, lengthM = 13.5, tareT = 13.0, maxWeightT = 19.5,
                axleLoadT = 11.5, axleCount = 2, topSpeedKmh = 100,
                wheelbaseM = 6.4, frontOverhangM = 2.7, turnRadiusM = 12.0,
            ),
            VehicleProfile(
                id = "camper", name = "Camper", type = VehicleType.CAMPER,
                heightM = 3.2, widthM = 2.35, lengthM = 7.5, tareT = 3.0, maxWeightT = 3.5,
                axleLoadT = 2.0, axleCount = 2, topSpeedKmh = 100,
                wheelbaseM = 4.0, frontOverhangM = 0.9, turnRadiusM = 8.5,
            ),
        )
  }
}

/**
 * The kind of route the driver prefers, as openrouteservice's "preference": the fastest, the one
 * with more motorway ("recommended": lorries prefer motorways and main roads and avoid small
 * streets; for campers and cars it is the fastest), the shortest.
 */
@Serializable
enum class RouteKind(val label: String, val gh: Int) {
  FASTEST("Più veloce", 0),
  MOTORWAY("Più autostrada", 1),
  SHORTEST("Più corto", 2),
}

/** Choices that change from trip to trip (and during a trip). */
data class TripOptions(
    val avoidTolls: Boolean = false,
    val shortest: Boolean = false,
    val route: RouteKind = RouteKind.FASTEST,
    val alternates: Int = 0,
    /**
     * The "more routes" the driver asks for: GraphHopper's alternatives on the tablet (also when
     * openrouteservice gave the first ones).
     */
    val moreRoutes: Boolean = false,
    /** Rings of [lon, lat] the route must not touch (points the driver chose to avoid). */
    val excludePolygons: List<List<List<Double>>> = emptyList(),
    /**
     * Direction of travel at some pass-through points, by [pointKey]: points taken from a route
     * already computed, so that the route takes them on the right carriageway of a motorway.
     */
    val viaHeadings: Map<String, Double> = emptyMap(),
) {
  companion object {
    fun pointKey(lat: Double, lon: Double): String = String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lon)
  }
}
