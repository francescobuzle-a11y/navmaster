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

  /** Why the last route could not use GraphHopper (shown under the routes), null when it could. */
  @Volatile var problem: String? = null
    private set

  /** The engine for a route starting at [lat], [lon], or null. */
  @Synchronized
  fun engineAt(lat: Double, lon: Double): GhEngine? {
    val region = regions.regionAt(lat, lon) ?: regions.installed.value.firstOrNull()
    if (region == null) {
      problem = "nessuna mappa scaricata in questa zona"
      return null
    }
    ready(region.dir)
    when (state(region.dir)) {
      GhState.MISSING -> {
        problem = "${region.label}: manca il grafo GraphHopper. In «Mappe d'Europa» tocca «Aggiorna» (scarica solo il grafo)"
        Log.w(TAG, "GraphHopper: no graph in ${region.id}")
        return null
      }
      GhState.OLD -> {
        problem = "${region.label}: il grafo GraphHopper è di una versione vecchia. In «Mappe d'Europa» tocca «Aggiorna»"
        Log.w(TAG, "GraphHopper: old graph in ${region.id} (no nm_truck/nm_car landmarks)")
        return null
      }
      GhState.READY -> {}
    }
    val dir = File(region.dir, "gh")
    val key = dir.absolutePath + ":" + regions.version.value
    if (key != loadedKey) {
      engine?.close()
      engine = null
      loadedKey = null
      val started = SystemClock.elapsedRealtime()
      try {
        engine = GhEngine.open(dir, modelsDir(), true)
      } catch (e: Exception) {
        problem = "${region.label}: il grafo GraphHopper non si apre (${e.message?.take(80)}). In «Mappe d'Europa» tocca «Aggiorna»"
        throw e
      }
      loadedKey = key
      Log.i(TAG, "GraphHopper ready on ${region.id} in ${SystemClock.elapsedRealtime() - started} ms")
    }
    problem = null
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

  /** The base models, from the app's assets, where GraphHopper reads them (same files as on GitHub). */
  private fun modelsDir(): File {
    val d = File(context.filesDir, "gh-models").apply { mkdirs() }
    for (name in listOf(GhEngine.TRUCK_MODEL_FILE, GhEngine.CAR_MODEL_FILE)) {
      val f = File(d, name)
      val text = context.assets.open("gh/$name").bufferedReader().use { it.readText() }
      if (!f.exists() || f.readText() != text) f.writeText(text)
    }
    return d
  }

  /** The GraphHopper graph of a country: not there, made for an older version of the app, ready. */
  enum class GhState { MISSING, OLD, READY }

  companion object {
    private const val TAG = "NavMasterRoute"
    const val PACKAGE = "gh.tar.gz"

    /** Which package the graph was unpacked from (the SHA-256 of its parts), to know when it changed. */
    const val SOURCE_FILE = ".package"

    /**
     * The state of the graph in [regionDir]: the graphs made for this app have the landmarks of
     * both profiles (camion: nm_truck, camper/auto: nm_car); the first graph had one profile only.
     */
    fun state(regionDir: File): GhState {
      val dir = File(regionDir, "gh")
      if (!File(dir, "properties").exists()) return GhState.MISSING
      val ok = listOf(GhEngine.PROFILE_TRUCK, GhEngine.PROFILE_CAR).all { File(dir, "landmarks_$it").exists() }
      return if (ok) GhState.READY else GhState.OLD
    }

    /** The package the graph came from (see SOURCE_FILE), or null. */
    fun source(regionDir: File): String? = File(File(regionDir, "gh"), SOURCE_FILE).takeIf { it.exists() }?.readText()?.trim()

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
