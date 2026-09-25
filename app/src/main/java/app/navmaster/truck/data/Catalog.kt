package app.navmaster.truck.data

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import app.navmaster.truck.core.Geo
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class EuropeTiles(val tiles: Int = 0, val size: Long = 0, val parts: List<PackagePart> = emptyList())

@Serializable
data class CountryInfo(
    val id: String,
    val iso: String,
    val name: String,
    val available: Boolean = false,
    val bbox: List<Double>? = null,
    val poly: List<List<List<Double>>>? = null,
    val built: String? = null,
    val size: Long = 0,
    val files: Map<String, Long> = emptyMap(),
    @SerialName("europe_tiles") val europeTiles: EuropeTiles? = null,
) {
  fun contains(lat: Double, lon: Double): Boolean {
    val b = bbox ?: return false
    if (lon < b[0] || lat < b[1] || lon > b[2] || lat > b[3]) return false
    return poly?.let { Geo.inPolygon(lat, lon, it) } ?: true
  }

  /** What the download costs on the tablet (with the Europe graph instead of the country graph). */
  fun downloadSize(useEurope: Boolean): Long {
    val tiles = europeTiles
    return if (useEurope && tiles != null) size - (files["percorsi.tar"] ?: 0) + tiles.size else size
  }

  val flag: String
    get() =
        if (iso.length == 2 && iso != "XK")
            iso.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
        else "🏳"
}

@Serializable
data class EuropeGraph(val available: Boolean = false, val built: String? = null, val valhalla: String? = null)

@Serializable
data class Catalog(
    val format: Int = 1,
    val built: String? = null,
    val countries: List<CountryInfo> = emptyList(),
    @SerialName("europe_graph") val europeGraph: EuropeGraph = EuropeGraph(),
)

/**
 * The list of European countries that can be downloaded (catalog.json on the dati-catalogo
 * release). Kept on the tablet so that it works offline too; refreshed whenever there is a
 * connection. It also tells in which country a position is.
 */
class CatalogStore(private val context: Context) {
  private val file = File(context.filesDir, "catalog.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
  private val _catalog = MutableStateFlow(load())
  val catalog: StateFlow<Catalog?> = _catalog.asStateFlow()

  private fun load(): Catalog? =
      try {
        if (file.exists()) json.decodeFromString(Catalog.serializer(), file.readText()) else null
      } catch (e: Exception) {
        null
      }

  suspend fun refresh(): Boolean =
      withContext(Dispatchers.IO) {
        try {
          client.newCall(Request.Builder().url("${RegionManager.RELEASES}/dati-catalogo/catalog.json").build()).execute().use {
            if (!it.isSuccessful) return@withContext false
            val text = it.body.string()
            val c = json.decodeFromString(Catalog.serializer(), text)
            file.writeText(text)
            _catalog.value = c
            Log.i(TAG, "catalog: ${c.countries.count { x -> x.available }} countries, europe graph ${c.europeGraph.available}")
            true
          }
        } catch (e: Exception) {
          Log.w(TAG, "catalog refresh: $e")
          false
        }
      }

  fun country(id: String): CountryInfo? = _catalog.value?.countries?.firstOrNull { it.id == id }

  fun countryAt(lat: Double, lon: Double): CountryInfo? = _catalog.value?.countries?.firstOrNull { it.contains(lat, lon) }

  fun countryByIso(iso: String): CountryInfo? = _catalog.value?.countries?.firstOrNull { it.iso.equals(iso, true) }

  /**
   * The country of the mobile network (or of the SIM): known without any location permission and
   * without a GPS fix, so the app can suggest the right map at the very first start.
   */
  fun networkCountryIso(): String? {
    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val iso = tm?.networkCountryIso?.takeIf { it.length == 2 } ?: tm?.simCountryIso?.takeIf { it.length == 2 }
    return (iso ?: Locale.getDefault().country.takeIf { it.length == 2 })?.uppercase()
  }

  companion object {
    private const val TAG = "NavMasterCatalog"
  }
}
