package app.navmaster.truck.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.navmaster.truck.data.CatalogStore
import app.navmaster.truck.data.InstalledRegion
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.Tar
import it.navmaster.engine.RoutingGraph
import it.navmaster.engine.api.ApiException
import it.navmaster.engine.api.CountryGraph
import it.navmaster.engine.api.CrossBorderDirections
import it.navmaster.engine.api.DirectionsLimits
import it.navmaster.engine.api.DirectionsRequest
import it.navmaster.engine.api.DirectionsService
import it.navmaster.engine.api.Json
import it.navmaster.engine.api.ResponseFormat
import it.navmaster.engine.api.RoadsService
import it.navmaster.engine.api.Transfer
import it.navmaster.engine.api.Transfers
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * navmaster-engine on the tablet: the graphs of the downloaded countries (nm.tar.gz in each
 * country's package, unpacked once into its "nm" folder), opened memory-mapped and kept open (a
 * few at a time). The engine computes routes with openrouteservice's own logic and answers with
 * openrouteservice's GeoJSON, plus the facts about the roads of the route (`navmaster.roads`).
 *
 * A trip across borders is computed in the graphs of the countries it goes through and joined at
 * the real border crossings ([CrossBorderDirections]); the crossings between two graphs are found
 * once and kept in the app's files ("valichi").
 *
 * Routes and road queries read the graphs at the same time (a read lock); only opening and
 * closing a graph waits for them (the write lock).
 */
class NmEngine(private val context: Context, private val regions: RegionManager, private val catalog: CatalogStore? = null) {
  private class Open(val region: InstalledRegion, val key: String, val graph: RoutingGraph, val service: DirectionsService) {
    val country = CountryGraph(region.id, service)
    @Volatile var used = SystemClock.elapsedRealtime()
  }

  private val lock = ReentrantReadWriteLock()
  private val open = LinkedHashMap<String, Open>()
  private val transfers = HashMap<String, List<Transfer>>()

  /** Why the last route could not use the engine (shown under the routes), null when it could. */
  @Volatile var problem: String? = null
    private set

  /** The graph of [region], opened if needed (null when it has none or it does not open; [problem] says why). */
  private fun graphOf(region: InstalledRegion): Open? {
    val dir = ready(region.dir)
    if (dir == null) {
      problem = "${region.label}: manca il grafo del motore NavMaster. In «Mappe d'Europa» tocca «Aggiorna»"
      Log.w(TAG, "engine: no graph in ${region.id}")
      return null
    }
    // the same graph stays open across refreshes of the regions: only a new graph file reopens it
    val key = dir.absolutePath + ":" + File(dir, "profiles").lastModified() + ":" + File(dir, "edges").length()
    lock.read { open[region.id]?.takeIf { it.key == key }?.let { it.used = SystemClock.elapsedRealtime(); return it } }
    return lock.write {
      open[region.id]?.takeIf { it.key == key }?.let { return@write it }
      open.remove(region.id)?.let { runCatching { it.graph.close() } }
      // a few graphs open at a time (each is mapped from its files: little memory of its own)
      while (open.size >= MAX_OPEN) {
        val oldest = open.values.minByOrNull { it.used } ?: break
        open.remove(oldest.region.id)
        runCatching { oldest.graph.close() }
      }
      val started = SystemClock.elapsedRealtime()
      try {
        val g = RoutingGraph.load(dir, true)
        val o = Open(region, key, g, DirectionsService(g, DirectionsLimits(50, 6_000_000.0, 6_000_000.0, 400.0), ENGINE_VERSION))
        open[region.id] = o
        Log.i(TAG, "engine graph of ${region.id} opened in ${SystemClock.elapsedRealtime() - started} ms: ${g.profileNames} ch=${g.chStorages.keys}")
        o
      } catch (e: Throwable) {
        problem = "${region.label}: il grafo del motore non si apre (${e.javaClass.simpleName}: ${e.message?.take(80)}). In «Mappe d'Europa» tocca «Aggiorna»"
        Log.e(TAG, "engine graph of ${region.id} not opened", e)
        null
      }
    }
  }

