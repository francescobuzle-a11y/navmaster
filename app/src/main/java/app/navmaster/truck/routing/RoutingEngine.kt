package app.navmaster.truck.routing

import android.content.Context
import android.util.Log
import app.navmaster.truck.data.RegionManager
import com.valhalla.config.ValhallaConfigBuilder
import com.valhalla.valhalla.Valhalla

/**
 * One Valhalla instance on the offline graph. With the Europe graph every installed country is in
 * one tile folder, so routes cross borders; otherwise the graph of the country where the route
 * starts is used.
 */
class RoutingEngine(private val context: Context, private val regions: RegionManager) {
  private var valhalla: Valhalla? = null
  private var loadedKey: String? = null

  @Synchronized
  fun get(lat: Double? = null, lon: Double? = null): Valhalla? {
    val key: String
    val builder = ValhallaConfigBuilder()
    if (regions.europeTilesInstalled()) {
      key = "dir:" + regions.europeTiles.absolutePath + ":" + regions.version.value
      builder.withTileDir(regions.europeTiles.absolutePath)
    } else {
      val region = (if (lat != null && lon != null) regions.regionAt(lat, lon) else null)
          ?: regions.installed.value.firstOrNull { it.routingTar.exists() }
          ?: return null
      val tar = region.routingTar
      if (!tar.exists()) return null
      key = "tar:" + tar.absolutePath
      builder.withTileExtract(tar.absolutePath)
    }
    if (key != loadedKey) {
      valhalla?.close()
      valhalla = Valhalla(context, builder.build())
      loadedKey = key
      Log.i(TAG, "Valhalla ready on $key")
    }
    return valhalla
  }

  /** Runs one request on the engine; requests are serialised (the native actor is not reentrant). */
  @Synchronized
  fun <T> use(lat: Double?, lon: Double?, block: (Valhalla) -> T): T {
    val v = get(lat, lon) ?: throw IllegalStateException("Mappe offline non installate: scarica prima il Paese")
    return block(v)
  }

  @Synchronized
  fun reset() {
    valhalla?.close()
    valhalla = null
    loadedKey = null
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
