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
 * Every driver has his own tablet and his own free TomTom and HERE accounts (the keys typed in
 * Impostazioni › Chiavi dei servizi): each tablet uses the whole free allowance of its accounts,
 * less a tenth kept as a margin, so that no account ever goes over and pays.
 */
object Quota {
  /** What this tablet may use of a free allowance of its own account: 90% of it. */
  fun usable(free: Int): Int = (free * 0.9).toInt().coerceAtLeast(1)

  /**
   * HERE's free plan without a credit card («Limited») gives [dayCap] requests a day for all its
   * services together: may [n] more be sent, [search] or traffic? The searches leave the last
   * [reserve] of the day to the traffic (asked only when TomTom has finished, three an hour).
   */
  fun hereDayFits(dayCalls: Int, daySearch: Int, n: Int, search: Boolean, dayCap: Int, reserve: Int = 100): Boolean {
    val used = dayCalls + daySearch + n
    return if (search) used <= dayCap - minOf(reserve, dayCap / 4) else used <= dayCap
  }

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
    /** HERE Geocoding & Search (the companies and places typed in the search): its own allowance. */
    val daySearch: Int = 0,
    val monthSearch: Int = 0,
    val monthSearchAtDayStart: Int = 0,
)

/**
 * Keeps HERE inside the free allowances of this tablet's own account: on the «Base» plan HERE bills
 * what goes over, on the «Limited» plan (no credit card) it refuses it until the next day, so a
 * request over the limit is never sent (empty answer from here). Three limits:
 *  - HERE_DAY_FREE, 1,000 requests a day for all the services together (the «Limited» plan),
 *    90% of it used, the searches leaving the last hundred to the traffic;
 *  - HERE Traffic v7 (the incidents): HERE_MONTH_FREE, 5,000 requests a month. The second source:
 *    asked only when TomTom has used up its part (see NavViewModel.refreshLive).
 *  - HERE Geocoding & Search (the companies and places of the free search, search.hereapi.com):
 *    HERE_SEARCH_MONTH_FREE, 30,000 a month, counted apart (Discover/Autosuggest/Geocode share
 *    that one allowance).
 * A "429" pauses HERE until tomorrow, a refused key for an hour.
 */
object HereGuard : Interceptor {
  private const val TAG = "NavMasterLive"
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private var file: File? = null
  @Volatile private var usage = HereUsage()

  val monthLimit: Int
    get() = Quota.usable(BuildConfig.HERE_MONTH_FREE)

  /** This tablet's searches a month. */
  val searchMonthLimit: Int
    get() = Quota.usable(BuildConfig.HERE_SEARCH_MONTH_FREE)

  /** All the requests of a day together (the «Limited» plan). */
  val dayCap: Int
    get() = Quota.usable(BuildConfig.HERE_DAY_FREE)

  /** The search services of HERE (discover., autosuggest., geocode., browse., lookup.search.hereapi.com). */
  fun isSearch(host: String): Boolean = host.endsWith(".search.hereapi.com") || host == "search.hereapi.com"

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
    if (u.month != month) u = u.copy(month = month, monthCalls = 0, monthAtDayStart = 0, monthSearch = 0, monthSearchAtDayStart = 0)
    if (u.day != today) u = u.copy(day = today, dayCalls = 0, monthAtDayStart = u.monthCalls, daySearch = 0, monthSearchAtDayStart = u.monthSearch)
    return u
  }

  @Synchronized
  private fun save(u: HereUsage) {
    usage = u
    runCatching { file?.writeText(json.encodeToString(HereUsage.serializer(), u)) }
  }

  private fun dayLimit(u: HereUsage): Int = Quota.today(monthLimit - u.monthAtDayStart)

  private fun searchDayLimit(u: HereUsage): Int = Quota.today(searchMonthLimit - u.monthSearchAtDayStart)

  /** May [n] more requests be sent today? */
  @Synchronized
  fun canAsk(n: Int = 1): Boolean {
    val u = current()
    if (System.currentTimeMillis() < u.pausedUntil) return false
    return u.monthCalls + n <= monthLimit && u.dayCalls + n <= dayLimit(u) &&
        Quota.hereDayFits(u.dayCalls, u.daySearch, n, search = false, dayCap = dayCap)
  }

  /** May [n] more searches be sent today? */
  @Synchronized
  fun canSearch(n: Int = 1): Boolean {
    val u = current()
    if (System.currentTimeMillis() < u.pausedUntil) return false
    return u.monthSearch + n <= searchMonthLimit && u.daySearch + n <= searchDayLimit(u) &&
        Quota.hereDayFits(u.dayCalls, u.daySearch, n, search = true, dayCap = dayCap)
  }

  @Synchronized
  private fun take(): Boolean {
    if (!canAsk()) return false
    val u = current()
    save(u.copy(dayCalls = u.dayCalls + 1, monthCalls = u.monthCalls + 1))
    return true
  }

  @Synchronized
  private fun takeSearch(): Boolean {
    if (!canSearch()) return false
    val u = current()
    save(u.copy(daySearch = u.daySearch + 1, monthSearch = u.monthSearch + 1))
    return true
  }

  fun summary(): String {
    val u = current()
    val paused = System.currentTimeMillis() < u.pausedUntil
    return "HERE oggi ${u.dayCalls + u.daySearch} di $dayCap richieste (traffico ${u.dayCalls}, ricerche ${u.daySearch}) · " +
        "mese: traffico ${u.monthCalls} di $monthLimit, ricerche ${u.monthSearch} di $searchMonthLimit" + if (paused) " · in pausa fino a domani" else ""
  }

  override fun intercept(chain: Interceptor.Chain): Response {
    val req = chain.request()
    if (!req.url.host.endsWith("hereapi.com")) return chain.proceed(req)
    if (!(if (isSearch(req.url.host)) takeSearch() else take())) {
      return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(204).message("NavMaster: budget HERE")
          .body("".toResponseBody(null)).build()
    }
    val res = chain.proceed(req)
    when (res.code) {
      429 -> {
        save(current().copy(pausedUntil = Quota.tomorrowMs()))
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