  /**
   * openrouteservice's directions, computed here: [body] is the JSON openrouteservice would receive
   * (points [lat, lon] in [points]), the answer its GeoJSON (or its error object,
   * `{"error":{"code":2009,...}}`). Null when a country of the trip has no graph on the tablet
   * ([problem] says which).
   */
  fun directions(points: List<DoubleArray>, profile: String, body: String): String? {
    @Suppress("UNCHECKED_CAST")
    val parsed = Json.parse(body) as Map<String, Any?>
    val countries = regions.installed.value.filter { present(it.dir) }
    if (countries.isEmpty()) { problem = "nessuna mappa scaricata in questa zona"; return null }
    val at = points.map { regions.regionAt(it[0], it[1]) }
    // a point in a country not on the tablet: the trip cannot be computed here
    val missing = points.indices.firstOrNull { i -> at[i] == null && catalog?.countryAt(points[i][0], points[i][1]) != null }
    if (missing != null) {
      problem = "${catalog?.countryAt(points[missing][0], points[missing][1])?.name ?: "Un Paese del viaggio"}: mappa non scaricata. In «Mappe d'Europa» scaricala"
      return null
    }
    val known = at.filterNotNull().distinctBy { it.id }
    // one country: its graph (a point just outside every outline goes with the others)
    if (known.size <= 1) {
      val region = known.firstOrNull() ?: countries.first()
      // the graph may be closed by another thread between opening and reading it: then opened again
      repeat(3) {
        val o = graphOf(region) ?: return null
        problem = null
        lock.read {
          if (open[region.id] === o) {
            o.used = SystemClock.elapsedRealtime()
            return try { Json.stringify(o.service.handle(DirectionsRequest.fromJson(profile, parsed), ResponseFormat.GEOJSON)) }
            catch (e: ApiException) { Json.stringify(o.service.errorBody(e)) }
          }
        }
      }
      return null
    }
    // across borders: the countries of the points and those near the way between them
    val knownIds = known.map { it.id }.toSet()
    val near = countries.filter { r -> r.id in knownIds || nearTrip(r, points) }
    repeat(3) {
      val graphs = near.sortedBy { r -> if (r.id in knownIds) 0 else 1 }.take(MAX_OPEN).mapNotNull { graphOf(it) }
      if (knownIds.any { k -> graphs.none { it.region.id == k } }) return null
      problem = null
      lock.read {
        if (graphs.all { open[it.region.id] === it }) {
          val cb = CrossBorderDirections(graphs.map { it.country }, { a, b -> transfersBetween(a, b) }, { Log.i(TAG, it) })
          return try {
            Json.stringify(cb.handle(DirectionsRequest.fromJson(profile, parsed), at.map { it?.id }))
          } catch (e: ApiException) { Json.stringify(graphs.first().service.errorBody(e)) }
        }
      }
    }
    return null
  }

