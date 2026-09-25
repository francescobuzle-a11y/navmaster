package app.navmaster.truck.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import java.io.File
import java.security.MessageDigest
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
    val valhalla: String = "",
    val format: Int = 1,
    val files: List<PackageFile>,
)

/** A country (or the test area) whose data is on the tablet. */
data class InstalledRegion(val id: String, val manifest: Manifest, val dir: File) {
  val label: String
    get() = manifest.label

  val map: File
    get() = File(dir, "mappa.pmtiles")

  val routingTar: File
    get() = File(dir, "percorsi.tar")

  val limits: File
    get() = File(dir, "limiti.sqlite")

  val poi: File
    get() = File(dir, "poi.sqlite")

  val addresses: File
    get() = File(dir, "indirizzi.sqlite")

  val critical: File
    get() = File(dir, "criticita.sqlite")

  /** Its tiles of the Europe-wide graph are in the shared tile folder. */
  val hasEuropeTiles: Boolean
    get() = File(dir, "europa-tiles.txt").exists()

  val sizeBytes: Long
    get() = dir.listFiles()?.sumOf { it.length() } ?: 0
}

sealed class DownloadState {
  data object Queued : DownloadState()

  data class Running(val doneBytes: Long, val totalBytes: Long, val step: String) : DownloadState()

  data class Failed(val message: String) : DownloadState()

  data object Done : DownloadState()
}

private data class Job(val id: String, val label: String, val wifiOnly: Boolean, val useEurope: Boolean)

/**
 * Offline packages, one folder per country under the app's external files. They are published on
 * GitHub releases (dati-<country>) with a manifest; big files come in parts that are joined here.
 * With the Europe graph, the routing tiles of every country go in one shared folder so routes
 * cross borders. Downloads go through Android's DownloadManager: they survive the app going to the
 * background, resume after a dropped connection and can be limited to Wi-Fi.
 */
