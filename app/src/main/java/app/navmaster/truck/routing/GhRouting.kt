package app.navmaster.truck.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.Tar
import app.navmaster.truck.routing.gh.GhEngine
import app.navmaster.truck.routing.gh.TruckSpec
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * GraphHopper on the tablet: the graph of the country where the route starts (gh.tar.gz in the
 * country's package, unpacked once into its "gh" folder), opened memory-mapped and kept open.
 * Null when the country has no GraphHopper graph yet: the route is then Valhalla's alone.
 */
class GhRouting(private val context: Context, private val regions: RegionManager) {
  private var engine: GhEngine? = null
  private var loadedKey: String? = null

  /** The engine for a route starting at [lat], [lon], or null. */
  @Synchronized
  fun engineAt(lat: Double, lon: Double): GhEngine? {
    val region = regions.regionAt(lat, lon) ?: regions.installed.value.firstOrNull() ?: return null
    val dir = ready(region.dir) ?: return null
    val key = dir.absolutePath + ":" + regions.version.value
    if (key != loadedKey) {
      engine?.close()
      engine = null
      loadedKey = null
      val started = SystemClock.elapsedRealtime()
      engine = GhEngine.open(dir, modelsDir(), true)
      loadedKey = key
      Log.i(TAG, "GraphHopper ready on ${region.id} in ${SystemClock.elapsedRealtime() - started} ms")
    }
    return engine
  }

  /**
   * Up to [maxPaths] routes of [spec] through [points] ([lat, lon]) with GraphHopper (alternatives
   * only for a trip without stops), best first; null without a graph.
   */
  @Synchronized
  fun routes(points: List<DoubleArray>, headings: List<Double>, spec: TruckSpec, maxPaths: Int = 1): List<GhEngine.Result>? {
    val first = points.firstOrNull() ?: return null
    val e = runCatching { engineAt(first[0], first[1]) }.onFailure { Log.w(TAG, "GraphHopper not available: $it | ${it.stackTrace.take(6).joinToString(" < ")}", it) }.getOrNull()
        ?: return null
    return e.routes(points, headings, spec, maxPaths)
  }

  @Synchronized
  fun reset() {
    engine?.close()
    engine = null
    loadedKey = null
  }

  /** The base model, from the app's assets, where GraphHopper reads it (same file as on GitHub). */
  private fun modelsDir(): File {
    val d = File(context.filesDir, "gh-models").apply { mkdirs() }
    val f = File(d, GhEngine.BASE_MODEL_FILE)
    val text = context.assets.open("gh/" + GhEngine.BASE_MODEL_FILE).bufferedReader().use { it.readText() }
    if (!f.exists() || f.readText() != text) f.writeText(text)
    return d
  }

  companion object {
    private const val TAG = "NavMasterRoute"
    const val PACKAGE = "gh.tar.gz"

    /**
     * The "gh" folder of a country, unpacked from [PACKAGE] when a new one arrived (the package is
     * then removed, it is not needed any more). Null when the country has no graph.
     */
    @Synchronized
    fun ready(regionDir: File): File? {
      val dir = File(regionDir, "gh")
      val gz = File(regionDir, PACKAGE)
      if (gz.exists() && gz.length() > 0) {
        val started = SystemClock.elapsedRealtime()
        val tmp = File(regionDir, "gh.new")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
          GZIPInputStream(gz.inputStream().buffered(1 shl 20), 1 shl 16).use { Tar.extract(it, tmp) }
          if (!File(tmp, "properties").exists()) throw IllegalStateException("grafo GraphHopper incompleto")
          dir.deleteRecursively()
          if (!tmp.renameTo(dir)) throw IllegalStateException("impossibile installare il grafo GraphHopper")
          gz.delete()
          Log.i(TAG, "GraphHopper graph unpacked in ${regionDir.name} in ${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: Exception) {
          Log.e(TAG, "GraphHopper graph of ${regionDir.name} not unpacked", e)
          tmp.deleteRecursively()
        }
      }
      return dir.takeIf { File(it, "properties").exists() }
    }
  }
}

/** The vehicle and the trip's choices as GraphHopper needs them. */
fun app.navmaster.truck.vehicle.VehicleProfile.ghSpec(loadT: Double, trip: app.navmaster.truck.vehicle.TripOptions): TruckSpec {
  val v = this
  return TruckSpec().apply {
    // the bans for lorries (hgv=no) are for goods vehicles over 3.5 t, not for buses and campers
    hgv = v.isHgv
    heightM = v.heightM
    widthM = v.widthM
    lengthM = v.lengthM
    weightT = v.tripWeightT(loadT)
    axleLoadT = v.axleLoadT
    hazmat = v.adr != app.navmaster.truck.vehicle.AdrTunnel.NONE
    tunnelCode = if (v.adr == app.navmaster.truck.vehicle.AdrTunnel.NONE) '\u0000' else v.adr.name[0]
    hazmatWater = v.hazmatWater
    topSpeedKmh = v.topSpeedKmh.toDouble()
    avoidTolls = trip.avoidTolls
    avoidFerries = v.avoidFerries
    avoidUnpaved = v.avoidUnpaved
    preferTruckRoutes = v.preferTruckRoutes
    shortest = trip.shortest
    // the zones are rings of [lon, lat] for Valhalla, [lat, lon] here
    for (ring in trip.excludePolygons) {
      avoidZones.add(ring.map { p -> doubleArrayOf(p[1], p[0]) }.toTypedArray())
    }
  }
}
