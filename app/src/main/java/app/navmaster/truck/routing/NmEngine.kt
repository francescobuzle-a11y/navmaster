package app.navmaster.truck.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.Tar
import it.navmaster.engine.RoutingGraph
import it.navmaster.engine.api.ApiException
import it.navmaster.engine.api.DirectionsLimits
import it.navmaster.engine.api.DirectionsRequest
import it.navmaster.engine.api.DirectionsService
import it.navmaster.engine.api.Json
import it.navmaster.engine.api.ResponseFormat
import it.navmaster.engine.api.RoadsService
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * navmaster-engine on the tablet: the graph of the country where the route starts (nm.tar.gz in
 * the country's package, unpacked once into its "nm" folder), opened memory-mapped and kept open.
 * The engine computes routes with openrouteservice's own logic and answers with openrouteservice's
 * GeoJSON, plus the facts about the roads of the route (limits, lanes, signs, tolls: `navmaster.roads`).
 * Null when the country has no graph yet (the map must be updated).
 */
class NmEngine(private val context: Context, private val regions: RegionManager) {
  private var graph: RoutingGraph? = null
  private var service: DirectionsService? = null
  private var loadedKey: String? = null

  /** Why the last route could not use the engine (shown under the routes), null when it could. */
  @Volatile var problem: String? = null
    private set

  /** The engine for a route starting at [lat], [lon], or null (then [problem] says why). */
  @Synchronized
  fun serviceAt(lat: Double, lon: Double): DirectionsService? {
    val region = regions.regionAt(lat, lon) ?: regions.installed.value.firstOrNull()
    if (region == null) {
      problem = "nessuna mappa scaricata in questa zona"
      return null
    }
    val dir = ready(region.dir)
    if (dir == null) {
      problem = "${region.label}: manca il grafo del motore NavMaster. In «Mappe d'Europa» tocca «Aggiorna»"
      Log.w(TAG, "engine: no graph in ${region.id}")
      return null
    }
    val key = dir.absolutePath + ":" + regions.version.value
    if (key != loadedKey) {
      close()
      val started = SystemClock.elapsedRealtime()
      try {
        val g = RoutingGraph.load(dir, true)
        graph = g
        service = DirectionsService(g, DirectionsLimits(50, 6_000_000.0, 6_000_000.0, 400.0), ENGINE_VERSION)
        loadedKey = key
        Log.i(TAG, "engine graph of ${region.id} opened in ${SystemClock.elapsedRealtime() - started} ms: ${g.profileNames} ch=${g.chStorages.keys}")
      } catch (e: Throwable) {
        problem = "${region.label}: il grafo del motore non si apre (${e.javaClass.simpleName}: ${e.message?.take(80)}). In «Mappe d'Europa» tocca «Aggiorna»"
        Log.e(TAG, "engine graph of ${region.id} not opened", e)
        close()
        return null
      }
    }
    problem = null
    return service
  }

  /**
   * openrouteservice's directions, computed here: [body] is the JSON openrouteservice would receive,
   * the answer its GeoJSON (or its error object, `{"error":{"code":2009,...}}`). Null when there is
   * no graph for the start point ([problem] says why).
   */
  fun directions(lat: Double, lon: Double, profile: String, body: String): String? {
    val s = serviceAt(lat, lon) ?: return null
    synchronized(this) {
      @Suppress("UNCHECKED_CAST")
      val parsed = Json.parse(body) as Map<String, Any?>
      return try {
        Json.stringify(s.handle(DirectionsRequest.fromJson(profile, parsed), ResponseFormat.GEOJSON))
      } catch (e: ApiException) {
        Json.stringify(s.errorBody(e))
      }
    }
  }

  /** The roads within [radiusM] of a point, from the graph (empty without a graph here). */
  fun roadsAround(lat: Double, lon: Double, radiusM: Double): List<Map<String, Any?>> {
    serviceAt(lat, lon) ?: return emptyList()
    synchronized(this) {
      val g = graph ?: return emptyList()
      @Suppress("UNCHECKED_CAST")
      return runCatching { RoadsService(g).around("driving-car", lat, lon, radiusM)["roads"] as List<Map<String, Any?>> }
          .onFailure { Log.w(TAG, "roads around: $it") }.getOrDefault(emptyList())
    }
  }

  /** The graph in use is closed (a country updated or removed): opened again at the next route. */
  @Synchronized
  fun reset() {
    close()
    problem = null
  }

  private fun close() {
    runCatching { graph?.close() }
    graph = null
    service = null
    loadedKey = null
  }

  companion object {
    private const val TAG = "NavMasterRoute"
    const val ENGINE_VERSION = "0.1.0"
    /** The graph of a country, packed (release dati-<country>). */
    const val PACKAGE = "nm.tar.gz"
    /** The folder it is unpacked into. */
    const val DIR = "nm"
    /** Which package the graph was unpacked from (the SHA-256 of its parts), to know when it changed. */
    const val SOURCE_FILE = ".package"

    /** The package the graph came from (see SOURCE_FILE), or null. */
    fun source(regionDir: File): String? = File(File(regionDir, DIR), SOURCE_FILE).takeIf { it.exists() }?.readText()?.trim()

    /** The graph of a country is there and complete. */
    fun installed(regionDir: File): Boolean = File(File(regionDir, DIR), "profiles").exists() && File(File(regionDir, DIR), "edges").exists()

    /** A graph (unpacked or still packed) is there. */
    fun present(regionDir: File): Boolean = installed(regionDir) || File(regionDir, PACKAGE).let { it.exists() && it.length() > 0 }

    /**
     * The "nm" folder of a country, unpacked from [PACKAGE] when a new one arrived (the package is
     * then removed, it is not needed any more). Null when the country has no graph.
     */
    @Synchronized
    fun ready(regionDir: File): File? {
      val dir = File(regionDir, DIR)
      val gz = File(regionDir, PACKAGE)
      if (gz.exists() && gz.length() > 0) {
        val started = SystemClock.elapsedRealtime()
        val tmp = File(regionDir, "$DIR.new")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
          GZIPInputStream(gz.inputStream().buffered(1 shl 20), 1 shl 16).use { Tar.extract(it, tmp) }
          if (!File(tmp, "profiles").exists() || !File(tmp, "edges").exists()) throw IllegalStateException("grafo incompleto")
          dir.deleteRecursively()
          if (!tmp.renameTo(dir)) throw IllegalStateException("impossibile installare il grafo")
          gz.delete()
          Log.i(TAG, "engine graph unpacked in ${regionDir.name} in ${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: Throwable) {
          Log.e(TAG, "engine graph of ${regionDir.name} not unpacked", e)
          tmp.deleteRecursively()
        }
      }
      return dir.takeIf { installed(regionDir) }
    }
  }
}
