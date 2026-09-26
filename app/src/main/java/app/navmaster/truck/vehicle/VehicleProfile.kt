package app.navmaster.truck.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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

  /** Valhalla profile: vans and light campers are cars with their height and width. */
  val costing: String
    get() =
        when {
          type == VehicleType.AUTOBUS -> "bus"
          type == VehicleType.FURGONE || (type == VehicleType.CAMPER && maxWeightT <= 3.5) -> "auto"
          else -> "truck"
        }

  /**
   * The options object merged into the Valhalla request by Ferrostar's generator: costing options,
   * instruction language and units, plus the choices of this trip.
   */
  fun valhallaOptions(loadT: Double, trip: TripOptions = TripOptions()): JsonObject = buildJsonObject {
    putJsonObject("costing_options") {
      putJsonObject(costing) {
        put("height", heightM)
        put("width", widthM)
        if (costing == "truck") {
          put("length", lengthM)
          put("weight", tripWeightT(loadT))
          put("axle_load", axleLoadT)
          put("axle_count", axleCount)
          put("hazmat", hazmat)
          // Valhalla makes roads signed for lorries (hgv=designated) much cheaper as this grows: at
          // 0.6 they cost about half, and a lorry left the A14 for 25 km of local "truck roads" that
          // take 65% longer. A light preference keeps them as a tie-breaker, never a detour.
          put("use_truck_route", if (preferTruckRoutes) 0.15 else 0.0)
          put("hgv_no_access_penalty", 43200)
        }
        put("top_speed", topSpeedKmh)
        put("use_tolls", if (trip.avoidTolls) 0.0 else 0.5)
        if (trip.avoidTolls) put("exclude_tolls", true)
        put("use_ferry", if (avoidFerries) 0.0 else 0.5)
        put("exclude_unpaved", avoidUnpaved)
        if (trip.shortest) put("shortest", true)
      }
    }
    if (trip.excludePolygons.isNotEmpty()) {
      put(
          "exclude_polygons",
          JsonArray(trip.excludePolygons.map { ring -> JsonArray(ring.map { p -> JsonArray(p.map { JsonPrimitive(it) }) }) }),
      )
    }
    if (trip.alternates > 0) put("alternates", trip.alternates)
    put("language", "it-IT")
    put("units", "kilometers")
    put("directions_type", "instructions")
  }

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

/** Choices that change from trip to trip (and during a trip). */
data class TripOptions(
    val avoidTolls: Boolean = false,
    val shortest: Boolean = false,
    val alternates: Int = 0,
    /** Rings of [lon, lat] the route must not touch (points the driver chose to avoid). */
    val excludePolygons: List<List<List<Double>>> = emptyList(),
)
