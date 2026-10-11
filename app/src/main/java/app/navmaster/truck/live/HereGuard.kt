package app.navmaster.truck.live

import android.content.Context
import android.util.Log
import app.navmaster.truck.BuildConfig
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The fleet: one TomTom account and one HERE account for all the tablets (their terms forbid more
 * accounts to get more free requests, and the keys of one account share its allowance). Every
 * tablet uses only its part of each free monthly allowance: 90% of it divided by the vehicles
 * (FLEET_SIZE, a repository variable, 20 when not set), so the whole fleet never pays.
 */
object Fleet {
  val size: Int
    get() = BuildConfig.FLEET_SIZE.coerceAtLeast(1)

  /** This tablet's part of a free monthly allowance. */
  fun share(monthFree: Int): Int = (monthFree * 0.9 / size).toInt().coerceAtLeast(1)

  /**
   * What may be used today of what is left this month: the rest spread over the days left, half as
   * much again on a long day (what a day does not use stays for the next ones).
   */
  fun today(monthLeft: Int): Int {
    if (monthLeft <= 0) return 0
    val d = LocalDate.now()
    val daysLeft = d.lengthOfMonth() - d.dayOfMonth + 1
    return minOf(monthLeft, Math.ceil(1.5 * monthLeft / daysLeft).toInt().coerceAtLeast(1))
  }

  fun tomorrowMs(): Long =
      LocalDateTime.now().toLocalDate().plusDays(1).atTime(0, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** HERE requests counted today and this month on this tablet (saved, so a restart does not reset them). */
@Serializable
data class HereUsage(
    val day: String = "",
    val month: String = "",
    val dayCalls: Int = 0,
    val monthCalls: Int = 0,
    /** Month's calls when today began (today's budget is reckoned from what was left then). */
    val monthAtDayStart: Int = 0,
    val pausedUntil: Long = 0,
)

/**
 * Keeps HERE (the incidents of HERE Traffic v7) inside this tablet's part of the free allowance
 * of the Base plan (HERE_MONTH_FREE, 5,000 traffic requests a month): HERE bills what goes over,
 * so a request over the limit is never sent (empty answer from here). HERE is the second source:
 * it is asked only when TomTom has used up its part (see NavViewModel.refreshLive).
 * A "429" pauses HERE until tomorrow, a refused key for an hour.
 */
object HereGuard : Interceptor {
  private const val TAG = "NavMasterLive"
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private var file: File? = null
  @Volatile private var usage = HereUsage()

  val monthLimit: Int
    get() = Fleet.share(BuildConfig.HERE_MONTH_FREE)

  fun init(context: Context) {
    val f = File(context.filesDir, "here_usage.json")
    file = f
    usage = runCatching { json.decodeFromString(HereUsage.serializer(), f.readText()) }.getOrDefault(HereUsage())
  }

  @Synchronized
  private fun current(): HereUsage {
    val today = LocalDate.now().toString()
    val month = today.take(7)
    var u = usage
    if (u.month != month) u = u.copy(month = month, monthCalls = 0, monthAtDayStart = 0)
    if (u.day != today) u = u.copy(day = today, dayCalls = 0, monthAtDayStart = u.monthCalls)
    return u
  }

  @Synchronized
  private fun save(u: HereUsage) {
    usage = u
    runCatching { file?.writeText(json.encodeToString(HereUsage.serializer(), u)) }
  }

  private fun dayLimit(u: HereUsage): Int = Fleet.today(monthLimit - u.monthAtDayStart)

  /** May [n] more requests be sent today? */
  @Synchronized
  fun canAsk(n: Int = 1): Boolean {
    val u = current()
    if (System.currentTimeMillis() < u.pausedUntil) return false
    return u.monthCalls + n <= monthLimit && u.dayCalls + n <= dayLimit(u)
  }

  @Synchronized
  private fun take(): Boolean {
    if (!canAsk()) return false
    val u = current()
    save(u.copy(dayCalls = u.dayCalls + 1, monthCalls = u.monthCalls + 1))
    return true
  }

  fun summary(): String {
    val u = current()
    val paused = System.currentTimeMillis() < u.pausedUntil
    return "HERE oggi ${u.dayCalls} di ${dayLimit(u)} · mese ${u.monthCalls} di $monthLimit" + if (paused) " · in pausa" else ""
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    val req = chain.request()
    if (!req.url.host.endsWith("hereapi.com")) return chain.proceed(req)
    if (!take()) {
      return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(204).message("NavMaster: budget HERE")
          .body("".toResponseBody(null)).build()
    }
    val res = chain.proceed(req)
    when (res.code) {
      429 -> {
        save(current().copy(pausedUntil = Fleet.tomorrowMs()))
        Log.w(TAG, "HERE: allowance reached, paused until tomorrow")
      }
      401, 403 -> {
        save(current().copy(pausedUntil = System.currentTimeMillis() + 3_600_000L))
        Log.w(TAG, "HERE: key refused, paused for an hour")
      }
    }
    return res
  }
}
