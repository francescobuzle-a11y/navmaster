package app.navmaster.truck.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The three kinds of vehicle the app is made for (project document, "Utenti e profili veicolo"). */
@Serializable
enum class VehicleType(val label: String, val costing: String) {
  CAMION("Camion / autoarticolato", "truck"),
  AUTOBUS("Autobus", "bus"),
  // Valhalla has no motorhome costing: the camper uses truck with its own (smaller) measures
  CAMPER("Camper", "truck"),
}

/**
 * A vehicle profile as stored on the tablet. Measures are the real ones of the vehicle; the load is
 * set at the start of every trip, so the weight used for routing is tare + load, not the maximum.
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
    val hazmat: Boolean = false,
    val topSpeedKmh: Int = 90,
    val avoidTolls: Boolean = false,
    val avoidFerries: Boolean = true,
    val avoidUnpaved: Boolean = true,
    /** Kept for when the routing engine supports it; Valhalla truck has no grade limit today. */
    val maxGradePercent: Int? = null,
) {
  /** Weight used for routing and for the weight-limit warnings. */
  fun tripWeightT(loadT: Double): Double = (tareT + loadT).coerceAtLeast(tareT)

  /**
   * The options object handed to Ferrostar's Valhalla request generator. It is merged into the
   * request, so the costing options, the language of the instructions and the units live here.
   */
  fun valhallaOptions(loadT: Double): JsonObject = buildJsonObject {
    putJsonObject("costing_options") {
      putJsonObject(type.costing) {
        put("height", heightM)
        put("width", widthM)
        if (type.costing == "truck") {
          put("length", lengthM)
          put("weight", tripWeightT(loadT))
          put("axle_load", axleLoadT)
          put("axle_count", axleCount)
          put("hazmat", hazmat)
          put("use_truck_route", 0.6)
          put("hgv_no_access_penalty", 43200)
        }
        put("top_speed", topSpeedKmh)
        put("use_tolls", if (avoidTolls) 0.0 else 0.5)
        put("use_ferry", if (avoidFerries) 0.0 else 0.5)
        put("exclude_unpaved", avoidUnpaved)
      }
    }
    put("language", "it-IT")
    put("units", "kilometers")
    put("directions_type", "instructions")
  }

  companion object {
    fun defaults(): List<VehicleProfile> =
        listOf(
            VehicleProfile(
                id = "camion",
                name = "Autoarticolato",
                type = VehicleType.CAMION,
                heightM = 4.0,
                widthM = 2.55,
                lengthM = 16.5,
                tareT = 15.0,
                maxWeightT = 44.0,
                axleLoadT = 11.5,
                axleCount = 5,
                trailer = true,
                topSpeedKmh = 80,
            ),
            VehicleProfile(
                id = "autobus",
                name = "Autobus turistico",
                type = VehicleType.AUTOBUS,
                heightM = 3.8,
                widthM = 2.55,
                lengthM = 13.5,
                tareT = 13.0,
                maxWeightT = 19.5,
                axleLoadT = 11.5,
                axleCount = 2,
                topSpeedKmh = 100,
            ),
            VehicleProfile(
                id = "camper",
                name = "Camper",
                type = VehicleType.CAMPER,
                heightM = 3.2,
                widthM = 2.35,
                lengthM = 7.5,
                tareT = 3.0,
                maxWeightT = 3.5,
                axleLoadT = 2.0,
                axleCount = 2,
                topSpeedKmh = 100,
            ),
        )
  }
}
