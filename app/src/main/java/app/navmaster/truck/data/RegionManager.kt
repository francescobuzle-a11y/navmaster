package app.navmaster.truck.data

import app.navmaster.truck.routing.GhRouting
import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable data class PackagePart(val name: String, val size: Long, val sha256: String)

@Serializable data class PackageFile(val file: String, val size: Long, val parts: List<PackagePart>)

@Serializable
data class Manifest(
    val region: String,
    val label: String,
    val built: String,
    val format: Int = 1,
    val files: List<PackageFile>,
)

/** A country (or the test area) whose data is on the tablet. */
data class InstalledRegion(val id: String, val manifest: Manifest, val dir: File) {
  val label: String
    get() = manifest.label

  val map: File
    get() = File(dir, "mappa.pmtiles")

  val limits: File
    get() = File(dir, "limiti.sqlite")

  val poi: File
    get() = File(dir, "poi.sqlite")

  val addresses: File
    get() = File(dir, "indirizzi.sqlite")

  val critical: File
    get() = File(dir, "criticita.sqlite")

  val sizeBytes: Long
    get() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

sealed class DownloadState {
  data object Queued : DownloadState()

  data class Running(val doneBytes: Long, val totalBytes: Long, val step: String) : DownloadState()

  data class Failed(val message: String) : DownloadState()

  data object Done : DownloadState()
}

private data class Job(val id: String, val label: String)

/**
 * Offline packages, one folder per country under the app's external files. They are published on
 * GitHub releases (dati-<country>) with a manifest; big files come in parts that are joined here.
 * Routes are computed and guided by GraphHopper (the country's graph, gh*.tar.gz; between
 * countries the graphs are joined at the border crossings, valichi.json). Downloads (FastDownloader) use several connections at once, on Wi-Fi or mobile
 * data, go on in the background (DownloadService) and resume where they were after a dropped
 * connection or the app closed.
 */
class RegionManager(private val context: Context, private val catalog: CatalogStore) {
  val root: File = File(context.getExternalFilesDir(null), "regions").apply { mkdirs() }
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
  val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()
  private val _installed = MutableStateFlow(scan())
  val installed: StateFlow<List<InstalledRegion>> = _installed.asStateFlow()
  private val queue = Channel<Job>(Channel.UNLIMITED)

  /** Bumped when routing data change, so the routing engine reloads. */
  private val _version = MutableStateFlow(0)
  val version: StateFlow<Int> = _version.asStateFlow()

  init {
    scope.launch {
      // downloads of the old versions (Android's download manager, often stuck waiting for Wi-Fi)
      runCatching {
        val dm = context.getSystemService(DownloadManager::class.java)
        val old = mutableListOf<Long>()
        dm.query(DownloadManager.Query()).use { c ->
          while (c.moveToNext()) {
            if (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
              old += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
            }
          }
        }
        if (old.isNotEmpty()) {
          dm.remove(*old.toLongArray())
          Log.i(TAG, "removed ${old.size} old system downloads")
        }
      }.onFailure { Log.w(TAG, "old downloads: $it") }
      for (job in queue) {
        try {
          doDownload(job)
          setState(job.id, DownloadState.Done)
        } catch (e: Exception) {
          Log.e(TAG, "download ${job.id} failed", e)
          setState(job.id, DownloadState.Failed(friendly(e)))
        }
      }
    }
  }

  fun refresh() {
    _installed.value = scan()
    _version.value++
  }

  private fun scan(): List<InstalledRegion> =
      (root.listFiles() ?: emptyArray())
          .filter { it.isDirectory && !it.name.startsWith(".") }
          .mapNotNull { dir ->
            val mf = File(dir, "manifest.json")
            if (!mf.exists()) return@mapNotNull null
            try {
              val m = json.decodeFromString(Manifest.serializer(), mf.readText())
              val region = InstalledRegion(dir.name, m, dir)
              // a country is ready with its map and its GraphHopper graph
              val gh = File(dir, "gh/properties").exists() || File(dir, GhRouting.PACKAGE).exists()
              if (region.map.exists() && gh) region else null
            } catch (e: Exception) {
              Log.w(TAG, "bad manifest in $dir: $e")
              null
            }
          }

