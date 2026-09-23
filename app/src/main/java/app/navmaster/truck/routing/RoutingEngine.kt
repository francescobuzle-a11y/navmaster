package app.navmaster.truck.routing

import android.content.Context
import android.util.Log
import app.navmaster.truck.data.RegionManager
import com.valhalla.config.ValhallaConfigBuilder
import com.valhalla.valhalla.Valhalla

/** One Valhalla instance on the tile extract of the installed region, created when first needed. */
class RoutingEngine(private val context: Context, private val regions: RegionManager) {
  private var valhalla: Valhalla? = null
  private var loadedFrom: String? = null

  @Synchronized
  fun get(): Valhalla? {
    val tar = regions.active()?.routingTar ?: return null
    if (!tar.exists()) return null
    if (tar.absolutePath != loadedFrom) {
      valhalla?.close()
      val config = ValhallaConfigBuilder().withTileExtract(tar.absolutePath).build()
      valhalla = Valhalla(context, config)
      loadedFrom = tar.absolutePath
      Log.i("NavMasterRoute", "Valhalla ready on ${tar.absolutePath} (${tar.length() / 1_000_000} MB)")
    }
    return valhalla
  }

  @Synchronized
  fun reset() {
    valhalla?.close()
    valhalla = null
    loadedFrom = null
  }
}
