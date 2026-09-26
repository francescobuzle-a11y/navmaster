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

/** Which side of the screen the places along the route go. */
@Serializable
enum class PoiSide(val label: String) {
  AUTO("Automatico"),
  RIGHT("Destra"),
  LEFT("Sinistra"),
}

/**
 * A finer choice inside a kind of place: [flag] is what poi.sqlite marks on the place. A "kind"
 * (restaurant, fast food, cafe) is one of several alternatives, a feature (AdBlue, free, guarded)
 * is something the place has or not.
 */
data class PoiSub(val flag: String, val label: String, val kind: Boolean = false)

/** One kind of place the driver can choose to see along the route. */
data class PoiCategory(val id: String, val label: String, val icon: String, val subs: List<PoiSub> = emptyList())

/** Kinds of place grouped as the driver thinks of them. */
data class PoiGroup(val id: String, val label: String, val icon: String, val categories: List<PoiCategory>)

object PoiCategories {
  private val h24 = PoiSub("h24", "Aperti 24 h")
  private val free = PoiSub("free", "Gratuiti")

  val groups =
      listOf(
          PoiGroup("rest", "Sosta e riposo", "🅿", listOf(
              PoiCategory("truck_parking", "Parcheggi TIR", "🅿", listOf(free, PoiSub("guarded", "Custoditi"))),
              PoiCategory("services", "Aree di servizio", "🛣", listOf(PoiSub("hgv", "Con posti per camion"))),
              PoiCategory("rest_area", "Aree di sosta", "🌲", listOf(PoiSub("hgv", "Con posti per camion"))),
              PoiCategory("hotel", "Hotel e motel", "🛏"),
          )),
          PoiGroup("energy", "Rifornimento", "⛽", listOf(
              PoiCategory("fuel", "Distributori", "⛽", listOf(PoiSub("adblue", "Con AdBlue"), PoiSub("lpg", "Con GPL"), h24)),
              PoiCategory("hgv_charging", "Ricarica camion", "🔌"),
          )),
          PoiGroup("food", "Mangiare e spesa", "🍽", listOf(
              PoiCategory("food", "Ristoranti e bar", "🍽", listOf(
                  PoiSub("restaurant", "Ristoranti", kind = true), PoiSub("fast_food", "Fast food", kind = true),
                  PoiSub("cafe", "Bar e caffè", kind = true))),
              PoiCategory("supermarket", "Supermercati", "🛒"),
          )),
          PoiGroup("hygiene", "Igiene", "🚿", listOf(
              PoiCategory("shower", "Docce", "🚿", listOf(free)),
              PoiCategory("toilets", "Bagni", "🚻", listOf(free)),
          )),
          PoiGroup("vehicle", "Assistenza al mezzo", "🔧", listOf(
              PoiCategory("truck_repair", "Officine camion", "🔧"),
              PoiCategory("tyres", "Gommisti", "🛞", listOf(PoiSub("hgv", "Per mezzi pesanti"))),
              PoiCategory("truck_wash", "Lavaggio camion", "🧽"),
              PoiCategory("weighbridge", "Pese", "⚖"),
          )),
          PoiGroup("camper", "Camper", "🚐", listOf(
              PoiCategory("camper_site", "Aree camper", "🚐", listOf(PoiSub("free", "Gratuite"))),
              PoiCategory("camper_parking", "Parcheggi camper", "🅿"),
              PoiCategory("camper_service", "Carico/scarico camper", "💧"),
          )),
          PoiGroup("other", "Salute, soldi e confini", "🏥", listOf(
              PoiCategory("pharmacy", "Farmacie", "💊"),
              PoiCategory("hospital", "Ospedali", "🏥"),
              PoiCategory("atm", "Bancomat", "🏧"),
              PoiCategory("border", "Dogane / confini", "🛂"),
          )),
      )

  val all = groups.flatMap { it.categories }

  fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** How much the voice talks. */
@Serializable
enum class VoiceLevel(val label: String, val detail: String) {
  ESSENTIAL("Essenziale", "Una volta prima della manovra e alla manovra; il resto lo mostra lo schermo"),
  NORMAL("Normale", "Anche il preavviso a circa 2 km su autostrade e superstrade"),
  FULL("Completa", "Come Normale: le manovre arrivano sempre una alla volta, al momento giusto"),
}

/** How the map looks while driving. */
@Serializable
enum class DriveView(val label: String) {
  VIEW_3D("3D inclinata"),
  VIEW_2D("2D dall'alto"),
}

@Serializable
data class Settings(
    val tollPolicy: TollPolicy = TollPolicy.ASK,
    /** Minutes more the driver accepts to save the toll (below this the app suggests it). */
    val tollMaxExtraMin: Int = 5,
    val askTightRamps: Boolean = true,
    val voiceWarnings: Boolean = true,
    val voiceLevel: VoiceLevel = VoiceLevel.ESSENTIAL,
    /** The picture of the junction with lanes and direction signs, at exits and complex turns. */
    val junctionView: Boolean = true,
    /** Fixed speed cameras (where the law allows the warning). */
    val speedCameras: Boolean = true,
    val nightMode: NightMode = NightMode.AUTO,
    val tiltDeg: Int = 55,
    val driveView: DriveView = DriveView.VIEW_3D,
    val poiCategories: Set<String> = setOf("truck_parking", "fuel", "services", "rest_area", "food", "shower"),
    val poiOnlyTruckFriendly: Boolean = true,
    /** Finer choices, as "category:flag" (none for a category = all its places). */
    val poiSubs: Set<String> = emptySet(),
    val poiMaxDetourM: Int = 800,
    /** How many places the side panel shows (0 = never). */
    val poiRailCount: Int = 3,
    /** Seconds the panel stays open when new places come up (0 = always open). */
    val poiRailSeconds: Int = 12,
    /** Opacity of the panel, percent. */
    val poiRailOpacity: Int = 70,
    val poiRailSide: PoiSide = PoiSide.AUTO,
    val driveTimeReminder: Boolean = true,
    val useEuropeGraph: Boolean = true,
    val wifiOnly: Boolean = true,
    /** Optional free Mapillary token: with it the street photos also come from Mapillary. */
    val mapillaryToken: String = "",
    val speedWarningKmh: Int = 5,
    /** Reports of the other NavMaster drivers on the road ahead (and the button to send one). */
    val liveReports: Boolean = true,
    /** Official traffic information (road operators; TomTom / HERE with the driver's free key). */
    val liveTraffic: Boolean = true,
    /** Free TomTom key (developer.tomtom.com): incidents and queues of all Europe, traffic on the map. */
    val tomtomKey: String = "",
    /** Defaults applied once on a real tablet (1: Waze on), so the app comes ready to use. */
    val readyDefaults: Int = 0,
    /** Free HERE key (platform.here.com): incidents of all Europe. */
    val hereKey: String = "",
    /** Official traffic information of the European countries (national access points, open data). */
    val nationalTraffic: Boolean = true,
    /** Free key of Trafikverket (Sweden), asked by the driver. */
    val trafikverketKey: String = "",
    /** Traffic colours on the map roads (with the TomTom key). */
    val trafficOnMap: Boolean = false,
    /** The ntfy server the reports travel on (the public one, or one's own). */
    val reportsServer: String = "https://ntfy.sh",
    /**
     * Police checks and speed cameras shown in every country, also where the law forbids warning
     * about them (Germany, Switzerland; France only "control zone"): the driver's choice.
     */
    val enforcementEverywhere: Boolean = true,
    /**
     * Personal test only: reports read from a server the owner runs on his own computer (format of
     * the waze-api server used by the JMoore335 script). Off by default, never shared.
     */
    val personalFeed: Boolean = false,
    val personalFeedUrl: String = "",
    /** true: the tablet asks the Waze Live Map itself (no computer); false: the owner's own server. */
    val personalFeedDirect: Boolean = true,
    /** Random tag of this device, to count a driver's confirmations once (not who he is). */
    val deviceTag: String = "",
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
