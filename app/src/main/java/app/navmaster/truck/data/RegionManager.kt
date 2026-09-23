package app.navmaster.truck.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** A region whose map, routing graph and limits are all on the tablet. */
data class InstalledRegion(val id: String, val manifest: Manifest, val dir: File) {
  val map: File
    get() = File(dir, "mappa.pmtiles")

  val routingTar: File
    get() = File(dir, "percorsi.tar")

  val limits: File
    get() = File(dir, "limiti.sqlite")
}

sealed class DownloadState {
  data object Idle : DownloadState()

  data class Running(val label: String, val doneBytes: Long, val totalBytes: Long, val step: String) :
      DownloadState()

  data class Failed(val message: String) : DownloadState()

  data class Done(val label: String) : DownloadState()
}

/**
 * Offline packages, one folder per region under the app's external files. They are published on
 * GitHub releases (dati-<region>) with a manifest; big files come in parts that are joined here.
 * The download itself is left to Android's DownloadManager: it survives the app going to the
 * background, resumes after a dropped connection and can be limited to Wi-Fi, as the project asks.
 */
class RegionManager(private val context: Context) {
  val root: File = File(context.getExternalFilesDir(null), "regions").apply { mkdirs() }
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
  val state: StateFlow<DownloadState> = _state.asStateFlow()
  private val _installed = MutableStateFlow(scan())
  val installed: StateFlow<List<InstalledRegion>> = _installed.asStateFlow()

  fun refresh() {
    _installed.value = scan()
  }

  private fun scan(): List<InstalledRegion> =
      (root.listFiles() ?: emptyArray())
          .filter { it.isDirectory }
          .mapNotNull { dir ->
            val mf = File(dir, "manifest.json")
            if (!mf.exists()) return@mapNotNull null
            try {
              val m = json.decodeFromString(Manifest.serializer(), mf.readText())
              val region = InstalledRegion(dir.name, m, dir)
              if (region.map.exists() && region.routingTar.exists()) region else null
            } catch (e: Exception) {
              Log.w(TAG, "bad manifest in $dir: $e")
              null
            }
          }

  /** Italy first when present; the small test region is only used on the emulator. */
  fun active(): InstalledRegion? =
      _installed.value.let { list -> list.firstOrNull { it.id == "italia" } ?: list.firstOrNull() }

  fun download(regionId: String, label: String, wifiOnly: Boolean) {
    if (_state.value is DownloadState.Running) return
    scope.launch {
      try {
        doDownload(regionId, label, wifiOnly)
      } catch (e: Exception) {
        Log.e(TAG, "download failed", e)
        _state.value = DownloadState.Failed(e.message ?: e.toString())
      }
    }
  }

  private suspend fun doDownload(regionId: String, label: String, wifiOnly: Boolean) {
    _state.value = DownloadState.Running(label, 0, 0, "Lettura dell'elenco file")
    val base = "$RELEASES/dati-$regionId"
    val manifestText =
        OkHttpClient().newCall(Request.Builder().url("$base/manifest.json").build()).execute().use {
          if (!it.isSuccessful) throw IllegalStateException("Pacchetto non disponibile (${it.code})")
          it.body.string()
        }
    val manifest = json.decodeFromString(Manifest.serializer(), manifestText)
    val dir = File(root, regionId).apply { mkdirs() }
    val tmp = File(dir, ".download").apply { mkdirs() }
    val parts = manifest.files.flatMap { it.parts }
    val total = parts.sumOf { it.size }
    val dm = context.getSystemService(DownloadManager::class.java)

    // parts already downloaded and intact are kept (a restarted download does not start over)
    val ids = mutableMapOf<Long, PackagePart>()
    for ((i, part) in parts.withIndex()) {
      val target = File(tmp, part.name)
      if (target.exists() && target.length() == part.size) continue
      target.delete()
      val req =
          DownloadManager.Request(Uri.parse("$base/${part.name}"))
              .setTitle("NavMaster · $label (${i + 1}/${parts.size})")
              .setDescription("Mappa offline e percorsi per mezzi pesanti")
              .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
              .setAllowedOverMetered(!wifiOnly)
              .setAllowedOverRoaming(false)
              .setDestinationUri(Uri.fromFile(target))
      ids[dm.enqueue(req)] = part
    }
    while (ids.isNotEmpty()) {
      var done = parts.filter { p -> ids.values.none { it == p } }.sumOf { it.size }
      val finished = mutableListOf<Long>()
      dm.query(DownloadManager.Query().setFilterById(*ids.keys.toLongArray())).use { c ->
        while (c.moveToNext()) {
          val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
          val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
          val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
          when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
              finished += id
              done += ids[id]?.size ?: 0
            }
            DownloadManager.STATUS_FAILED -> {
              val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
              throw IllegalStateException("Scaricamento interrotto (codice $reason)")
            }
            else -> done += got
          }
        }
      }
      finished.forEach { ids.remove(it) }
      _state.value =
          DownloadState.Running(label, done, total, if (wifiOnly) "Scaricamento (solo Wi-Fi)" else "Scaricamento")
      if (ids.isNotEmpty()) delay(1000)
    }

    // check every part, then join them into the final files
    var checked = 0L
    for (f in manifest.files) {
      for (p in f.parts) {
        _state.value = DownloadState.Running(label, checked, total, "Controllo integrità ${p.name}")
        val file = File(tmp, p.name)
        if (file.length() != p.size || sha256(file) != p.sha256) {
          file.delete()
          throw IllegalStateException("File ${p.name} danneggiato: riprova, verrà riscaricato solo lui")
        }
        checked += p.size
      }
    }
    for (f in manifest.files) {
      _state.value = DownloadState.Running(label, total, total, "Preparazione ${f.file}")
      val out = File(dir, f.file)
      if (f.parts.size == 1) {
        File(tmp, f.parts[0].name).renameTo(out)
      } else {
        out.outputStream().buffered(1 shl 20).use { o ->
          for (p in f.parts) {
            File(tmp, p.name).inputStream().buffered(1 shl 20).use { it.copyTo(o, 1 shl 20) }
          }
        }
        f.parts.forEach { File(tmp, it.name).delete() }
      }
    }
    File(dir, "manifest.json").writeText(manifestText)
    tmp.deleteRecursively()
    refresh()
    _state.value = DownloadState.Done(label)
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

  fun delete(regionId: String) {
    File(root, regionId).deleteRecursively()
    refresh()
  }

  companion object {
    private const val TAG = "NavMasterData"
    const val RELEASES = "https://github.com/francescobuzle-a11y/navmaster/releases/download"
  }
}
