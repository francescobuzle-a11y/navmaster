package app.navmaster.truck.live

import android.util.Log
import app.navmaster.truck.core.Geo
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.ferrostar.GeographicCoordinate

/**
 * Reports shared between NavMaster drivers (police, queues, accidents, closures, lorry checks...),
 * without an account and without a server of our own: they travel on ntfy (ntfy.sh, free and open
 * source; any other ntfy server can be set, also one's own). Europe is cut in squares of a quarter
 * degree (about 28 × 20 km), one "topic" each: a driver publishes in the square where he is and
 * reads the squares of the road ahead. A report carries only the kind, the point, the direction of
 * travel and a random tag of the device (to count confirmations once), never who sent it.
 *
 * Confirmations ("still there") keep a report alive, two "no longer there" more than the
 * confirmations remove it; every kind expires by itself ([LiveKind.ttlMin]).
 */
object SharedReports {
  private const val TAG = "NavMasterLive"
  private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  /** Topic names start with this (the emulator tests use their own, not to disturb real drivers). */
  @Volatile var prefix = "navmaster-eu1-"

  fun topic(lat: Double, lon: Double): String = "$prefix${floor(lat * 4).toInt()}_${floor(lon * 4).toInt()}"

  /** The squares of a list of points (the route ahead). */
  fun topics(points: List<GeographicCoordinate>): List<String> = points.map { topic(it.lat, it.lng) }.distinct()

  private fun base(server: String) = server.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }

  /** Sends a new report; the id ntfy gives it, or null. */
  fun publish(server: String, device: String, kind: LiveKind, lat: Double, lon: Double, heading: Double?): String? =
      send(server, topic(lat, lon), buildJsonObject {
        put("v", 1)
        put("a", "new")
        put("k", kind.name)
        put("la", Math.round(lat * 1e5) / 1e5)
        put("lo", Math.round(lon * 1e5) / 1e5)
        if (heading != null) put("h", heading.toInt())
        put("d", device)
      })

  /** "Still there" (true) or "no longer there" (false) on a report seen on the road. */
  fun vote(server: String, device: String, e: LiveEvent, yes: Boolean): Boolean =
      send(server, topic(e.lat, e.lon), buildJsonObject {
        put("v", 1)
        put("a", if (yes) "yes" else "no")
        put("r", e.id.removePrefix("nx:"))
        put("d", device)
      }) != null

  private fun send(server: String, topic: String, payload: JsonObject): String? =
      try {
        val body = buildJsonObject {
          put("topic", topic)
          put("message", payload.toString())
          putJsonArray("tags") { add(JsonPrimitive("navmaster")) }
        }.toString()
        client.newCall(Request.Builder().url(base(server)).post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "NavMaster/1.0").build()).execute().use { r ->
          val text = r.body.string()
          if (!r.isSuccessful) {
            Log.w(TAG, "report not sent: HTTP ${r.code} $text")
            null
          } else {
            ((json.parseToJsonElement(text) as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull.also {
              Log.i(TAG, "report sent to $topic: $payload -> $it")
            }
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "report not sent: $e")
        null
      }

  private class Raw(val id: String, val timeMs: Long, val o: JsonObject)

  /** The reports still valid in these squares. */
  fun read(server: String, topics: List<String>, device: String): List<LiveEvent> {
    if (topics.isEmpty()) return emptyList()
    val raws = mutableListOf<Raw>()
    for (chunk in topics.chunked(12)) {
      val url = "${base(server)}/${chunk.joinToString(",")}/json?poll=1&since=12h"
      val text = (try {
        client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/1.0").build()).execute().use { r ->
          if (r.isSuccessful) r.body.string() else null.also { Log.w(TAG, "reports: HTTP ${r.code}") }
        }
      } catch (e: Exception) {
        Log.w(TAG, "reports: $e")
        null
      }) ?: continue
      for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val msg = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
        if ((msg["event"] as? JsonPrimitive)?.contentOrNull != "message") continue
        val id = (msg["id"] as? JsonPrimitive)?.contentOrNull ?: continue
        val t = ((msg["time"] as? JsonPrimitive)?.longOrNull ?: continue) * 1000
        val body = (msg["message"] as? JsonPrimitive)?.contentOrNull ?: continue
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: continue
        if ((o["v"] as? JsonPrimitive)?.contentOrNull != "1") continue
        raws += Raw(id, t, o)
      }
    }
    return merge(raws, device)
  }

  private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

  private fun d(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull

  /**
   * Reports and votes together: the same thing reported by several drivers (same kind, within 300 m,
   * in 30 minutes) is one event with more confirmations.
   */
  private fun merge(raws: List<Raw>, device: String): List<LiveEvent> {
    val now = System.currentTimeMillis()
    class Acc(var e: LiveEvent, val ids: MutableSet<String>, val yes: MutableSet<String>, val no: MutableSet<String>, var last: Long)
    val acc = mutableListOf<Acc>()
    for (r in raws.filter { s(it.o, "a") == "new" }.sortedBy { it.timeMs }) {
      val kind = LiveKind.of(s(r.o, "k")) ?: continue
      val lat = d(r.o, "la") ?: continue
      val lon = d(r.o, "lo") ?: continue
      val dev = s(r.o, "d") ?: ""
      val same = acc.firstOrNull { a ->
        a.e.kind == kind && Geo.dist(a.e.lat, a.e.lon, lat, lon) < 300 && r.timeMs - a.last < 30 * 60_000 &&
            (a.e.headingDeg == null || d(r.o, "h") == null || kotlin.math.abs(Geo.angleDiff(a.e.headingDeg!!, d(r.o, "h")!!)) < 60)
      }
      if (same != null) {
        same.ids += r.id
        same.yes += dev
        same.last = r.timeMs
        continue
      }
      acc += Acc(
          LiveEvent(id = "nx:${r.id}", source = "Segnalazione NavMaster", kind = kind, title = kind.label, lat = lat, lon = lon,
              timeMs = r.timeMs, official = false, mine = dev == device, headingDeg = d(r.o, "h")),
          mutableSetOf(r.id), mutableSetOf(dev), mutableSetOf(), r.timeMs)
    }
    for (r in raws.filter { s(it.o, "a") == "yes" || s(it.o, "a") == "no" }.sortedBy { it.timeMs }) {
      val ref = s(r.o, "r") ?: continue
      val a = acc.firstOrNull { ref in it.ids } ?: continue
      val dev = s(r.o, "d") ?: ""
      if (s(r.o, "a") == "yes") {
        a.yes += dev
        a.no -= dev
        a.last = maxOf(a.last, r.timeMs)
      } else {
        a.no += dev
        a.yes -= dev
      }
    }
    return acc.mapNotNull { a ->
      val alive = now - a.last < a.e.kind.ttlMin * 60_000L
      val removed = (a.no.size >= 2 && a.no.size > a.yes.size) || device in a.no
      if (!alive || removed) null
      else a.e.copy(timeMs = a.last, confirms = (a.yes.size - 1).coerceAtLeast(0), denies = a.no.size, mine = a.e.mine || device in a.yes)
    }
  }
}
