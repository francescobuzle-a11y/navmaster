package app.navmaster.truck.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.Tar
import app.navmaster.truck.routing.gh.GhCross
import app.navmaster.truck.routing.gh.GhEngine
import app.navmaster.truck.routing.gh.GhGuide
import uniffi.ferrostar.Route
import app.navmaster.truck.routing.gh.TruckSpec
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * GraphHopper on the tablet: the graph of the country where the route starts (gh.tar.gz in the
 * country's package, unpacked once into its "gh" folder, plus the landmarks of the vehicles the
 * driver chose: gh-camion.tar.gz, gh-auto.tar.gz), opened memory-mapped and kept open.
 * Null when the country has no GraphHopper graph yet: the route is then Valhalla's alone.
 */
class GhRouting(private val context: Context, private val regions: RegionManager) {
  private var engine: GhEngine? = null
  private var loadedKey: String? = null

  /** The graphs of the other countries of a trip abroad (country → (key, engine)), the last used last. */
  private val others = LinkedHashMap<String, Pair<String, GhEngine>>()

  /** The border crossings of each country (valichi.json), read once. */
  private val crossingCache = HashMap<String, List<GhCross.Crossing>>()

  /** The trips abroad of the last routes (their pieces in each country's graph), for the guidance. */
  private val trips = java.util.WeakHashMap<GhEngine.Result, GhCross.Trip>()

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
    // the graph itself (the landmarks of the vehicle are checked for each route)
    when (state(region.dir, emptyList())) {
      GhState.MISSING -> {
        problem = "${region.label}: manca il grafo GraphHopper. In «Mappe d'Europa» tocca «Aggiorna» (scarica solo il grafo)"
        Log.w(TAG, "GraphHopper: no graph in ${region.id}")
        return null
      }
      GhState.OLD -> {
        problem = "${region.label}: il grafo GraphHopper è di una versione vecchia. In «Mappe d'Europa» tocca «Aggiorna»"
        Log.w(TAG, "GraphHopper: old graph in ${region.id} (made before the openrouteservice rules)")
        return null
      }
      GhState.READY, GhState.PARTIAL -> {}
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
    if (!e.canRoute(spec)) {
      // the data of this kind of vehicle were not downloaded (only lorries, or only the others)
      problem = "manca il calcolo GraphHopper per ${if (spec.hgv) "i camion" else "camper, bus e auto"}: in «Mappe d'Europa» " +
          "scegli per quali mezzi e tocca «Aggiorna»"
      Log.w(TAG, "GraphHopper: no landmarks for ${GhEngine.profileFor(spec)}")
      return null
    }
    // a trip abroad: the graphs of the countries joined at the border crossings
    val ids = points.map { regions.regionAt(it[0], it[1])?.id }
    if (ids.distinct().size > 1) return across(points, headings, ids, spec)
    return e.routes(points, headings, spec, maxPaths)
  }

  /** The route of a trip through more countries (GhCross): one result, the whole route. */
  private fun across(points: List<DoubleArray>, headings: List<Double>, ids: List<String?>, spec: TruckSpec): List<GhEngine.Result>? {
    if (ids.any { it == null }) {
      problem = "manca la mappa di uno dei Paesi del viaggio: scaricala in «Mappe d'Europa»"
      return null
    }
    val graphs = object : GhCross.Graphs {
      override fun has(country: String): Boolean {
        val region = regions.installed.value.firstOrNull { it.id == country } ?: return false
        return state(region.dir, emptyList()).let { it == GhState.READY || it == GhState.PARTIAL } &&
            GhEngine.hasLandmarks(File(region.dir, "gh"), GhEngine.profileFor(spec))
      }

      override fun engine(country: String): GhEngine? =
          runCatching { engineFor(country) }.onFailure { Log.w(TAG, "GraphHopper of $country not open: $it") }.getOrNull()

      override fun crossings(country: String): List<GhCross.Crossing> = crossingsOf(country)
    }
    val trip = GhCross.route(graphs, points, ids.map { it!! }, headings.firstOrNull() ?: Double.NaN, spec)
    if (trip.error != null || trip.whole == null) {
      problem = "percorso tra Paesi: ${trip.error}"
      Log.w(TAG, "GraphHopper across countries: ${trip.error} (${trip.note})")
      return null
    }
    Log.i(TAG, String.format(java.util.Locale.US, "GraphHopper across countries: %.1f km, %d min, %s, %d ms",
        trip.whole.distanceM / 1000, trip.whole.timeMs / 60000, trip.note, trip.whole.computeMs))
    synchronized(trips) { trips[trip.whole] = trip }
    return listOf(trip.whole)
  }

