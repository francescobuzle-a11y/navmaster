package app.navmaster.truck.live

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** TomTom requests counted today and this month (saved, so a restart does not reset them). */
@Serializable
data class TomTomUsage(
    val day: String = "",
    val month: String = "",
    val dayIncidents: Int = 0,
    val dayFlow: Int = 0,
    val monthTiles: Int = 0,
    /** Month's tiles when today began (today's budget is reckoned from what was left then). */
    val monthAtDayStart: Int = 0,
    /** After a "too many requests" answer, nothing is asked until this time (epoch ms). */
    val pausedUntil: Long = 0,
) {
  val dayTiles: Int
    get() = dayIncidents + dayFlow
}

/**
 * Keeps TomTom always inside this tablet's part of the free allowance of the fleet's one account
 * (TOMTOM_MONTH_FREE, 200,000 traffic tiles a month, × 90% / FLEET_SIZE, see Fleet). Every request to api.tomtom.com - the traffic
 * incidents the app reads along the route and the traffic colours the map draws - passes through
 * here (also the map's own ones: MapLibre gets this same guard on its HTTP client):
 *
 * - only tiles are used (incidents as vector tiles, traffic flow as map tiles): they are counted in
 *   the large tile allowance, never in the small one of the other requests;
 * - hard limits per month (this tablet's part) and per day (what is left spread over the days
 *   left), the colours on the map at most a quarter of the day: the incidents on the route first;
 * - when the day's part is used up HERE takes over (NavViewModel.refreshLive), till tomorrow;
 * - a "429 too many requests" pauses TomTom until the next day, the other sources go on;
 * - the map keeps traffic tiles 5 minutes instead of asking them again at every movement.
 * Over the limit a request is not sent at all: it gets an empty answer from here.
 */
object TomTomGuard : Interceptor {
  private const val TAG = "NavMasterLive"
  /** This tablet's tiles a month. */
  val MONTH_TILES: Int
    get() = Fleet.share(app.navmaster.truck.BuildConfig.TOMTOM_MONTH_FREE)

  /** Today's tiles at most. */
  val DAY_TILES: Int
    get() = dayLimit(current())

  private fun dayLimit(u: TomTomUsage): Int = Fleet.today(MONTH_TILES - u.monthAtDayStart)

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private var file: File? = null
  private val _usage = MutableStateFlow(TomTomUsage())
  val usage: StateFlow<TomTomUsage> = _usage.asStateFlow()

  fun init(context: Context) {
    val f = File(context.filesDir, "tomtom_usage.json")
    file = f
    _usage.value = runCatching { json.decodeFromString(TomTomUsage.serializer(), f.readText()) }.getOrDefault(TomTomUsage())
  }

  @Synchronized
  private fun current(): TomTomUsage {
    val today = LocalDate.now().toString()
    val month = today.take(7)
    var u = _usage.value
    if (u.month != month) u = u.copy(month = month, monthTiles = 0, monthAtDayStart = 0)
    if (u.day != today) u = u.copy(day = today, dayIncidents = 0, dayFlow = 0, monthAtDayStart = u.monthTiles)
    return u
  }

  @Synchronized
  private fun save(u: TomTomUsage) {
    _usage.value = u
    runCatching { file?.writeText(json.encodeToString(TomTomUsage.serializer(), u)) }
  }

  /** May one more tile be asked now? [incident]: for the route, else for the map colours. */
  @Synchronized
  fun allow(incident: Boolean): Boolean {
    val u = current()
    if (System.currentTimeMillis() < u.pausedUntil) return false
    if (u.monthTiles >= MONTH_TILES) return false
    val day = dayLimit(u)
    if (u.dayTiles >= day) return false
    // the colours on the map: at most a quarter of the day
    if (!incident && u.dayFlow >= day / 4) return false
    save(if (incident) u.copy(dayIncidents = u.dayIncidents + 1, monthTiles = u.monthTiles + 1)
         else u.copy(dayFlow = u.dayFlow + 1, monthTiles = u.monthTiles + 1))
    return true
  }