  /** The country's outline (catalog bbox) is within reach of the way between the points. */
  private fun nearTrip(r: InstalledRegion, points: List<DoubleArray>): Boolean {
    val b = catalog?.country(r.id)?.bbox ?: return false
    var length = 0.0
    for (i in 1 until points.size) length += app.navmaster.truck.core.Geo.dist(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
    val reach = length * 0.35 + 60_000.0
    for (i in 1 until points.size) for (s in 0..40) {
      val t = s / 40.0
      val la = points[i - 1][0] + (points[i][0] - points[i - 1][0]) * t
      val lo = points[i - 1][1] + (points[i][1] - points[i - 1][1]) * t
      val d = app.navmaster.truck.core.Geo.dist(la, lo, la.coerceIn(b[1], b[3]), lo.coerceIn(b[0], b[2]))
      if (d <= reach) return true
    }
    return false
  }

  /** The border crossings between two graphs: from the files, else found now and kept. */
  private fun transfersBetween(a: CountryGraph, b: CountryGraph): List<Transfer> {
    val (x, y) = if (a.id < b.id) a to b else b to a
    fun stamp(g: CountryGraph) = (File(g.graph.dir, "edges").length() xor File(g.graph.dir, "profiles").lastModified()).toULong().toString(16)
    val name = "${x.id}_${stamp(x)}-${y.id}_${stamp(y)}.txt"
    synchronized(transfers) { transfers[name]?.let { return it } }
    val dir = File(context.filesDir, "valichi")
    val file = File(dir, name)
    val list = runCatching { Transfers.read(file) }.getOrNull() ?: run {
      val started = SystemClock.elapsedRealtime()
      val found = Transfers.find(x.graph, y.graph)
      Log.i(TAG, "valichi ${x.id}-${y.id}: ${found.size} in ${SystemClock.elapsedRealtime() - started} ms (${Transfers.timing})")
      // the files of the same two countries made from older graphs go
      dir.listFiles { f -> f.name.startsWith("${x.id}_") && f.name.contains("-${y.id}_") }?.forEach { it.delete() }
      runCatching { Transfers.write(file, found) }
      found
    }
    synchronized(transfers) { transfers[name] = list }
    return list
  }

  /**
   * The border crossings between the downloaded countries that touch, found now (in the
   * background, after the start and after a country is installed) so that the first trip across a
   * border does not wait for them. The graphs are opened here for the time it takes, apart from the
   * ones the routes use.
   */
  /**
   * Warms the graphs of the downloaded countries for [profile] (`driving-hgv` or `driving-car`):
   * the files a core search reads at random go into the system's file cache, within one [budget]
   * for all the countries. First the core files of every country (levels, landmarks, shortcuts:
   * what every search reads), then the graph files (nodes, turn restrictions, edges) starting from
   * the country [first] (where the vehicle is): cold, the edges of Italy alone cost 5 s on the
   * first route to Komjatice (14.6 s against 6.6 s warm). [progress] gets the country and the
   * fraction of the whole warm-up. In the background; the graphs stay open (see [graphOf]).
   */
  fun warmUp(profile: String, budget: Long, first: String? = null, progress: ((region: InstalledRegion, fraction: Float) -> Unit)? = null) {
    // first the graphs still on the shared storage into the internal one (see internalRoot)
    for (r in regions.installed.value) migrate(r.dir) { reset() }
    val list = regions.installed.value.filter { installed(it.dir) }.sortedBy { if (it.id == first) 0 else 1 }
    if (list.isEmpty()) return
    // the plan, by use: the core files of every country, then the graph files from the country [first]
    class Item(val region: InstalledRegion, val part: Int, val bytes: Long)
    val plan = ArrayList<Item>()
    var left = budget
    for (part in listOf(RoutingGraph.WARM_CORE, RoutingGraph.WARM_GRAPH)) for (r in list) {
      if (left <= 0) break
      val o = graphOf(r) ?: continue
      val b = runCatching { lock.read { if (open[r.id] === o) o.graph.warmBytes(profile, budget = left, parts = part) else 0L } }.getOrDefault(0L)
      if (b > 0) { plan += Item(r, part, b); left -= b }
    }
    val total = plan.sumOf { it.bytes }.coerceAtLeast(1)
    // read the other way round: the most useful last, the freshest in the cache. Read first, the
    // levels of Italy were out of the cache again at the first route (11.6 s instead of 7.4)
    var done = 0L
    for (item in plan.asReversed()) {
      val r = item.region
      progress?.invoke(r, done.toFloat() / total)
      val o = graphOf(r) ?: continue
      try {
        val base = done
        val line = lock.read {
          if (open[r.id] === o) o.graph.warm(profile, budget = item.bytes, parts = item.part, reverse = true) { d, _ ->
            progress?.invoke(r, (base + d).toFloat() / total)
          } else null
        } ?: continue
        o.used = SystemClock.elapsedRealtime()
        Log.i(TAG, "${r.id}: $line")
      } catch (e: Throwable) {
        Log.w(TAG, "${r.id}: riscaldamento fallito: $e")
      }
      done += item.bytes
    }
    // then the code: a few sample searches in the country [first], so the hot parts of the engine are
    // compiled before the first real route (just after a start or an update the code runs interpreted:
    // the first route to Komjatice cost 13 s, 5.9 s with the app compiled)
    list.firstOrNull()?.let { r ->
      progress?.invoke(r, 0.97f)
      val o = graphOf(r) ?: return@let
      runCatching { lock.read { if (open[r.id] === o) o.graph.warmCode(profile) else null } }
          .onSuccess { line -> line?.let { Log.i(TAG, "${r.id}: $it") } }
          .onFailure { Log.w(TAG, "${r.id}: riscaldamento del codice fallito: $it") }
      progress?.invoke(r, 1f)
    }
  }

  fun prepareBorders() {
    // first the graphs still on the shared storage into the internal one (see internalRoot)
    for (r in regions.installed.value) migrate(r.dir) { reset() }
    val list = regions.installed.value.filter { installed(it.dir) }
    fun box(id: String) = catalog?.country(id)?.bbox
    for (i in list.indices) for (j in i + 1 until list.size) {
      val a = list[i]; val b = list[j]
      val ba = box(a.id) ?: continue
      val bb = box(b.id) ?: continue
      if (ba[0] > bb[2] || bb[0] > ba[2] || ba[1] > bb[3] || bb[1] > ba[3]) continue
      var ga: RoutingGraph? = null
      var gb: RoutingGraph? = null
      try {
        ga = RoutingGraph.load(graphDir(a.dir) ?: continue, true)
        gb = RoutingGraph.load(graphDir(b.dir) ?: continue, true)
        val ca = CountryGraph(a.id, DirectionsService(ga, DirectionsLimits(), ENGINE_VERSION))
        val cbg = CountryGraph(b.id, DirectionsService(gb, DirectionsLimits(), ENGINE_VERSION))
        transfersBetween(ca, cbg)
      } catch (e: Throwable) {
        Log.w(TAG, "valichi ${a.id}-${b.id}: $e")
      } finally {
        runCatching { ga?.close() }
        runCatching { gb?.close() }
      }
    }
  }

  /** The roads within [radiusM] of a point, from the graph of its country (empty without one). */
  fun roadsAround(lat: Double, lon: Double, radiusM: Double): List<Map<String, Any?>> {
    val region = regions.regionAt(lat, lon) ?: return emptyList()
    repeat(2) {
      val o = graphOf(region) ?: return emptyList()
      lock.read {
        if (open[region.id] === o) {
          o.used = SystemClock.elapsedRealtime()
          @Suppress("UNCHECKED_CAST")
          return runCatching { RoadsService(o.graph).around("driving-car", lat, lon, radiusM)["roads"] as List<Map<String, Any?>> }
              .onFailure { Log.w(TAG, "roads around: $it") }.getOrDefault(emptyList())
        }
      }
    }
    return emptyList()
  }

  /** The graph of [lat], [lon] is there (a road query can answer). */
  fun hasGraphAt(lat: Double, lon: Double): Boolean = regions.regionAt(lat, lon)?.let { installed(it.dir) || present(it.dir) } == true

  /** The graphs in use are closed (a country updated or removed): opened again at the next route. */
  fun reset() {
    lock.write {
      for (o in open.values) runCatching { o.graph.close() }
      open.clear()
    }
    synchronized(transfers) { transfers.clear() }
    problem = null
  }

  companion object {
    private const val TAG = "NavMasterRoute"
    const val ENGINE_VERSION = "0.2.0"
    /** Graphs kept open at the same time (a trip through a few countries). */
    private const val MAX_OPEN = 5
    /** The graph of a country, packed (release dati-<country>). */
    const val PACKAGE = "nm.tar.gz"
    /** The folder it is unpacked into. */
    const val DIR = "nm"
    /** Which package the graph was unpacked from (the SHA-256 of its parts), to know when it changed. */
    const val SOURCE_FILE = ".package"

    /**
     * Where the graphs live: the app's internal storage. The country folders are on the shared
     * storage (/sdcard/Android/data), which Android serves through FUSE: every page of a graph
     * mapped in memory was read through it, and a search on the core took seconds instead of tens
     * of milliseconds. Same partition, same space, no FUSE. Null in the tests: next to the country.
     */
    @Volatile var internalRoot: File? = null

    private fun complete(d: File) = File(d, "profiles").exists() && File(d, "edges").exists()

    /** The internal folder of a country's graph (or its folder next to the country without one). */
    private fun internal(regionDir: File): File = internalRoot?.let { File(it, regionDir.name) } ?: File(regionDir, DIR)

    /** The old place of a graph, next to the country (packages installed before). */
    private fun legacy(regionDir: File): File = File(regionDir, DIR)

    /** The folder the graph of a country is read from: the internal one when complete, else the old one. */
    fun graphDir(regionDir: File): File? = internal(regionDir).takeIf { complete(it) } ?: legacy(regionDir).takeIf { complete(it) }

    /** The package the graph came from (see SOURCE_FILE), or null. */
    fun source(regionDir: File): String? = graphDir(regionDir)?.let { File(it, SOURCE_FILE) }?.takeIf { it.exists() }?.readText()?.trim()

    /** The graph of a country is there and complete. */
    fun installed(regionDir: File): Boolean = graphDir(regionDir) != null

    /** A graph (unpacked or still packed) is there. */
    fun present(regionDir: File): Boolean = installed(regionDir) || File(regionDir, PACKAGE).let { it.exists() && it.length() > 0 }

    /** The graph of a removed country goes too. */
    fun deleteGraph(regionDir: File) {
      runCatching { internal(regionDir).deleteRecursively() }
      runCatching { legacy(regionDir).deleteRecursively() }
    }

    /**
     * The graph folder of a country, unpacked from [PACKAGE] when a new one arrived (into the
     * internal storage; the package is then removed). Null when the country has no graph.
     */
    @Synchronized
    fun ready(regionDir: File): File? {
      val gz = File(regionDir, PACKAGE)
      if (gz.exists() && gz.length() > 0) {
        val dir = internal(regionDir)
        val started = SystemClock.elapsedRealtime()
        val tmp = File(dir.parentFile, dir.name + ".new")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
          GZIPInputStream(gz.inputStream().buffered(1 shl 20), 1 shl 16).use { Tar.extract(it, tmp) }
          if (!complete(tmp)) throw IllegalStateException("grafo incompleto")
          dir.deleteRecursively()
          if (!tmp.renameTo(dir)) throw IllegalStateException("impossibile installare il grafo")
          gz.delete()
          // the copy of before next to the country is not needed any more
          if (legacy(regionDir) != dir) legacy(regionDir).deleteRecursively()
          Log.i(TAG, "engine graph unpacked for ${regionDir.name} in ${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: Throwable) {
          Log.e(TAG, "engine graph of ${regionDir.name} not unpacked", e)
          tmp.deleteRecursively()
        }
      }
      return graphDir(regionDir)
    }

    /**
     * A graph still next to the country (installed before) copied into the internal storage, once,
     * in the background; then [onMoved] (the engine closes the old one) and the old copy goes.
     * Not done without twice its size free.
     */
    fun migrate(regionDir: File, onMoved: () -> Unit): Boolean {
      val old = legacy(regionDir)
      val dir = internal(regionDir)
      if (old == dir || !complete(old) || complete(dir)) return false
      val size = old.walkTopDown().filter { it.isFile }.sumOf { it.length() }
      val free = dir.parentFile?.let { it.mkdirs(); it.usableSpace } ?: 0L
      if (free < size + (512L shl 20)) {
        Log.w(TAG, "graph of ${regionDir.name} left on the shared storage: ${size shr 20} MB, free ${free shr 20} MB")
        return false
      }
      val started = SystemClock.elapsedRealtime()
      val tmp = File(dir.parentFile, dir.name + ".new")
      return try {
        tmp.deleteRecursively()
        old.copyRecursively(tmp, overwrite = true)
        if (!complete(tmp)) throw IllegalStateException("copia incompleta")
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IllegalStateException("impossibile spostare il grafo")
        onMoved()
        old.deleteRecursively()
        Log.i(TAG, "engine graph of ${regionDir.name} moved to the internal storage (${size shr 20} MB) in ${SystemClock.elapsedRealtime() - started} ms")
        true
      } catch (e: Throwable) {
        Log.e(TAG, "graph of ${regionDir.name} not moved", e)
        tmp.deleteRecursively()
        false
      }
    }
  }
}
