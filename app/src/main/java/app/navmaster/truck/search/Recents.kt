package app.navmaster.truck.search

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import uniffi.ferrostar.GeographicCoordinate

@Serializable
data class Recent(val title: String, val detail: String, val lat: Double, val lon: Double, val icon: String = "🕘") {
  fun toFound() = Found(title, detail, GeographicCoordinate(lat, lon), icon)
}

/** Last destinations, most recent first. */
class RecentStore(context: Context) {
  private val file = File(context.filesDir, "recenti.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val _items = MutableStateFlow(load())
  val items: StateFlow<List<Recent>> = _items.asStateFlow()

  private fun load(): List<Recent> =
      try {
        if (file.exists()) json.decodeFromString(ListSerializer(Recent.serializer()), file.readText()) else emptyList()
      } catch (e: Exception) {
        emptyList()
      }

  fun add(f: Found) {
    val r = Recent(f.title, f.detail, f.coordinate.lat, f.coordinate.lng)
    val list = (listOf(r) + _items.value.filterNot { it.title == r.title && it.detail == r.detail }).take(15)
    _items.value = list
    runCatching { file.writeText(json.encodeToString(ListSerializer(Recent.serializer()), list)) }
  }
}
