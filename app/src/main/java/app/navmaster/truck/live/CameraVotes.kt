package app.navmaster.truck.live

import android.util.Log
import app.navmaster.truck.core.Geo
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The drivers' answers about the fixed speed cameras: after passing one the tablet asks "c'era?"
 * (NavViewModel), the answer is kept here and shared with the other NavMaster tablets, so a camera
 * that is gone stops being announced and one that is there says how many drivers saw it.
 *
 * Shared without a server of our own, like the reports (SharedReports): every answer goes to one
 * ntfy topic ("navmaster-eu1-cam"), which keeps messages for 12 hours; every tablet reads the topic
 * while driving and keeps what it read in a file, and a scheduled job of the repository
 * (.github/workflows/voti-autovelox.yml) gathers the topic every few hours into voti.json on the
 * release "voti-autovelox", which the tablets download once a day: so an answer is not lost when a
 * tablet stays off for days. An answer carries the point, yes or no, a random tag of the device
 * and its own random id, never who gave it.
 *
 * The rules ([statusOf]): only the latest answer of each device counts, within two years. A camera
 * is left out when at least two devices said "no" and more said "no" than "yes", or when this
 * tablet said "no" on two different days (since its last "yes").
 */
object CameraVotes {
  private const val TAG = "NavMasterCameras"
  private const val FILE = "voti-autovelox.json"
  private const val NEAR_M = 30.0
  private const val MAX_AGE_MS = 2L * 365 * 24 * 3600_000L
  private const val MAX_VOTES = 50_000
  const val RELEASE_FILE = "voti-autovelox/voti.json"

  data class Vote(val id: String, val lat: Double, val lon: Double, val yes: Boolean, val device: String, val timeMs: Long)

  /** What the drivers say about one camera: devices that saw it, devices that did not, left out. */
  data class Status(val yes: Int, val no: Int, val hidden: Boolean, val mine: Boolean?) {
    val confirmed: Boolean
      get() = yes > 0 && yes >= no
  }

  private val json = Json { ignoreUnknownKeys = true }
  private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
  private val githubClient by lazy {
    OkHttpClient.Builder().dns(app.navmaster.truck.data.GithubDns).connectTimeout(8, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
  }

  @Volatile private var votes: List<Vote>? = null
  @Volatile private var lastTopicAt = 0L
  @Volatile private var lastReleaseAt = 0L

  fun topic(): String = SharedReports.prefix + "cam"

  private fun file(): File = File(app.navmaster.truck.AppGraph.app.filesDir, FILE)

  private fun all(): List<Vote> =
      votes ?: synchronized(this) {
        votes ?: runCatching { decode(file().takeIf { it.exists() }?.readText() ?: "") }.getOrDefault(emptyList()).also { votes = it }
      }

  /** What the drivers say about the camera at [lat], [lon] (this tablet is [device]). */
  fun status(lat: Double, lon: Double, device: String): Status = statusOf(all(), lat, lon, device, System.currentTimeMillis())

  /** Whether this tablet answered about the camera in the last [days] days (then it is not asked again). */
  fun answeredRecently(lat: Double, lon: Double, device: String, days: Int = 60): Boolean {
    val since = System.currentTimeMillis() - days * 24 * 3600_000L
    return all().any { it.device == device && it.timeMs >= since && Geo.dist(it.lat, it.lon, lat, lon) <= NEAR_M }
  }

  /** The driver's answer: kept, and sent to the other tablets when [share]. */
  fun record(lat: Double, lon: Double, yes: Boolean, device: String, server: String, share: Boolean) {
    val v = Vote(java.util.UUID.randomUUID().toString().replace("-", "").take(16), round5(lat), round5(lon), yes, device, System.currentTimeMillis())
    add(listOf(v))
    Log.i(TAG, "answer ${if (yes) "yes" else "no"} at ${v.lat},${v.lon}${if (share) "" else " (not shared)"}")
    if (share) publish(server, v)
  }

  /**
   * The answers of the other tablets: the topic every 30 minutes (what ntfy still keeps, 12
   * hours), the gathered file of the repository every 12 hours. Called while driving (IO thread).
   */
  fun refreshIfDue(server: String) {
    val now = System.currentTimeMillis()
    if (now - lastTopicAt > 30 * 60_000L) {
      lastTopicAt = now
      readTopic(server)?.let { add(it) }
    }
    if (now - lastReleaseAt > 12 * 3600_000L) {
      lastReleaseAt = now
      // GitHub silent (no network, a slow day): again in an hour, not in 12
      readRelease()?.let { add(it) } ?: run { lastReleaseAt = now - 11 * 3600_000L }
    }
  }

  @Synchronized
  private fun add(new: List<Vote>) {
    if (new.isEmpty()) return
    val now = System.currentTimeMillis()
    val byId = LinkedHashMap<String, Vote>()
    for (v in all()) byId[v.id] = v
    var added = 0
    for (v in new) if (byId.put(v.id, v) == null) added++
    if (added == 0) return
    val kept = byId.values.filter { now - it.timeMs < MAX_AGE_MS }.sortedBy { it.timeMs }.takeLast(MAX_VOTES)
    votes = kept
    runCatching {
      val tmp = File(file().parentFile, "$FILE.new")
      tmp.writeText(encode(kept))
      tmp.renameTo(file())
    }.onFailure { Log.w(TAG, "answers not saved: $it") }
    Log.i(TAG, "answers: $added new, ${kept.size} kept")
  }

  private fun base(server: String) = server.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }

  private fun publish(server: String, v: Vote) {
    try {
      val body = buildJsonObject {
        put("topic", topic())
        put("message", payload(v).toString())
        putJsonArray("tags") { add(JsonPrimitive("navmaster")) }
      }.toString()
      client.newCall(Request.Builder().url(base(server)).post(body.toRequestBody("application/json".toMediaType()))
          .header("User-Agent", "NavMaster/1.0").build()).execute().use { r ->
        if (!r.isSuccessful) Log.w(TAG, "answer not sent: HTTP ${r.code}") else Log.i(TAG, "answer sent to ${topic()}")
      }
    } catch (e: Exception) {
      Log.w(TAG, "answer not sent: $e")
    }
  }

  private fun readTopic(server: String): List<Vote>? =
      try {
        client.newCall(Request.Builder().url("${base(server)}/${topic()}/json?poll=1&since=12h").header("User-Agent", "NavMaster/1.0").build())
            .execute().use { r -> if (r.isSuccessful) parseTopic(r.body.string()) else null.also { Log.w(TAG, "answers: HTTP ${r.code}") } }
      } catch (e: Exception) {
        Log.w(TAG, "answers: $e")
        null
      }

  private fun readRelease(): List<Vote>? =
      try {
        githubClient.newCall(Request.Builder().url("${app.navmaster.truck.data.RegionManager.RELEASES}/$RELEASE_FILE").build()).execute().use { r ->
          // 404 until the job has gathered the first answers
          if (r.isSuccessful) decode(r.body.string()) else null
        }
      } catch (e: Exception) {
        Log.w(TAG, "gathered answers: $e")
        null
      }

  // ------------------------------------------------------------------ pure (tested on the computer)

  private fun round5(v: Double) = Math.round(v * 1e5) / 1e5

  /** The message of one answer on ntfy. */
  fun payload(v: Vote): JsonObject = buildJsonObject {
    put("v", 1)
    put("a", "cam")
    put("i", v.id)
    put("la", v.lat)
    put("lo", v.lon)
    put("y", if (v.yes) 1 else 0)
    put("d", v.device)
  }

  /** One answer from its message, or null when it is not a valid one ([timeMs]: when ntfy got it). */
  fun fromPayload(o: JsonObject, timeMs: Long): Vote? {
    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    if (s("v") != "1" || s("a") != "cam") return null
    val id = s("i")?.takeIf { it.length in 6..40 } ?: return null
    val lat = (o["la"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val lon = (o["lo"] as? JsonPrimitive)?.doubleOrNull ?: return null
    if (lat !in 27.0..72.0 || lon !in -32.0..45.0) return null
    val y = (o["y"] as? JsonPrimitive)?.let { it.intOrNull ?: it.booleanOrNull?.let { b -> if (b) 1 else 0 } } ?: return null
    val dev = s("d")?.takeIf { it.isNotBlank() && it.length <= 40 } ?: return null
    return Vote(id, lat, lon, y == 1, dev, timeMs)
  }

  /** The answers in what ntfy returns for the topic (one JSON message per line). */
  fun parseTopic(text: String): List<Vote> =
      text.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val msg = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
        if ((msg["event"] as? JsonPrimitive)?.contentOrNull != "message") return@mapNotNull null
        val t = ((msg["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null) * 1000
        val body = (msg["message"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return@mapNotNull null
        fromPayload(o, t)
      }.toList()

  /** The file of the answers (the tablet's own and the gathered one have the same form). */
  fun encode(list: List<Vote>): String = buildJsonObject {
    put("version", 1)
    put("votes", buildJsonArray {
      for (v in list) add(buildJsonObject {
        put("i", v.id); put("la", v.lat); put("lo", v.lon); put("y", if (v.yes) 1 else 0); put("d", v.device); put("t", v.timeMs / 1000)
      })
    })
  }.toString()

  fun decode(text: String): List<Vote> {
    if (text.isBlank()) return emptyList()
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
    val arr = root["votes"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { e ->
      val o = e as? JsonObject ?: return@mapNotNull null
      val t = (o["t"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
      fromPayload(buildJsonObject { put("v", 1); put("a", "cam"); o.forEach { (k, v) -> if (k != "t") put(k, v) } }, t * 1000)
    }
  }

  /** The rules of the top of the file, on [list], for the camera at [lat], [lon]. */
  fun statusOf(list: List<Vote>, lat: Double, lon: Double, device: String, now: Long): Status {
    val near = list.filter { now - it.timeMs < MAX_AGE_MS && Geo.dist(it.lat, it.lon, lat, lon) <= NEAR_M }
    if (near.isEmpty()) return Status(0, 0, false, null)
    val latest = near.groupBy { it.device }.mapValues { (_, l) -> l.maxBy { it.timeMs } }
    val yes = latest.values.count { it.yes }
    val no = latest.values.count { !it.yes }
    val mine = near.filter { it.device == device }
    val lastYes = mine.filter { it.yes }.maxOfOrNull { it.timeMs } ?: 0L
    val myNoDays = mine.filter { !it.yes && it.timeMs > lastYes }.map { it.timeMs / (24 * 3600_000L) }.distinct().size
    val hidden = (no >= 2 && no > yes) || myNoDays >= 2
    return Status(yes, no, hidden, latest[device]?.yes)
  }
}
