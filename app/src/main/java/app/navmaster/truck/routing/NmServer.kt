package app.navmaster.truck.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The NavMaster server (navmaster-engine's own `engine-server`, the same engine and the same
 * answers as the tablet, on a graph of the whole of Europe): used for the trips the graphs on the
 * tablet cannot cover, such as a trip across a border or through a country not downloaded.
 * The server speaks openrouteservice's API (`/ors/v2/directions/{profile}/geojson`) and, like
 * the engine on the tablet, adds the facts about the roads (`navmaster.roads`).
 */
class NmServer(private val context: Context, private val url: () -> String, private val key: () -> String) {
  private val client = OkHttpClient.Builder()
      .connectTimeout(6, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

  /** Why the server cannot be used now (for the log), null when it can. */
  @Volatile var note: String? = null
    private set

  fun configured(): Boolean = url().isNotBlank()

  /** Why the server cannot be used now, in words; null when it can. */
  fun unavailable(): String? {
    if (!configured()) return "nessun server NavMaster impostato (Impostazioni › Percorso)"
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "nessuna connessione a internet"
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "nessuna connessione a internet"
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "nessuna connessione a internet"
    return null
  }

  /** openrouteservice's directions from the server: its GeoJSON (or its error object), null when it cannot answer. */
  fun directions(profile: String, body: String): String? {
    unavailable()?.let { note = it; return null }
    val started = SystemClock.elapsedRealtime()
    val base = url().trim().trimEnd('/')
    return try {
      val b = Request.Builder()
          .url("$base/ors/v2/directions/$profile/geojson")
          .header("Accept", "application/geo+json, application/json")
          .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
      key().takeIf { it.isNotBlank() }?.let { b.header("Authorization", it) }
      client.newCall(b.build()).execute().use { rsp ->
        val text = rsp.body.string()
        if (!rsp.isSuccessful && !text.trimStart().startsWith("{")) {
          note = "server NavMaster ${rsp.code}: ${text.take(160)}"
          Log.w(TAG, note!!)
          null
        } else {
          note = null
          Log.i(TAG, "server NavMaster $profile answered in ${SystemClock.elapsedRealtime() - started} ms (${rsp.code})")
          text
        }
      }
    } catch (e: Exception) {
      note = "server NavMaster: $e"
      Log.w(TAG, note!!)
      null
    }
  }

  companion object {
    private const val TAG = "NavMasterRoute"
  }
}