  fun get(id: String): InstalledRegion? = _installed.value.firstOrNull { it.id == id }

  /** The installed region containing a point (by the country outline of the catalog). */
  fun regionAt(lat: Double, lon: Double): InstalledRegion? {
    val list = _installed.value
    val c = catalog.countryAt(lat, lon)
    return list.firstOrNull { it.id == c?.id } ?: list.firstOrNull { it.id == "test" && lat in 43.85..44.12 && lon in 12.30..12.72 }
  }

  private fun setState(id: String, s: DownloadState?) =
      _states.update { m -> if (s == null) m - id else m + (id to s) }

  fun download(id: String, label: String) {
    val s = _states.value[id]
    if (s is DownloadState.Running || s is DownloadState.Queued) return
    setState(id, DownloadState.Queued)
    queue.trySend(Job(id, label))
    DownloadService.start(context)
  }

  /** A download cut off (app closed, phone restarted) goes on from where it was, at the next start. */
  fun resumePending() {
    for (dir in root.listFiles() ?: emptyArray()) {
      val job = File(dir, ".parts/job.txt").takeIf { it.exists() }?.readLines() ?: continue
      if (_states.value[dir.name] == null && job.size >= 2) {
        Log.i(TAG, "resuming the download of ${dir.name}")
        download(dir.name, job[0])
      }
    }
  }

  /** What the driver reads when a download stops: no file paths or technical words. */
  private fun friendly(e: Exception): String {
    val m = e.message ?: ""
    return when {
      "ENOSPC" in m || "No space" in m -> "Spazio esaurito sul telefono: libera spazio e tocca Scarica, riprende da dove era arrivato"
      e is IllegalStateException && !m.startsWith("/") && m.isNotBlank() -> m
      e is java.io.IOException && !m.startsWith("/") && m.isNotBlank() && "danneggiato" in m -> m
      "Connessione" in m -> m
      else -> "Scaricamento interrotto: tocca Scarica, riprende da dove era arrivato"
    }
  }

  /** Why downloading has to wait now (null: it can go on). */
  private fun blocked(): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "In attesa della connessione a internet"
    if (app.navmaster.truck.AppGraph.settings.settings.value.downloadWifiOnly && cm.isActiveNetworkMetered) {
      return "In attesa del Wi-Fi: disattiva «Solo con Wi-Fi» per scaricare con i dati"
    }
    return null
  }

  /** Speed and time left, smoothed. */
  private class Speed {
    private var t0 = 0L
    private var b0 = 0L
    private var rate = 0.0

    fun text(done: Long, total: Long): String {
      val now = System.currentTimeMillis()
      if (t0 == 0L) {
        t0 = now
        b0 = done
      }
      val dt = (now - t0) / 1000.0
      if (dt >= 2.0) {
        val r = (done - b0) / dt
        rate = if (rate == 0.0) r else rate * 0.6 + r * 0.4
        t0 = now
        b0 = done
      }
      if (rate < 1000) return "Scaricamento"
      val left = ((total - done) / rate).toLong()
      val eta = when {
        left < 60 -> "meno di un minuto"
        left < 3600 -> "circa ${left / 60 + 1} min"
        else -> "circa ${left / 3600} h ${(left % 3600) / 60} min"
      }
      return String.format(Locale.ITALIAN, "Scaricamento · %.1f MB/s · %s", rate / 1e6, eta)
    }
  }

  fun freeBytes(): Long = StatFs(root.absolutePath).availableBytes