  /** The graph of the country [id] (installed), opened once and kept with the others of the trip. */
  @Synchronized
  private fun engineFor(id: String): GhEngine? {
    val region = regions.installed.value.firstOrNull { it.id == id } ?: return null
    ready(region.dir)
    val dir = File(region.dir, "gh")
    val key = dir.absolutePath + ":" + regions.version.value
    if (key == loadedKey) return engine
    others[id]?.let { (k, e) ->
      if (k == key) {
        others.remove(id)
        others[id] = k to e
        return e
      }
      e.close()
      others.remove(id)
    }
    if (state(region.dir, emptyList()).let { it != GhState.READY && it != GhState.PARTIAL }) return null
    val e = GhEngine.open(dir, modelsDir(), true)
    others[id] = key to e
    // at most three other countries' graphs open
    while (others.size > 3) {
      val eldest = others.keys.first()
      others.remove(eldest)?.second?.close()
    }
    return e
  }

  /** The border crossings of the country [id] (valichi.json in its package), empty when not there. */
  private fun crossingsOf(id: String): List<GhCross.Crossing> = synchronized(crossingCache) {
    crossingCache.getOrPut(id) {
      val region = regions.installed.value.firstOrNull { it.id == id }
      val f = region?.let { File(it.dir, "valichi.json") }
      if (f != null && f.exists()) GhCross.read(f) else emptyList()
    }
  }

  /**
   * The guidance along [r] worked out from the graph that computed it (GhGuide): an OSRM answer
   * for Ferrostar and the roads of the route for the analysis. Null when the graph was built
   * without the guidance data (older packages: Valhalla guides then).
   */
  @Synchronized
  fun guide(r: GhEngine.Result, spec: TruckSpec, breaks: IntArray, requested: List<DoubleArray>): GhGuide.Output? {
    synchronized(trips) { trips[r] }?.let { trip ->
      if (!trip.engines.all { it.canGuide() }) return null
      return GhEngine.guide(trip, spec, breaks, requested)
    }
    val e = engine ?: return null
    if (!e.canGuide()) return null
    return e.guide(r, spec, breaks, requested)
  }

