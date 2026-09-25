package app.navmaster.truck.settings

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What to do with toll roads. */
@Serializable
enum class TollPolicy(val label: String) {
  ASK("Chiedimi quando conviene"),
  AVOID("Evita sempre i pedaggi"),
  ALLOW("Usa i pedaggi"),
}

@Serializable
enum class NightMode(val label: String) {
  AUTO("Automatica"),
  DAY("Sempre giorno"),
  NIGHT("Sempre notte"),
}

/** One kind of place the driver can choose to see along the route. */
data class PoiCategory(val id: String, val label: String, val icon: String)

object PoiCategories {
  val all =
      listOf(
          PoiCategory("truck_parking", "Parcheggi TIR", "🅿"),
          PoiCategory("fuel", "Distributori", "⛽"),
          PoiCategory("services", "Aree di servizio", "🛣"),
          PoiCategory("rest_area", "Aree di sosta", "🌲"),
          PoiCategory("food", "Ristoranti e bar", "🍽"),
          PoiCategory("supermarket", "Supermercati", "🛒"),
          PoiCategory("shower", "Docce", "🚿"),
          PoiCategory("toilets", "Bagni", "🚻"),
          PoiCategory("truck_wash", "Lavaggio camion", "🧽"),
          PoiCategory("tyres", "Gommisti", "🛞"),
          PoiCategory("truck_repair", "Officine camion", "🔧"),
          PoiCategory("hotel", "Hotel e motel", "🛏"),
          PoiCategory("weighbridge", "Pese", "⚖"),
          PoiCategory("border", "Dogane / confini", "🛂"),
          PoiCategory("camper_site", "Aree camper", "🚐"),
          PoiCategory("camper_service", "Carico/scarico camper", "💧"),
          PoiCategory("camper_parking", "Parcheggi camper", "🅿"),
          PoiCategory("atm", "Bancomat", "🏧"),
          PoiCategory("pharmacy", "Farmacie", "💊"),
          PoiCategory("hospital", "Ospedali", "🏥"),
          PoiCategory("hgv_charging", "Ricarica camion", "🔌"),
      )

  fun byId(id: String) = all.firstOrNull { it.id == id }
}

@Serializable
data class Settings(
    val tollPolicy: TollPolicy = TollPolicy.ASK,
    /** Minutes more the driver accepts to save the toll (below this the app suggests it). */
    val tollMaxExtraMin: Int = 5,
    val askTightRamps: Boolean = true,
    val voiceWarnings: Boolean = true,
    val nightMode: NightMode = NightMode.AUTO,
    val tiltDeg: Int = 55,
    val poiCategories: Set<String> = setOf("truck_parking", "fuel", "services", "rest_area", "food", "shower"),
    val poiOnlyTruckFriendly: Boolean = true,
    val poiMaxDetourM: Int = 800,
    val driveTimeReminder: Boolean = true,
    val useEuropeGraph: Boolean = true,
    val wifiOnly: Boolean = true,
    /** Optional free Mapillary token: with it the street photos also come from Mapillary. */
    val mapillaryToken: String = "",
    val speedWarningKmh: Int = 5,
)

class SettingsStore(context: Context) {
  private val file = File(context.filesDir, "settings.json")
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }
  private val _settings = MutableStateFlow(load())
  val settings: StateFlow<Settings> = _settings.asStateFlow()

  private fun load(): Settings =
      try {
        if (file.exists()) json.decodeFromString(Settings.serializer(), file.readText()) else Settings()
      } catch (e: Exception) {
        Settings()
      }

  fun update(block: (Settings) -> Settings) {
    val s = block(_settings.value)
    _settings.value = s
    file.writeText(json.encodeToString(Settings.serializer(), s))
  }
}