  private suspend fun doDownload(job: Job) {
    val id = job.id
    setState(id, DownloadState.Running(0, 0, "Lettura dell'elenco file"))
    val base = "$RELEASES/dati-$id"
    val manifestText =
        OkHttpClient().newCall(Request.Builder().url("$base/manifest.json").build()).execute().use {
          if (!it.isSuccessful) throw IllegalStateException("Pacchetto non ancora disponibile (${it.code})")
          it.body.string()
        }
    val manifest = json.decodeFromString(Manifest.serializer(), manifestText)
    val dir = File(root, id).apply { mkdirs() }
    // an update of a country already on the tablet: only the files that changed (same name, other
    // checksum) or are missing are brought down, e.g. only the GraphHopper graph
    val prev = File(dir, "manifest.json").takeIf { it.exists() }
        ?.let { runCatching { json.decodeFromString(Manifest.serializer(), it.readText()) }.getOrNull() }
    fun sums(f: PackageFile) = f.parts.joinToString(",") { it.sha256 }
    // the GraphHopper data: the graph and the landmarks of the vehicles chosen in the settings
    val ghWanted = GhRouting.wantedNow()
    fun unchanged(f: PackageFile): Boolean {
      if (f.file in GhRouting.PACKAGES) {
        // installed from this same package (the graph unpacked, the landmarks in it)
        if (GhRouting.source(dir, f.file) != sums(f) || !GhRouting.hasPackage(dir, f.file)) return false
        return f.file != GhRouting.PACKAGE || GhRouting.state(dir, emptyList()) == GhRouting.GhState.READY
      }
      val old = prev?.files?.firstOrNull { it.file == f.file } ?: return false
      if (sums(old) != sums(f)) return false
      return File(dir, f.file).length() == f.size
    }
    // the landmarks of the vehicles not chosen are not downloaded
    fun skipped(f: PackageFile) = (f.file == GhRouting.PACKAGE_TRUCK || f.file == GhRouting.PACKAGE_CAR) && f.file !in ghWanted
    // the packages made before GraphHopper alone (until they are rebuilt) still list the old
    // routing graph (percorsi.tar): never downloaded
    val files = manifest.files.filter { it.file != OLD_ROUTING_FILE && !skipped(it) && !unchanged(it) }
    if (prev != null) Log.i(TAG, "update $id: ${files.joinToString { it.file }.ifEmpty { "nothing changed" }}")
    val parts = files.flatMap { f -> f.parts.map { "$base/${it.name}" to it } }
    val total = parts.sumOf { it.second.size }
    val tmp = File(dir, ".parts").apply { mkdirs() }
    // files already brought down by the old versions: kept, they are checked before use (a damaged
    // one is fetched again by itself), so the data already spent is not spent again
    // (in the old download folder, or already put in place by an old version that stopped later on)
    val old = File(dir, ".download")
    val sources = listOfNotNull(old.takeIf { it.isDirectory }, dir.takeIf { !File(it, "manifest.json").exists() })
    for ((_, p) in parts) {
      val to = File(tmp, p.name)
      if (to.exists()) continue
      for (src in sources) {
        val f = File(src, p.name)
        if (f.length() != p.size) continue
        if (!f.renameTo(to)) {
          runCatching { f.copyTo(to, overwrite = true) }.onFailure { to.delete() }
          if (to.length() != p.size) continue
        }
        val n = ((p.size + FastDownloader.SEG - 1) / FastDownloader.SEG).toInt().coerceAtLeast(1)
        File(tmp, p.name + ".seg").writeText((0 until n).joinToString("\n", postfix = "\n"))
        Log.i(TAG, "download $id: ${p.name} kept from the previous download (${src.name})")
        break
      }
    }
    if (old.isDirectory) runCatching { old.deleteRecursively() }
    File(tmp, "job.txt").writeText("${job.label}\nfalse\n")
    // what is already there from an interrupted download (the files are made full size at once,
    // what counts is the pieces done)
    val have = parts.sumOf { (_, p) ->
      when {
        File(tmp, p.name + ".ok").exists() -> p.size
        else -> minOf(p.size, (File(tmp, p.name + ".seg").takeIf { it.exists() }?.readLines()?.size ?: 0) * FastDownloader.SEG)
      }
    }
    // the files are written in place; only the files in several parts need room twice
    // (the GraphHopper data are unpacked on the tablet: about 2.5 times their packages)
    val need = total - have + files.filter { it.parts.size > 1 }.sumOf { it.size } + 200_000_000 +
        files.filter { it.file in GhRouting.PACKAGES }.sumOf { it.size } * 7 / 2
    if (freeBytes() < need) {
      throw IllegalStateException(String.format(Locale.ITALIAN, "Spazio insufficiente: servono %.1f GB liberi", need / 1e9))
    }
    Log.i(TAG, "download $id: ${parts.size} files, ${total / 1_000_000} MB")
    val started = System.currentTimeMillis()
    val waiting = AtomicReference<String?>(null)
    val speed = Speed()
    val downloader = FastDownloader(connections = 6) {
      while (true) {
        val why = blocked()
        waiting.set(why)
        if (why == null) break
        delay(2000)
      }
    }
    val items = parts.map { (url, p) -> FastDownloader.Item(url, p.size, p.sha256, File(tmp, p.name)) }
    downloader.fetch(items) { got -> setState(id, DownloadState.Running(got, total, waiting.get() ?: speed.text(got, total))) }
    val secs = (System.currentTimeMillis() - started) / 1000.0
    Log.i(TAG, String.format(Locale.US, "download %s: %d MB in %.0f s (%.1f MB/s)", id, total / 1_000_000, secs, total / 1e6 / secs.coerceAtLeast(0.1)))

    // join the parts into the final files
    for (f in files) {
      setState(id, DownloadState.Running(total, total, "Preparazione ${f.file}"))
      join(f.parts.map { File(tmp, it.name) }, File(dir, f.file))
    }
    // the GraphHopper data come packed: unpacked now, once, not at the first route
    val ghFiles = files.filter { it.file in GhRouting.PACKAGES }
    if (ghFiles.isNotEmpty()) {
      setState(id, DownloadState.Running(total, total, "Installazione calcolo percorsi GraphHopper"))
      // the graph in use is closed before it is replaced
      runCatching { app.navmaster.truck.AppGraph.gh.reset() }
      val gh = GhRouting.ready(dir)
      // a package unpacked is removed: its source is noted, to know when it changes
      if (gh != null) for (f in ghFiles) if (!File(dir, f.file).exists()) File(gh, GhRouting.sourceFile(f.file)).writeText(sums(f))
    }
    removeOldRoutingData(dir)
    File(dir, "manifest.json").writeText(manifestText)
    tmp.deleteRecursively()
    refresh()
  }