  // the roads of the routes guided by GraphHopper (trace_attributes form), for RouteAnalysis
  private val attributes = object : LinkedHashMap<String, String>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 24
  }

  /** Keeps the roads of [route] (computed and guided by GraphHopper) for its analysis. */
  fun remember(route: Route, json: String) = synchronized(attributes) { attributes[keyOf(route)] = json }

  /** The roads of [route] when GraphHopper guided it, else null. */
  fun attributesFor(route: Route): String? = synchronized(attributes) { attributes[keyOf(route)] }

  private fun keyOf(route: Route): String {
    val g = route.geometry
    return "${g.size}:${g.firstOrNull()}:${g.lastOrNull()}:${route.distance.toLong()}"
  }

  /**
   * GraphHopper's path along the line of a route computed elsewhere ([r], openrouteservice's), in
   * the graph of the country where it starts; null without a graph that can guide it.
   */
  @Synchronized
  fun follow(r: GhEngine.Result, spec: TruckSpec): GhEngine.Result? {
    if (r.lat.size < 2) return null
    val e = runCatching { engineAt(r.lat[0], r.lon[0]) }.getOrNull() ?: return null
    if (!e.canGuide() || !e.canRoute(spec)) return null
    return e.follow(r.lat, r.lon, r.waypointIndex, r.distanceM, spec)
  }

  @Synchronized
  fun reset() {
    engine?.close()
    engine = null
    loadedKey = null
    for ((_, e) in others.values) e.close()
    others.clear()
    synchronized(crossingCache) { crossingCache.clear() }
  }

  /** The base models, from the app's assets, where GraphHopper reads them (same files as on GitHub). */
  private fun modelsDir(): File {
    val d = File(context.filesDir, "gh-models").apply { mkdirs() }
    for (name in GhEngine.modelFiles()) {
      val f = File(d, name)
      val text = context.assets.open("gh/$name").bufferedReader().use { it.readText() }
      if (!f.exists() || f.readText() != text) f.writeText(text)
    }
    return d
  }

  /**
   * The GraphHopper graph of a country: not there, made for an older version of the app, without
   * the data of a kind of vehicle the driver wants, ready.
   */
  enum class GhState { MISSING, OLD, PARTIAL, READY }

  companion object {
    private const val TAG = "NavMasterRoute"
    /** The graph without the landmarks. */
    const val PACKAGE = "gh.tar.gz"
    /** The landmarks of the lorry profiles and of the other vehicles' ones. */
    const val PACKAGE_TRUCK = "gh-camion.tar.gz"
    const val PACKAGE_CAR = "gh-auto.tar.gz"
    val PACKAGES = listOf(PACKAGE, PACKAGE_TRUCK, PACKAGE_CAR)

    /** Which package the graph was unpacked from (the SHA-256 of its parts), to know when it changed. */
    const val SOURCE_FILE = ".package"

    fun sourceFile(pkg: String) = if (pkg == PACKAGE) SOURCE_FILE else ".package-$pkg"

    /** The landmark packages for the vehicles chosen in the settings ([hgvInUse]: the vehicle in use is a lorry). */
    fun wanted(choice: app.navmaster.truck.settings.OfflineVehicles, hgvInUse: Boolean): List<String> = when (choice) {
      app.navmaster.truck.settings.OfflineVehicles.AUTO -> listOf(if (hgvInUse) PACKAGE_TRUCK else PACKAGE_CAR)
      app.navmaster.truck.settings.OfflineVehicles.TRUCK -> listOf(PACKAGE_TRUCK)
      app.navmaster.truck.settings.OfflineVehicles.CAR -> listOf(PACKAGE_CAR)
      app.navmaster.truck.settings.OfflineVehicles.BOTH -> listOf(PACKAGE_TRUCK, PACKAGE_CAR)
    }

    /** The landmark packages wanted now, from the settings and the vehicle in use. */
    fun wantedNow(): List<String> = wanted(
        app.navmaster.truck.AppGraph.settings.settings.value.offlineVehicles,
        app.navmaster.truck.AppGraph.profiles.garage.value.active.isHgv,
    )

    /** The landmarks of a package are in the graph folder. */
    fun hasPackage(regionDir: File, pkg: String): Boolean {
      val dir = File(regionDir, "gh")
      return when (pkg) {
        PACKAGE_TRUCK -> GhEngine.profilesOf(true).all { GhEngine.hasLandmarks(dir, it) }
        PACKAGE_CAR -> GhEngine.profilesOf(false).all { GhEngine.hasLandmarks(dir, it) }
        else -> File(dir, "properties").exists()
      }
    }

    /**
     * The state of the graph in [regionDir] for the landmark packages [wanted]: the graph made
     * with openrouteservice's rules (graphs from 10/2026, tools/gh/NmImport.java) and the
     * landmarks of those vehicles.
     */
    fun state(regionDir: File, wanted: List<String> = wantedNow()): GhState {
      val dir = File(regionDir, "gh")
      if (!File(dir, "properties").exists()) return GhState.MISSING
      if (!orsValues(File(dir, "properties"))) return GhState.OLD
      // made before the guidance came from GraphHopper itself: it needs Valhalla, not kept any more
      if (!guides(regionDir)) return GhState.OLD
      return if (wanted.all { hasPackage(regionDir, it) }) GhState.READY else GhState.PARTIAL
    }

    /** The country's graph guides the routes by itself (made with the guidance data, 10/2026). */
    fun guides(regionDir: File): Boolean {
      val p = File(File(regionDir, "gh"), "properties")
      return try {
        p.exists() && p.length() < 4_000_000 && p.readBytes().toString(Charsets.ISO_8859_1).contains("osm_way_id")
      } catch (e: Exception) {
        false
      }
    }

    /** The graph's description lists openrouteservice's values (ors_hgv_speed…). */
    private fun orsValues(properties: File): Boolean = try {
      properties.length() < 4_000_000 && properties.readBytes().toString(Charsets.ISO_8859_1).contains("ors_hgv_speed")
    } catch (e: Exception) {
      false
    }

    /** The package the graph (or its landmarks [pkg]) came from (see SOURCE_FILE), or null. */
    fun source(regionDir: File, pkg: String = PACKAGE): String? =
        File(File(regionDir, "gh"), sourceFile(pkg)).takeIf { it.exists() }?.readText()?.trim()

    /**
     * The "gh" folder of a country, unpacked from [PACKAGE] when a new one arrived, then the
     * landmark packages that arrived unpacked into it (the packages are then removed, they are not
     * needed any more). Null when the country has no graph.
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
      if (File(dir, "properties").exists()) {
        for (pkg in listOf(PACKAGE_TRUCK, PACKAGE_CAR)) {
          val lm = File(regionDir, pkg)
          if (!lm.exists() || lm.length() == 0L) continue
          try {
            GZIPInputStream(lm.inputStream().buffered(1 shl 20), 1 shl 16).use { Tar.extract(it, dir) }
            lm.delete()
            Log.i(TAG, "GraphHopper landmarks $pkg unpacked in ${regionDir.name}")
          } catch (e: Exception) {
            Log.e(TAG, "GraphHopper landmarks $pkg of ${regionDir.name} not unpacked", e)
          }
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
    route = trip.route.gh
    // the zones are rings of [lon, lat] for Valhalla, [lat, lon] here
    for (ring in trip.excludePolygons) {
      avoidZones.add(ring.map { p -> doubleArrayOf(p[1], p[0]) }.toTypedArray())
    }
  }
}
