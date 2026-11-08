package app.navmaster.truck.live

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The speed cameras and Tutor sections of each country, checked (bare points, enforcement
 * relations, the official lists of France and Spain, Italy's national census): a small file per
 * country (autovelox-<country>.sqlite, the tables of limiti.sqlite with the cameras alone), built
 * every week by .github/workflows/autovelox.yml on the release "autovelox" with indice.json.
 * The country packages no longer carry limiti.sqlite (everything else comes from the engine's
 * graph), so these files are what lets LimitsIndex check the graph's cameras.
 *
 * Kept in the app's own storage as <country>-<date>.sqlite (a new name for each version, so the
 * open databases are opened again: RegionDbs), the older ones removed; asked once a day.
 */
object CameraFiles {
  private const val TAG = "NavMasterCameras"
  const val INDEX = "autovelox/indice.json"

  data class Entry(val name: String, val size: Long, val stamp: String) {
    /** The country of the file ("autovelox-italy.sqlite" → "italy"). */
    val country: String
      get() = name.removePrefix("autovelox-").removeSuffix(".sqlite")
  }

  private val json = Json { ignoreUnknownKeys = true }
  private val client by lazy {
    OkHttpClient.Builder().dns(app.navmaster.truck.data.GithubDns).connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
  }
  @Volatile private var lastAt = 0L

  private fun dir(): File = File(app.navmaster.truck.AppGraph.app.filesDir, "autovelox").apply { mkdirs() }

  /** The newest file of the country's cameras, or null when none was downloaded. */
  fun fileFor(country: String): File? =
      dir().listFiles { f -> f.name.startsWith("$country-") && f.name.endsWith(".sqlite") }?.maxByOrNull { it.name }

  /** Brings the files of [countries] up to date, once a day (IO thread; nothing without the network). */
  @Synchronized
  fun refreshIfDue(countries: Collection<String>, force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (countries.isEmpty() || (!force && now - lastAt < 24 * 3600_000L)) return
    lastAt = now
    val index = runCatching {
      client.newCall(Request.Builder().url("${app.navmaster.truck.data.RegionManager.RELEASES}/$INDEX").build()).execute().use { r ->
        if (r.isSuccessful) parseIndex(r.body.string()) else null
      }
    }.onFailure { Log.w(TAG, "camera files: $it") }.getOrNull() ?: return
    for (e in index) {
      if (e.country !in countries) continue
      val target = File(dir(), "${e.country}-${e.stamp}.sqlite")
      if (target.exists() && target.length() == e.size) continue
      val tmp = File(dir(), "${e.country}.download")
      val ok = runCatching {
        client.newCall(Request.Builder().url("${app.navmaster.truck.data.RegionManager.RELEASES}/autovelox/${e.name}").build()).execute().use { r ->
          if (!r.isSuccessful) return@use false
          tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
          tmp.length() == e.size
        }
      }.onFailure { Log.w(TAG, "camera file ${e.name}: $it") }.getOrDefault(false)
      if (!ok || !tmp.renameTo(target)) {
        tmp.delete()
        continue
      }
      // the older versions of the country (an open database keeps reading its own until closed)
      dir().listFiles { f -> f.name.startsWith("${e.country}-") && f.name.endsWith(".sqlite") && f.name != target.name }?.forEach { it.delete() }
      Log.i(TAG, "camera file ${e.name}: ${e.size / 1024} kB (${e.stamp})")
    }
  }

  /** The files of indice.json: name, size, when it was published (as digits, for the file's name). */
  fun parseIndex(text: String): List<Entry> {
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
    val files = root["files"] as? JsonArray ?: return emptyList()
    return files.mapNotNull { e ->
      val o = e as? JsonObject ?: return@mapNotNull null
      val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("autovelox-") && it.endsWith(".sqlite") } ?: return@mapNotNull null
      val size = (o["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
      val stamp = (o["updated"] as? JsonPrimitive)?.contentOrNull?.filter { it.isDigit() }?.take(14)?.takeIf { it.length >= 8 } ?: return@mapNotNull null
      // a country id is letters and dashes only (it becomes a file name)
      if (!Regex("autovelox-[a-z-]+\\.sqlite").matches(name)) return@mapNotNull null
      Entry(name, size, stamp)
    }
  }
}
