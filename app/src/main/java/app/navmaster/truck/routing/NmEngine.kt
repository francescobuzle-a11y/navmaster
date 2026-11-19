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
import it.navmaster.engine.api.RequestContext
import it.navmaster.engine.api.ResponseFormat
import it.navmaster.engine.api.RoadsService
import it.navmaster.engine.api.SearchSlots
import it.navmaster.engine.api.Transfer
import it.navmaster.engine.storage.BBox
import it.navmaster.engine.api.Transfers
import it.navmaster.engine.routing.CancelToken
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.GZIPInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * How a computation is asked of the engine: [priority] MAIN for the route on screen (or guided),
 * BACKGROUND for what is computed next to it (the variants, the other kinds, the toll-free one),
 * which gives way to a MAIN one; [deadlineMs] the time it may take (null: no limit).
 */
class EngineCall(val priority: Int = RequestContext.MAIN, val deadlineMs: Long? = null)

/**
 * The engine's answer to one request: [text] (GeoJSON or openrouteservice's error object), or null
 * when the trip cannot be computed here, then [problem] says why. Returned with the answer, not kept
 * in the engine: several requests run at the same time and one cleared or overwrote another's reason.
 */
class EngineReply(val text: String?, val problem: String? = null)

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
  init { RouteProgress.init(context) }
  private class Open(val region: InstalledRegion, val key: String, val graph: RoutingGraph, val service: DirectionsService) {
    val country = CountryGraph(region.id, service)
    @Volatile var used = SystemClock.elapsedRealtime()
    // the last route computed on it (0: none): the warm-up and the crossings open every country in
    // turn, and closing the graph of the trip on screen would lose its engine caches (the legs a
    // pass-through point reuses, the best route the variants start from)
    @Volatile var routed = 0L
  }

  private val lock = ReentrantReadWriteLock()
  private val open = LinkedHashMap<String, Open>()
  // at most 2 core searches at a time in the whole app (the tablet's memory and cores), one of them
  // at most for the work in the background: a route on screen always finds one free
  private val slots = SearchSlots(2)
  // the engine's helper thread (legs, candidates of the variants): used only inside lock.read, so a
  // graph is never closed under it
  private val pool = Executors.newFixedThreadPool(1) { r -> Thread(r, "nm-leg").apply { isDaemon = true } }
  private val transfers = HashMap<String, List<Transfer>>()

  /**
   * The graph of [region], opened if needed (null when it has none or it does not open; [why] gets the reason).
   * [keep]: the graphs the same request needs (its plan): never closed to make room for this one.
   */
  private fun graphOf(region: InstalledRegion, why: (String) -> Unit = {}, keep: Set<String> = emptySet(), background: Boolean = false): Open? {
    val dir = ready(region.dir)
    if (dir == null) {
      why("${region.label}: manca il grafo del motore NavMaster. In «Mappe d'Europa» tocca «Aggiorna»")
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
        // first the graphs no route used (or used longest ago), then the least recently opened; never
        // one of the request's own plan (a transit graph has routed 0: closing it to open the next one
        // of the same plan went round three times and gave up)
        // the work in the background (the warm-up of the other countries) never closes a graph a route
        // used in the last minutes: it would lose that trip's engine caches (the legs, the crossings'
        // weights the variants start from); that country is not warmed instead
        val protect = if (background) SystemClock.elapsedRealtime() - ROUTED_KEEP_MS else null
        val id = toClose(open.values.map { Slot(it.region.id, it.routed, it.used) }, keep, protect)
        if (id == null) { if (background) return@write null else break }
        val oldest = open.remove(id) ?: break
        runCatching { oldest.graph.close() }
      }
      val started = SystemClock.elapsedRealtime()
      try {
        val g = RoutingGraph.load(dir, true)
        // alternatives: the plateau method up to 60 km in a straight line, the via points method beyond
        val service = DirectionsService(g, DirectionsLimits(50, 6_000_000.0, 6_000_000.0, 400.0, 60_000.0), ENGINE_VERSION)
        service.alternativesLog = { Log.i(TAG, it); RouteProgress.onEngineLog(it) }
        val o = Open(region, key, g, service)
        open[region.id] = o
        Log.i(TAG, "engine graph of ${region.id} opened in ${SystemClock.elapsedRealtime() - started} ms: ${g.profileNames} ch=${g.chStorages.keys}")
        o
      } catch (e: Throwable) {
        why("${region.label}: il grafo del motore non si apre (${e.javaClass.simpleName}: ${e.message?.take(80)}). In «Mappe d'Europa» tocca «Aggiorna»")
        Log.e(TAG, "engine graph of ${region.id} not opened", e)
        null
      }
    }
  }

  /**
   * openrouteservice's directions, computed here: [body] is the JSON openrouteservice would receive
   * (points [lat, lon] in [points]), the answer its GeoJSON (or its error object,
   * `{"error":{"code":2009,...}}`). No text when a country of the trip has no graph on the tablet
   * (the reply's problem says which). [cancel] stops the search (it then throws the engine's
   * SearchCancelledException); [call] says its priority and time limit.
   */
  fun directions(points: List<DoubleArray>, profile: String, body: String, call: EngineCall = EngineCall(), cancel: CancelToken = CancelToken.NEVER): EngineReply {
    var problem: String? = null
    val why = { w: String -> problem = w }
    val text = directionsText(points, profile, body, call, cancel, why)
    return EngineReply(text, if (text == null) problem else null)
  }

  private fun directionsText(points: List<DoubleArray>, profile: String, body: String, call: EngineCall, cancel: CancelToken, why: (String) -> Unit): String? {
    val t0 = SystemClock.elapsedRealtime()
    @Suppress("UNCHECKED_CAST")
    val parsed = Json.parse(body) as Map<String, Any?>
    val parseMs = SystemClock.elapsedRealtime() - t0
    val countries = regions.installed.value.filter { present(it.dir) }
    if (countries.isEmpty()) { why("nessuna mappa scaricata in questa zona"); return null }
    val at = points.map { regions.regionAt(it[0], it[1]) }
    // a point in a country not on the tablet: the trip cannot be computed here
    val missing = points.indices.firstOrNull { i -> at[i] == null && catalog?.countryAt(points[i][0], points[i][1]) != null }
    if (missing != null) {
      why("${catalog?.countryAt(points[missing][0], points[missing][1])?.name ?: "Un Paese del viaggio"}: mappa non scaricata. In «Mappe d'Europa» scaricala")
      return null
    }
    val known = at.filterNotNull().distinctBy { it.id }
    // the engine's lines of this request (variants, measures) go to the log only, never to the bar
    // of the search (RouteProgress reads the lines of the main route)
    val rc = RequestContext(cancel = cancel, priority = call.priority, executor = pool, slots = slots, log = { Log.i(TAG, it) })
    // the request of the variants (navmaster.alternatives): it may look at more countries than the route on screen
    val variants = (parsed["navmaster"] as? Map<*, *>)?.get("alternatives") != null
    // a trip in one country, its variants: also a way through a neighbouring country (A-B-A: Innsbruck →
    // Salzburg by the German A93/A8), when two of its crossings lie near the way (geometry only, no graph opened)
    val around = if (variants && known.size == 1 && points.size == 2) aroundCountries(known[0], countries, points) else emptyList()
    if (around.isNotEmpty()) Log.i(TAG, "varianti: giri per i Paesi vicini: ${around.joinToString()}")
    // one country: its graph (a point just outside every outline goes with the others)
    if (known.size <= 1 && around.isEmpty()) {
      val region = known.firstOrNull() ?: countries.first()
      // the graph may be closed by another thread between opening and reading it: then opened again
      repeat(3) {
        val o = graphOf(region, why) ?: return null
        lock.read {
          if (open[region.id] === o) {
            o.used = SystemClock.elapsedRealtime()
            o.routed = o.used
            return measured(rc, parseMs) {
              try { o.service.handle(DirectionsRequest.fromJson(profile, parsed), ResponseFormat.GEOJSON, rc) }
              catch (e: ApiException) { o.service.errorBody(e) }
            }
          }
        }
      }
      return null
    }
    // across borders: the countries of the points, then those on the sequences of countries the
    // engine will weigh (the same rule: CrossBorderDirections.countrySequences), then those near the way
    val knownIds = known.map { it.id }.toSet()
    val near = countries.filter { r -> r.id in knownIds || nearTrip(r, points) }.sortedBy { r -> if (r.id in knownIds) 0 else 1 }.map { it.id }
    val installed = countries.filter { installed(it.dir) }
    val plan = if (around.isNotEmpty()) OpenPlan(listOf(known[0].id) + around, emptyList(), emptyList())
      else openPlan(installed.map { it.id }, at.map { it?.id }, points, near, { id -> bboxOf(id) }, { a, b -> linkedByCrossings(installed, a, b) }, variants)
    Log.i(TAG, "grafi aperti: ${plan.open.joinToString()}" + (if (plan.extra.isEmpty()) "" else "; per le sole varianti: ${plan.extra.joinToString()}") +
        (if (plan.excluded.isEmpty()) "" else "; esclusi: ${plan.excluded.joinToString()} (oltre $MAX_OPEN)") +
        "; sequenze candidate: ${plan.sequences.joinToString(" | ") { it.joinToString("-") }.ifEmpty { "nessuna" }}")
    repeat(3) {
      val keep = (plan.open + plan.extra).toSet()
      // the graphs for the variants only: opened if they can be, never an error of the request
      val graphs = (plan.open + plan.extra).mapNotNull { id -> countries.firstOrNull { it.id == id } }
          .mapNotNull { r -> graphOf(r, if (r.id in knownIds) why else { _ -> }, keep) }
      if (knownIds.any { k -> graphs.none { it.region.id == k } }) return null
      lock.read {
        if (graphs.all { open[it.region.id] === it }) {
          val now = SystemClock.elapsedRealtime()
          // every graph of the trip, the transit ones too: the warm-up must not close them (their caches)
          for (g in graphs) { g.used = now; g.routed = now }
          // the engine's messages also move the bar of the search (RouteProgress), for the route on screen only
          val main = call.priority == RequestContext.MAIN
          if (main) RouteProgress.names = countries.associate { it.id to it.label }
          // a border with a graph for the variants only: the crossings already written (prepareBorders), never found
          // now inside the request (seconds on the tablet taken from the variants' time); none until the file is there
          val extraIds = plan.extra.toSet()
          val cb = CrossBorderDirections(graphs.map { it.country },
              { a, b -> if (a.id in extraIds || b.id in extraIds) transfersWritten(a, b) else transfersBetween(a, b) },
              { Log.i(TAG, it); if (main) RouteProgress.onEngineLog(it) })
          // the best is weighed only on the graphs the route on screen opens: the ones for the variants only
          // add sequences to their step 2 (the best on screen does not change)
          if (plan.extra.isNotEmpty()) cb.bestGraphs = plan.open.toSet()
          return measured(rc, parseMs) {
            try { cb.handle(DirectionsRequest.fromJson(profile, parsed), at.map { it?.id }, rc) }
            catch (e: ApiException) { graphs.first().service.errorBody(e) }
          }
        }
      }
    }
    return null
  }

  /**
   * The answer of [handle] as text, with the measures of the request in the log ("stats …": the
   * engine's, plus reading the request and writing the answer here), also when it was cancelled.
   */
  private inline fun measured(rc: RequestContext, parseMs: Long, handle: () -> Map<String, Any?>): String {
    val t0 = SystemClock.elapsedRealtime()
    var handleMs = -1L
    var stringifyMs = -1L
    try {
      val doc = handle()
      handleMs = SystemClock.elapsedRealtime() - t0
      val t1 = SystemClock.elapsedRealtime()
      return Json.stringify(doc).also { stringifyMs = SystemClock.elapsedRealtime() - t1 }
    } finally {
      if (handleMs < 0) handleMs = SystemClock.elapsedRealtime() - t0
      Log.i(TAG, runCatching { rc.stats.line() }.getOrDefault("stats") +
          " | app: json $parseMs ms, motore $handleMs ms, testo ${if (stringifyMs < 0) "-" else "$stringifyMs ms"}")
    }
  }

  /**
   * The system is short of memory: the engine's caches of legs and snaps go. Called on the main
   * thread: it never waits for the lock (a search holding it and a graph waiting to open behind it
   * froze the screen for seconds, "NavMaster non risponde"). The open services are read with
   * tryLock; when a graph is being opened or closed, on a thread of its own. The caches themselves
   * are synchronized: no engine lock is needed to clear them.
   */
  fun trimMemory(level: Int) {
    val now = lock.readLock()
    if (now.tryLock()) {
      val services = try { open.values.map { it.service } } finally { now.unlock() }
      clear(services, level)
    } else {
      Thread({ clear(lock.read { open.values.map { it.service } }, level) }, "nm-trim").apply { isDaemon = true }.start()
    }
  }

  private fun clear(services: List<DirectionsService>, level: Int) {
    for (s in services) runCatching { s.clearCaches() }
    Log.i(TAG, "engine caches cleared (trim memory $level)")
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

  /** The catalog's outline of a country as the engine's box (null without one). */
  private fun bboxOf(id: String): BBox? = catalog?.country(id)?.bbox?.takeIf { it.size == 4 }?.let { b -> BBox(b[0], b[2], b[1], b[3]) }

  /**
   * Two installed countries are linked when their outlines touch and the file of their border
   * crossings (transfersBetween, written after the start) is not empty; a file not written yet
   * counts as linked (the graphs are not opened here to find out: the engine decides then).
   */
  private fun linkedByCrossings(installed: List<InstalledRegion>, a: String, b: String): Boolean {
    val ba = bboxOf(a) ?: return false
    val bb = bboxOf(b) ?: return false
    if (!ba.intersects(bb)) return false
    val ra = installed.firstOrNull { it.id == a } ?: return false
    val rb = installed.firstOrNull { it.id == b } ?: return false
    val da = graphDir(ra.dir) ?: return false
    val db = graphDir(rb.dir) ?: return false
    val name = transfersName(a, da, b, db)
    synchronized(transfers) { transfers[name]?.let { return it.isNotEmpty() } }
    val file = File(File(context.filesDir, "valichi"), name)
    val list = runCatching { Transfers.read(file) }.getOrNull() ?: return true
    synchronized(transfers) { transfers[name] = list }
    return list.isNotEmpty()
  }

  /**
   * The neighbours of [home] worth a way round for a trip in [home] alone (A-B-A, the variants only):
   * installed countries whose outline touches [home]'s and whose crossings file has two crossings in
   * different places near the way (Transfers.aroundOk: p → t1 → t2 → q within 1.2 times the line),
   * at most CrossBorderDirections.AROUND_MAX, nearest the line first. Only the files of the crossings
   * already written are read (no graph opened): a border never prepared is not looked at.
   */
  private fun aroundCountries(home: InstalledRegion, countries: List<InstalledRegion>, points: List<DoubleArray>): List<String> {
    val p = points.first(); val q = points.last()
    val hb = bboxOf(home.id) ?: return emptyList()
    val hd = graphDir(home.dir) ?: return emptyList()
    val out = ArrayList<Pair<String, Double>>()
    for (r in countries) {
      if (r.id == home.id || !installed(r.dir)) continue
      val rb = bboxOf(r.id) ?: continue
      if (!hb.intersects(rb)) continue
      val rd = graphDir(r.dir) ?: continue
      val name = transfersName(home.id, hd, r.id, rd)
      val list = synchronized(transfers) { transfers[name] } ?: runCatching { Transfers.read(File(File(context.filesDir, "valichi"), name)) }.getOrNull()
          ?.also { l -> synchronized(transfers) { transfers[name] = l } } ?: continue
      if (!Transfers.aroundOk(list, p[0], p[1], q[0], q[1])) continue
      val c = doubleArrayOf((rb.minLat + rb.maxLat) / 2, (rb.minLon + rb.maxLon) / 2)
      out += r.id to app.navmaster.truck.core.Geo.dist(p[0], p[1], c[0], c[1]) + app.navmaster.truck.core.Geo.dist(c[0], c[1], q[0], q[1])
    }
    return out.sortedBy { it.second }.take(CrossBorderDirections.AROUND_MAX).map { it.first }
  }

  /** The file of the crossings between two graphs: the ids and a stamp of each graph (a new graph, a new file). */
  private fun transfersName(a: String, aDir: File, b: String, bDir: File): String {
    val (x, xd, y, yd) = if (a < b) listOf(a, aDir, b, bDir) else listOf(b, bDir, a, aDir)
    fun stamp(dir: File) = (File(dir, "edges").length() xor File(dir, "profiles").lastModified()).toULong().toString(16)
    return "${x}_${stamp(xd as File)}-${y}_${stamp(yd as File)}.txt"
  }

  /** The border crossings between two graphs from the files only (none when not written yet: nothing found now). */
  private fun transfersWritten(a: CountryGraph, b: CountryGraph): List<Transfer> {
    val (x, y) = if (a.id < b.id) a to b else b to a
    val name = transfersName(x.id, x.graph.dir, y.id, y.graph.dir)
    synchronized(transfers) { transfers[name]?.let { return it } }
    val list = runCatching { Transfers.read(File(File(context.filesDir, "valichi"), name)) }.getOrNull() ?: return emptyList()
    synchronized(transfers) { transfers[name] = list }
    return list
  }

  /** The border crossings between two graphs: from the files, else found now and kept. */
  private fun transfersBetween(a: CountryGraph, b: CountryGraph): List<Transfer> {
    val (x, y) = if (a.id < b.id) a to b else b to a
    val name = transfersName(x.id, x.graph.dir, y.id, y.graph.dir)
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
      val o = graphOf(r, background = true) ?: continue
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
      val o = graphOf(r, background = true) ?: continue
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
      val o = graphOf(r, background = true) ?: return@let
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
        val list = transfersBetween(ca, cbg)
        // the crossings on the roads of both countries into the system's file cache (the location
        // index and the geometry around each one): the first trip across this border snaps all of
        // them on both sides, and after the start those pages came from the flash (tablet,
        // Canneto → Dolná Komjatice: ~1500 page reads, ~0.7 s of the first route)
        val started = SystemClock.elapsedRealtime()
        for (t in list) {
          runCatching { ga.index.findClosest(t.lat, t.lon) }
          runCatching { gb.index.findClosest(t.lat, t.lon) }
        }
        if (list.isNotEmpty()) Log.i(TAG, "valichi ${a.id}-${b.id}: ${list.size} passaggi agganciati in ${SystemClock.elapsedRealtime() - started} ms")
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
  }

  /** An open graph as [toClose] sees it: its country, the last route on it (0: none), its last use. */
  internal data class Slot(val id: String, val routed: Long, val used: Long)

  /**
   * The graphs a trip opens ([openPlan]): in order, those left out, the sequences of countries found;
   * [extra]: the graphs opened for the request of the variants only (their step 2), never for the best.
   */
  internal class OpenPlan(val open: List<String>, val excluded: List<String>, val sequences: List<List<String>>, val extra: List<String> = emptyList())

  companion object {
    private const val TAG = "NavMasterRoute"
    const val ENGINE_VERSION = "0.2.0"
    /** Graphs kept open at the same time (a trip through a few countries). */
    internal const val MAX_OPEN = 5
    /**
     * Graphs open at most when a pair of points has no whole sequence of countries within [MAX_OPEN]
     * (Italy → Sweden or Bulgaria: 5-6 countries in a row): the shortest one is added, the countries
     * only near the way go first. RAM per open graph: to be measured on the tablet ("stats", usedMb).
     */
    internal const val MAX_PLAN_OPEN = 7
    /** Graphs open at most for the request of the variants (the other sequences' countries, for their step 2). */
    internal const val MAX_VARIANTS_OPEN = 6

    /**
     * The open graph to close to make room for another: outside [keep] (the graphs of the same
     * request), the one no route used (or used longest ago), then the least recently used; null when
     * every open graph is in [keep].
     */
    internal fun toClose(open: List<Slot>, keep: Set<String>, protectAfter: Long? = null): String? =
      open.filter { it.id !in keep && !(protectAfter != null && it.routed > 0 && it.routed > protectAfter) }
          .minWithOrNull(compareBy<Slot>({ it.routed }, { it.used }))?.id

    /** How long a graph a route used stays out of reach of the warm-up (see [graphOf]). */
    internal const val ROUTED_KEEP_MS = 15 * 60_000L

    /**
     * Which graphs a trip opens, [MAX_OPEN] at most: the countries of the points ([pointCountries],
     * null for a point outside every country), then the countries on the sequences of countries
     * between consecutive points in different countries (CrossBorderDirections.countrySequences over
     * the [installed] countries, with their outlines [bounds] and [linked] by crossings: the same
     * rule the engine weighs them with, so that a sequence it could weigh is never left without its
     * graph), then the other countries [near] the way (as before, the countries of the points first).
     * Each pair's sequences come shortest first, then those that stray least from the line; across
     * the pairs (a stop in another country) first every pair's sequences as long as its shortest
     * (the other ways through, by rank), then those with a country more (the detours): the detours
     * of the first leg never take the places of the only sequences of the next leg. On the real trip
     * Canneto → Bielsko-Biała they bring Austria, the Czech Republic, Slovakia and Poland in, and
     * leave Germany out (said in the log).
     */
    internal fun openPlan(installed: List<String>, pointCountries: List<String?>, points: List<DoubleArray>, near: List<String>,
                          bounds: (String) -> BBox?, linked: (String, String) -> Boolean, variants: Boolean = false): OpenPlan {
      // the sequences of every pair of consecutive points in different countries, in the engine's order
      val perPair = ArrayList<List<List<String>>>()
      var prev = -1
      for (i in points.indices) {
        val c = pointCountries[i] ?: continue
        if (prev >= 0 && pointCountries[prev] != c) {
          perPair += CrossBorderDirections.countrySequences(installed, bounds, linked, points[prev][0], points[prev][1], points[i][0], points[i][1], pointCountries[prev]!!, c)
        }
        prev = i
      }
      // across the pairs: by countries more than the pair's shortest, then by rank in the pair, then by pair
      val ranked = perPair.flatMapIndexed { p, seqs ->
        val shortest = seqs.minOfOrNull { it.size } ?: 0
        seqs.mapIndexed { rank, seq -> Triple(seq.size - shortest, rank, p) to seq }
      }.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third })).map { it.second }
      val sequences = ArrayList<List<String>>()
      for (seq in ranked) if (seq !in sequences) sequences += seq
      val ordered = LinkedHashSet<String>()
      pointCountries.filterNotNull().forEach { ordered += it }
      for (seq in sequences) ordered += seq
      ordered += near
      val open = LinkedHashSet(ordered.take(MAX_OPEN))
      // every pair keeps one whole sequence (its shortest): without it the engine has no way at all, the
      // best route included (Italy → Sweden: italy-austria-germany-denmark-sweden). Up to MAX_PLAN_OPEN,
      // the countries only near the way (in no sequence, no point) leave first. Where a whole sequence was
      // within MAX_OPEN nothing changes
      val pointSet = pointCountries.filterNotNull().toSet()
      val inSequences = sequences.flatten().toSet()
      for (seqs in perPair) {
        if (seqs.isEmpty() || seqs.any { s -> open.containsAll(s) }) continue
        for (id in seqs.first()) {
          if (id in open) continue
          if (open.size >= MAX_OPEN) open.lastOrNull { it !in pointSet && it !in inSequences }?.let { open.remove(it) }
          if (open.size >= MAX_PLAN_OPEN) break
          open += id
        }
      }
      // the variants only: the countries of the other sequences (also those beyond the first six of each
      // pair, CrossBorderDirections.EXTRA_SEQUENCES), up to MAX_VARIANTS_OPEN, for the engine's step 2
      val extra = LinkedHashSet<String>()
      if (variants) {
        var prevV = -1
        val more = ArrayList<List<String>>()
        for (i in points.indices) {
          val c = pointCountries[i] ?: continue
          if (prevV >= 0 && pointCountries[prevV] != c) more += CrossBorderDirections.countrySequences(installed, bounds, linked, points[prevV][0], points[prevV][1],
              points[i][0], points[i][1], pointCountries[prevV]!!, c, CrossBorderDirections.EXTRA_SEQUENCES)
          prevV = i
        }
        for (seq in (sequences + more).sortedBy { it.size }) for (id in seq) {
          if (id in open || id in extra) continue
          if (open.size + extra.size >= MAX_VARIANTS_OPEN) break
          extra += id
        }
      }
      return OpenPlan(open.toList(), ordered.filter { it !in open && it !in extra }, sequences, extra.toList())
    }
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

    /**
     * Throws if the graph in [d] has a format this engine cannot read (`graph.format_version` in its
     * `properties`, e.g. «5.navmaster-transito»): the package stays, the working graph is kept.
     */
    private fun checkFormat(d: File) {
      val props = java.util.Properties()
      runCatching { File(d, "properties").inputStream().use { props.load(it) } }
      val format = props.getProperty("graph.format_version").orEmpty()
      val major = format.substringBefore('.').toIntOrNull()
      // the engine's top-level isReadableGraphFormat (the Android jar has no Kotlin module metadata: by reflection)
      val readable = major != null && runCatching {
        Class.forName("it.navmaster.engine.EngineKt").getMethod("isReadableGraphFormat", Int::class.javaPrimitiveType).invoke(null, major) as Boolean
      }.getOrElse { major in 4..5 }
      if (!readable)
        throw IllegalStateException("formato del grafo «$format» non leggibile da questo motore: aggiorna l'app")
    }

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

    /** Whether [e] (or a cause of it) says the storage is full. */
    internal fun outOfSpace(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { "ENOSPC" in (it.message ?: "") || "No space left" in (it.message ?: "") }

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
          try {
            GZIPInputStream(gz.inputStream().buffered(1 shl 20), 1 shl 16).use { Tar.extract(it, tmp) }
          } catch (e: java.io.IOException) {
            // no room for the new graph next to the old one (Italy: 2.3 GB package plus the graph
            // unpacked, with the old graph still there): the old one goes first, then the new one is
            // unpacked again; the package stays until it is in place, so a failure is retried at the
            // next start (meanwhile the country has no graph)
            if (!outOfSpace(e) || !dir.exists()) throw e
            // a format this engine cannot read must not cost the working graph (checked again below)
            if (File(tmp, "properties").exists()) checkFormat(tmp)
            Log.w(TAG, "graph of ${regionDir.name}: no room next to the old one, the old one goes first")
            tmp.deleteRecursively()
            dir.deleteRecursively()
            tmp.mkdirs()
            GZIPInputStream(gz.inputStream().buffered(1 shl 20), 1 shl 16).use { Tar.extract(it, tmp) }
          }
          if (!complete(tmp)) throw IllegalStateException("grafo incompleto")
          // a graph of a newer format (an older APK than the graphs) is refused before the working one goes
          checkFormat(tmp)
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