  /** Share of today's budget still free (0..1): the refresh gets slower when it runs low. */
  fun leftToday(): Double {
    val u = current()
    val day = dayLimit(u)
    return if (day <= 0) 0.0 else (1.0 - u.dayTiles.toDouble() / day).coerceIn(0.0, 1.0)
  }

  /** May the incidents of the road ahead ([n] tiles) still be asked today? false: HERE takes over. */
  @Synchronized
  fun canAsk(n: Int = 1): Boolean {
    val u = current()
    if (System.currentTimeMillis() < u.pausedUntil) return false
    return u.monthTiles + n <= MONTH_TILES && u.dayTiles + n <= dayLimit(u)
  }

  @Synchronized
  private fun pauseTillTomorrow() {
    save(current().copy(pausedUntil = Fleet.tomorrowMs()))
    Log.w(TAG, "TomTom: free allowance reached, paused until tomorrow")
  }

  /** The key was refused (wrong, or not enabled for traffic): try again in an hour. */
  @Synchronized
  private fun pauseOneHour() {
    save(current().copy(pausedUntil = System.currentTimeMillis() + 3_600_000L))
    Log.w(TAG, "TomTom: key refused, paused for an hour")
  }

  fun summary(): String {
    val u = current()
    val paused = System.currentTimeMillis() < u.pausedUntil
    return (if (paused && u.pausedUntil - System.currentTimeMillis() <= 3_600_000L) "Chiave rifiutata da TomTom: controllala. " else "") +
        "TomTom oggi ${u.dayTiles} di ${dayLimit(u)} richieste (${u.dayIncidents} per il percorso, ${u.dayFlow} per la mappa) · " +
        "mese ${u.monthTiles} di $MONTH_TILES" + if (paused) " · in pausa" else ""
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    val req = chain.request()
    if (req.url.host != "api.tomtom.com") return chain.proceed(req)
    val path = req.url.encodedPath
    val incident = "/tile/incidents/" in path
    val flow = "/tile/flow/" in path
    // anything that is not a tile (search, routing, incident details...) is never sent
    if (!(incident || flow) || !allow(incident)) {
      return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(204).message("NavMaster: budget TomTom")
          .body("".toResponseBody(null)).build()
    }
    val res = chain.proceed(req)
    if (res.code == 429) {
      pauseTillTomorrow()
      return res
    }
    if (res.code == 403 || res.code == 401) {
      pauseOneHour()
      return res
    }
    // traffic changes every few minutes: the map need not ask the same tile again sooner
    return if (flow && res.isSuccessful) res.newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=300").build() else res
  }

  /**
   * The same guard on MapLibre's own HTTP client (the traffic colours on the map are asked by the
   * map engine, not by the app). By name, so a change of the map library cannot break the build.
   */
  fun installOnMapLibre(context: Context) {
    val client = OkHttpClient.Builder().addInterceptor(this).build()
    try {
      // the map library must be started before its HTTP client can be replaced
      runCatching {
        Class.forName("org.maplibre.android.MapLibre").methods
            .firstOrNull { it.name == "getInstance" && it.parameterTypes.size == 1 }?.invoke(null, context)
      }.onFailure { Log.w(TAG, "map start: ${it.cause ?: it}") }
      val cls = Class.forName("org.maplibre.android.module.http.HttpRequestUtil")
      val m = cls.methods.firstOrNull { it.name == "setOkHttpClient" && it.parameterTypes.size == 1 &&
          it.parameterTypes[0].isAssignableFrom(OkHttpClient::class.java) }
      if (m != null) {
        m.invoke(null, client)
        Log.i(TAG, "TomTom guard on the map client")
      } else Log.w(TAG, "map client: setOkHttpClient not found")
    } catch (e: Throwable) {
      Log.w(TAG, "map client: ${e.cause ?: e}")
    }
  }
}