  private fun join(parts: List<File>, out: File) {
    if (parts.size == 1 && parts[0].absolutePath == out.absolutePath) return
    if (parts.size == 1) {
      out.delete()
      if (!parts[0].renameTo(out)) parts[0].copyTo(out, overwrite = true)
      parts[0].delete()
      return
    }
    out.outputStream().buffered(1 shl 20).use { o ->
      for (p in parts) p.inputStream().buffered(1 shl 20).use { it.copyTo(o, 1 shl 20) }
    }
    parts.forEach { it.delete() }
  }

  /**
   * The routing data of the versions before GraphHopper alone (10/2026), not used any more:
   * removed from a country's folder ([dir]), or from every country and the shared folder of the
   * Europe-wide graph (no argument), so that the space comes back on the tablet.
   */
  fun removeOldRoutingData(dir: File? = null) {
    val dirs = if (dir != null) listOf(dir) else (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
    for (d in dirs) {
      for (name in listOf(OLD_ROUTING_FILE, "europa-tiles.txt")) {
        val f = File(d, name)
        if (f.exists()) {
          Log.i(TAG, "${d.name}: old routing data $name removed (${f.length() / 1_000_000} MB)")
          f.delete()
        }
      }
    }
    if (dir == null) {
      val europe = File(context.getExternalFilesDir(null), "grafo-europa")
      if (europe.exists()) {
        Log.i(TAG, "old Europe-wide routing graph removed")
        europe.deleteRecursively()
      }
      refresh()
    }
  }

  fun delete(id: String) {
    File(root, id).deleteRecursively()
    setState(id, null)
    refresh()
  }

  companion object {
    private const val TAG = "NavMasterData"
    const val RELEASES = "https://github.com/francescobuzle-a11y/navmaster/releases/download"
    /** The routing graph of the versions before GraphHopper alone, never downloaded nor kept. */
    const val OLD_ROUTING_FILE = "percorsi.tar"
  }
}
