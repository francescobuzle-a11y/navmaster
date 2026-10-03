package app.navmaster.truck.routing

import android.content.Context
import android.util.Log
import app.navmaster.truck.data.RegionManager
import com.valhalla.config.ValhallaConfigBuilder
import com.valhalla.config.models.ServiceLimitsTrace
import com.valhalla.config.models.ValhallaConfig
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
    // the country where the route starts: a country installed with its own graph (no European
    // tiles, e.g. an older package) keeps using it, even when other countries are on the Europe graph
    val here = if (lat != null && lon != null) regions.regionAt(lat, lon) else null
    val ownGraph = here?.takeIf { !it.hasEuropeTiles && it.routingTar.exists() }
    if (regions.europeTilesInstalled() && ownGraph == null) {
      key = "dir:" + regions.europeTiles.absolutePath + ":" + regions.version.value
      builder.withTileDir(regions.europeTiles.absolutePath)
    } else {
      val region = ownGraph ?: here?.takeIf { it.routingTar.exists() }
          ?: regions.installed.value.firstOrNull { it.routingTar.exists() }
          ?: return null
      val tar = region.routingTar
      if (!tar.exists()) return null
      key = "tar:" + tar.absolutePath
      builder.withTileExtract(tar.absolutePath)
    }
    if (key != loadedKey) {
      valhalla?.close()
      valhalla = Valhalla(context, withLongTraces(builder.build()))
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

  /**
   * GraphHopper's routes are followed by Valhalla's map matching (trace_route), whose limits are
   * made for GPS traces (200 km, 16,000 points): raised to whole trips across Europe.
   */
  private fun withLongTraces(config: ValhallaConfig): ValhallaConfig {
    val limits = config.serviceLimits ?: return config
    val trace = (limits.trace ?: ServiceLimitsTrace()).copy(maxDistance = 4_000_000, maxShape = 400_000, maxSearchRadius = 100)
    return config.copy(serviceLimits = limits.copy(trace = trace))
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