class RegionManager(private val context: Context, private val catalog: CatalogStore) {
  val root: File = File(context.getExternalFilesDir(null), "regions").apply { mkdirs() }
  val europeTiles: File = File(context.getExternalFilesDir(null), "grafo-europa/tiles").apply { mkdirs() }
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
      for (job in queue) {
        try {
          doDownload(job)
          setState(job.id, DownloadState.Done)
        } catch (e: Exception) {
          Log.e(TAG, "download ${job.id} failed", e)
          setState(job.id, DownloadState.Failed(e.message ?: e.toString()))
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
              if (region.map.exists() && (region.routingTar.exists() || region.hasEuropeTiles)) region else null
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

  fun europeTilesInstalled(): Boolean = _installed.value.any { it.hasEuropeTiles }

  private fun setState(id: String, s: DownloadState?) =
      _states.update { m -> if (s == null) m - id else m + (id to s) }

  fun download(id: String, label: String, wifiOnly: Boolean, useEurope: Boolean) {
    val s = _states.value[id]
    if (s is DownloadState.Running || s is DownloadState.Queued) return
    setState(id, DownloadState.Queued)
    queue.trySend(Job(id, label, wifiOnly, useEurope))
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
    val tiles = if (job.useEurope) catalog.country(id)?.europeTiles?.takeIf { it.parts.isNotEmpty() } else null

    // with the Europe graph the country graph is not needed
    val files = manifest.files.filter { tiles == null || it.file != "percorsi.tar" }
    val parts = files.flatMap { f -> f.parts.map { "$base/${it.name}" to it } } +
        (tiles?.parts?.map { "$RELEASES/grafo-europa/${it.name}" to it } ?: emptyList())
    val total = parts.sumOf { it.second.size }
    val need = (total * 1.25).toLong() + (tiles?.size ?: 0)
    if (freeBytes() < need) {
      throw IllegalStateException("Spazio insufficiente: servono ${need / 1_000_000_000.0} GB liberi")
    }
    val dir = File(root, id).apply { mkdirs() }
    val tmp = File(dir, ".download").apply { mkdirs() }
    val dm = context.getSystemService(DownloadManager::class.java)

    // parts already downloaded and intact are kept (a restarted download does not start over)
    val ids = mutableMapOf<Long, PackagePart>()
    for ((i, pu) in parts.withIndex()) {
      val (url, part) = pu
      val target = File(tmp, part.name)
      if (target.exists() && target.length() == part.size) continue
      target.delete()
      val req =
          DownloadManager.Request(Uri.parse(url))
              .setTitle("NavMaster · ${job.label} (${i + 1}/${parts.size})")
              .setDescription("Mappe offline per mezzi pesanti")
              .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
              .setAllowedOverMetered(!job.wifiOnly)
              .setAllowedOverRoaming(!job.wifiOnly)
              .setDestinationUri(Uri.fromFile(target))
      ids[dm.enqueue(req)] = part
    }
    while (ids.isNotEmpty()) {
      var done = parts.map { it.second }.filter { p -> ids.values.none { it == p } }.sumOf { it.size }
      val finished = mutableListOf<Long>()
      dm.query(DownloadManager.Query().setFilterById(*ids.keys.toLongArray())).use { c ->
        while (c.moveToNext()) {
          val did = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
          val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
          val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
          when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
              finished += did
              done += ids[did]?.size ?: 0
            }
            DownloadManager.STATUS_FAILED -> {
              val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
              throw IllegalStateException("Scaricamento interrotto (codice $reason)")
            }
            DownloadManager.STATUS_PAUSED -> done += got
            else -> done += got
          }
        }
      }
      finished.forEach { ids.remove(it) }
      setState(id, DownloadState.Running(done, total, if (job.wifiOnly) "Scaricamento (solo Wi-Fi)" else "Scaricamento"))
      if (ids.isNotEmpty()) delay(1000)
    }

    // check every part
    var checked = 0L
    for ((_, p) in parts) {
      setState(id, DownloadState.Running(checked, total, "Controllo integrità"))
      val file = File(tmp, p.name)
      if (file.length() != p.size || sha256(file) != p.sha256) {
        file.delete()
        throw IllegalStateException("File ${p.name} danneggiato: riprova, verrà riscaricato solo lui")
      }
      checked += p.size
    }
    // join the parts into the final files
    for (f in files) {
      setState(id, DownloadState.Running(total, total, "Preparazione ${f.file}"))
      join(f.parts.map { File(tmp, it.name) }, File(dir, f.file))
    }
    if (tiles != null) {
      setState(id, DownloadState.Running(total, total, "Installazione grafo Europa"))
      val tar = File(tmp, "tiles-$id.tar")
      join(tiles.parts.map { File(tmp, it.name) }, tar)
      val list = Tar.extract(tar, europeTiles)
      File(dir, "europa-tiles.txt").writeText(list.joinToString("\n"))
      tar.delete()
      File(dir, "percorsi.tar").delete()
    }
    File(dir, "manifest.json").writeText(manifestText)
    tmp.deleteRecursively()
    refresh()
  }

  private fun join(parts: List<File>, out: File) {
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

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(1 shl 20).use { input ->
      val buf = ByteArray(1 shl 20)
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  fun delete(id: String) {
    val dir = File(root, id)
    val mine = File(dir, "europa-tiles.txt").takeIf { it.exists() }?.readLines()?.toSet() ?: emptySet()
    if (mine.isNotEmpty()) {
      // tiles on a border are shared with the neighbouring countries still installed
      val others = _installed.value.filter { it.id != id }.flatMap { r ->
        File(r.dir, "europa-tiles.txt").takeIf { it.exists() }?.readLines() ?: emptyList()
      }.toSet()
      for (rel in mine - others) File(europeTiles, rel).delete()
    }
    dir.deleteRecursively()
    setState(id, null)
    refresh()
  }

  companion object {
    private const val TAG = "NavMasterData"
    const val RELEASES = "https://github.com/francescobuzle-a11y/navmaster/releases/download"
  }
}
