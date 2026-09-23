package app.navmaster.truck.vehicle

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Profiles of the tablet plus the choice made at the start of the trip (profile and load). */
@Serializable
data class Garage(
    val profiles: List<VehicleProfile> = VehicleProfile.defaults(),
    val activeId: String = "camion",
    val loadT: Double = 10.0,
) {
  val active: VehicleProfile
    get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
}

class ProfileStore(context: Context) {
  private val file = File(context.filesDir, "garage.json")
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
  }
  private val _garage = MutableStateFlow(load())
  val garage: StateFlow<Garage> = _garage.asStateFlow()

  private fun load(): Garage =
      try {
        if (file.exists()) json.decodeFromString(Garage.serializer(), file.readText()) else Garage()
      } catch (e: Exception) {
        Garage()
      }

  fun update(block: (Garage) -> Garage) {
    val g = block(_garage.value)
    _garage.value = g
    file.writeText(json.encodeToString(Garage.serializer(), g))
  }

  fun select(id: String) = update { it.copy(activeId = id) }

  fun setLoad(loadT: Double) = update { it.copy(loadT = loadT.coerceIn(0.0, 60.0)) }

  fun save(profile: VehicleProfile) = update { g ->
    val others = g.profiles.filterNot { it.id == profile.id }
    g.copy(profiles = others + profile)
  }
}
