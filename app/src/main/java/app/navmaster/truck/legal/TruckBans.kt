package app.navmaster.truck.legal

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.TextStyle
import java.util.Locale

/**
 * National weekend, holiday and night driving bans for lorries, checked against the time the
 * vehicle is expected in each country of the route. Rules as published for 2026 (they change: the
 * app shows them as a warning to verify, never as certainty).
 */
object TruckBans {
  enum class Day { MON, TUE, WED, THU, FRI, SAT, SUN, HOLIDAY, EVE }

  data class Window(
      val days: Set<Day>,
      val from: LocalTime,
      val to: LocalTime,
      /** Only in this part of the year (inclusive). */
      val season: Pair<MonthDay, MonthDay>? = null,
  )

  data class Rule(
      val iso: String,
      val country: String,
      /** Applies above this maximum mass (tonnes). */
      val overT: Double,
      val windows: List<Window>,
      val what: String,
      val roads: String,
  )

  private fun t(s: String) = LocalTime.parse(s)

  private fun md(m: Int, d: Int) = MonthDay.of(m, d)

  private val SUNHOL = setOf(Day.SUN, Day.HOLIDAY)
  private val EVERY = setOf(Day.MON, Day.TUE, Day.WED, Day.THU, Day.FRI, Day.SAT, Day.SUN, Day.HOLIDAY)
  private val END = t("23:59:59")

  val rules: List<Rule> =
      listOf(
          Rule("AT", "Austria", 7.5, listOf(Window(SUNHOL, t("00:00"), t("22:00")), Window(setOf(Day.EVE), t("15:00"), END)),
              "domeniche e festivi 00–22, vigilie di festivi dalle 15", "tutte le strade"),
          Rule("AT", "Austria", 7.5, listOf(Window(EVERY, t("22:00"), END), Window(EVERY, t("00:00"), t("05:00"))),
              "divieto notturno 22–05 (esclusi mezzi a basso rumore «L»)", "tutte le strade"),
          Rule("DE", "Germania", 7.5, listOf(Window(SUNHOL, t("00:00"), t("22:00")),
              Window(setOf(Day.SAT), t("07:00"), t("20:00"), md(7, 1) to md(8, 31))),
              "domeniche e festivi 00–22; sabati di luglio e agosto 07–20", "autostrade e superstrade (estate), tutte (domeniche)"),
          Rule("CH", "Svizzera", 3.5, listOf(Window(SUNHOL, t("00:00"), END), Window(EVERY, t("22:00"), END), Window(EVERY, t("00:00"), t("05:00"))),
              "domeniche e festivi tutto il giorno; ogni notte 22–05", "tutte le strade"),
          Rule("LI", "Liechtenstein", 3.5, listOf(Window(SUNHOL, t("00:00"), END), Window(EVERY, t("22:00"), END), Window(EVERY, t("00:00"), t("05:00"))),
              "domeniche e festivi; ogni notte 22–05", "tutte le strade"),
          Rule("FR", "Francia", 7.5, listOf(Window(setOf(Day.EVE), t("22:00"), END), Window(SUNHOL, t("00:00"), t("22:00")),
              Window(setOf(Day.SAT), t("07:00"), t("19:00"), md(7, 1) to md(8, 31))),
              "dalle 22 della vigilia alle 22 di domeniche e festivi; sabati estivi 07–19", "tutte le strade"),
          Rule("IT", "Italia", 7.5, listOf(Window(SUNHOL, t("09:00"), t("22:00")),
              Window(SUNHOL, t("07:00"), t("22:00"), md(6, 15) to md(9, 15)),
              Window(setOf(Day.SAT), t("08:00"), t("16:00"), md(7, 1) to md(8, 31))),
              "domeniche e festivi 09–22 (in estate 07–22); alcuni sabati estivi", "strade extraurbane"),
          Rule("PL", "Polonia", 12.0, listOf(Window(setOf(Day.EVE), t("18:00"), t("22:00")), Window(SUNHOL, t("08:00"), t("22:00")),
              Window(setOf(Day.FRI), t("18:00"), t("22:00"), md(6, 26) to md(9, 1)),
              Window(setOf(Day.SAT), t("08:00"), t("14:00"), md(6, 26) to md(9, 1))),
              "vigilie 18–22, domeniche e festivi 08–22; in estate anche venerdì sera e sabato mattina", "tutte le strade"),
          Rule("CZ", "Repubblica Ceca", 7.5, listOf(Window(SUNHOL, t("13:00"), t("22:00")),
              Window(setOf(Day.FRI), t("17:00"), t("21:00"), md(7, 1) to md(8, 31)),
              Window(setOf(Day.SAT), t("07:00"), t("13:00"), md(7, 1) to md(8, 31))),
              "domeniche e festivi 13–22; d'estate venerdì 17–21 e sabato 07–13", "autostrade e strade di I classe"),
          Rule("SK", "Slovacchia", 7.5, listOf(Window(SUNHOL, t("00:00"), t("22:00"))),
              "domeniche e festivi 00–22", "strade di I classe"),
          Rule("HU", "Ungheria", 7.5, listOf(Window(setOf(Day.EVE), t("22:00"), END), Window(SUNHOL, t("00:00"), t("22:00")),
              Window(setOf(Day.SAT), t("15:00"), END, md(7, 1) to md(8, 31))),
              "dalle 22 della vigilia alle 22 di domeniche e festivi; sabati estivi dalle 15", "tutte le strade"),
          Rule("SI", "Slovenia", 7.5, listOf(Window(SUNHOL, t("08:00"), t("21:00")),
              Window(setOf(Day.SAT), t("08:00"), t("13:00"), md(6, 27) to md(9, 6))),
              "domeniche e festivi 08–21; sabati estivi 08–13", "strade principali"),
          Rule("HR", "Croazia", 7.5, listOf(Window(setOf(Day.SAT), t("04:00"), t("14:00"), md(6, 15) to md(9, 15)),
              Window(setOf(Day.SUN), t("12:00"), t("23:00"), md(6, 15) to md(9, 15))),
              "dal 15 giugno al 15 settembre: sabato 04–14, domenica 12–23", "strade statali principali"),
          Rule("LU", "Lussemburgo", 7.5, listOf(Window(setOf(Day.SAT), t("21:30"), END), Window(SUNHOL, t("00:00"), t("21:45"))),
              "sabato dalle 21:30 a domenica 21:45 (transito verso la Francia)", "direttrici di transito"),
          Rule("BG", "Bulgaria", 12.0, listOf(Window(setOf(Day.FRI), t("17:00"), t("20:00"), md(6, 1) to md(9, 15)),
              Window(setOf(Day.SUN), t("14:00"), t("20:00"), md(6, 1) to md(9, 15))),
              "dal 1 giugno al 15 settembre: venerdì 17–20 e domenica 14–20", "strade principali"),
      )

  /** Warning about Romania, whose restrictions concern single roads (DN1 / E60) and Bucharest. */
  const val ROMANIA_NOTE =
      "Romania: restrizioni per mezzi oltre 7,5 t su DN1 (E60) Ploiești–Brașov nei fine settimana e nelle zone di Bucarest (permesso per il centro)."

  // ------------------------------------------------------------------ calendar

  fun easter(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = ((h + l - 7 * m + 114) % 31) + 1
    return LocalDate.of(year, month, day)
  }

  fun orthodoxEaster(year: Int): LocalDate {
    val a = year % 4
    val b = year % 7
    val c = year % 19
    val d = (19 * c + 15) % 30
    val e = (2 * a + 4 * b - d + 34) % 7
    val month = (d + e + 114) / 31
    val day = ((d + e + 114) % 31) + 1
    return LocalDate.of(year, month, day).plusDays(13) // Julian -> Gregorian
  }

  /** National public holidays (the ones the driving bans refer to). */
  fun holidays(iso: String, year: Int): Set<LocalDate> {
    val e = if (iso in setOf("BG", "RO", "GR", "RS", "MK", "ME")) orthodoxEaster(year) else easter(year)
    fun d(m: Int, day: Int) = LocalDate.of(year, m, day)
    val goodFri = e.minusDays(2)
    val easterMon = e.plusDays(1)
    val ascension = e.plusDays(39)
    val whitMon = e.plusDays(50)
    val corpus = e.plusDays(60)
    return when (iso) {
      "AT" -> setOf(d(1, 1), d(1, 6), easterMon, d(5, 1), ascension, whitMon, corpus, d(8, 15), d(10, 26), d(11, 1), d(12, 8), d(12, 25), d(12, 26))
      "DE" -> setOf(d(1, 1), goodFri, easterMon, d(5, 1), ascension, whitMon, d(10, 3), d(12, 25), d(12, 26))
      "CH", "LI" -> setOf(d(1, 1), goodFri, easterMon, ascension, whitMon, d(8, 1), d(12, 25), d(12, 26))
      "FR" -> setOf(d(1, 1), easterMon, d(5, 1), d(5, 8), ascension, whitMon, d(7, 14), d(8, 15), d(11, 1), d(11, 11), d(12, 25))
      "IT" -> setOf(d(1, 1), d(1, 6), easterMon, d(4, 25), d(5, 1), d(6, 2), d(8, 15), d(11, 1), d(12, 8), d(12, 25), d(12, 26))
      "PL" -> setOf(d(1, 1), d(1, 6), e, easterMon, d(5, 1), d(5, 3), e.plusDays(49), corpus, d(8, 15), d(11, 1), d(11, 11), d(12, 24), d(12, 25), d(12, 26))
      "CZ" -> setOf(d(1, 1), goodFri, easterMon, d(5, 1), d(5, 8), d(7, 5), d(7, 6), d(9, 28), d(10, 28), d(11, 17), d(12, 24), d(12, 25), d(12, 26))
      "SK" -> setOf(d(1, 1), d(1, 6), goodFri, easterMon, d(5, 1), d(5, 8), d(7, 5), d(8, 29), d(11, 1), d(11, 17), d(12, 24), d(12, 25), d(12, 26))
      "HU" -> setOf(d(1, 1), d(3, 15), goodFri, easterMon, d(5, 1), whitMon, d(8, 20), d(10, 23), d(11, 1), d(12, 25), d(12, 26))
      "SI" -> setOf(d(1, 1), d(1, 2), d(2, 8), easterMon, d(4, 27), d(5, 1), d(5, 2), d(6, 25), d(8, 15), d(10, 31), d(11, 1), d(12, 25), d(12, 26))
      "HR" -> setOf(d(1, 1), d(1, 6), easterMon, d(5, 1), d(5, 30), corpus, d(6, 22), d(8, 5), d(8, 15), d(11, 1), d(11, 18), d(12, 25), d(12, 26))
      "LU" -> setOf(d(1, 1), easterMon, d(5, 1), d(5, 9), ascension, whitMon, d(6, 23), d(8, 15), d(11, 1), d(12, 25), d(12, 26))
      "BG" -> setOf(d(1, 1), d(3, 3), goodFri, e, easterMon, d(5, 1), d(5, 6), d(5, 24), d(9, 6), d(9, 22), d(12, 24), d(12, 25), d(12, 26))
      "RO" -> setOf(d(1, 1), d(1, 2), d(1, 24), goodFri, e, easterMon, d(5, 1), d(6, 1), e.plusDays(49), e.plusDays(50), d(8, 15), d(11, 30), d(12, 1), d(12, 25), d(12, 26))
      else -> emptySet()
    }
  }

  private fun dayKinds(iso: String, t: LocalDateTime): Set<Day> {
    val out = mutableSetOf<Day>()
    out += when (t.dayOfWeek) {
      DayOfWeek.MONDAY -> Day.MON
      DayOfWeek.TUESDAY -> Day.TUE
      DayOfWeek.WEDNESDAY -> Day.WED
      DayOfWeek.THURSDAY -> Day.THU
      DayOfWeek.FRIDAY -> Day.FRI
      DayOfWeek.SATURDAY -> Day.SAT
      DayOfWeek.SUNDAY -> Day.SUN
    }
    val date = t.toLocalDate()
    val hol = holidays(iso, date.year) + holidays(iso, date.year + 1)
    if (date in hol) out += Day.HOLIDAY
    val next = date.plusDays(1)
    if (next in hol || next.dayOfWeek == DayOfWeek.SUNDAY) out += Day.EVE
    return out
  }

  private fun inSeason(w: Window, t: LocalDateTime): Boolean {
    val s = w.season ?: return true
    val m = MonthDay.from(t)
    return !m.isBefore(s.first) && !m.isAfter(s.second)
  }

  /** The ban active in [iso] at [t] for a vehicle of [maxT] tonnes, if any. */
  fun activeAt(iso: String, t: LocalDateTime, maxT: Double): Rule? {
    val kinds = dayKinds(iso, t)
    val time = t.toLocalTime()
    return rules.firstOrNull { r ->
      r.iso == iso && maxT > r.overT &&
          r.windows.any { w -> inSeason(w, t) && w.days.any { it in kinds } && !time.isBefore(w.from) && time.isBefore(w.to) }
    }
  }

  data class Hit(val rule: Rule, val from: LocalDateTime, val to: LocalDateTime, val alongM: Double)

  /**
   * Checks the time spent in each country: [countries] are the country codes in route order with
   * where the route enters them; [timeAt] gives the expected time at a position along the route.
   */
  fun check(countries: List<Pair<String, Double>>, lengthM: Double, maxT: Double, timeAt: (Double) -> LocalDateTime): List<Hit> {
    val hits = mutableListOf<Hit>()
    for ((i, c) in countries.withIndex()) {
      val (iso, start) = c
      val end = countries.getOrNull(i + 1)?.second ?: lengthM
      var along = start
      var hit: Hit? = null
      while (along <= end) {
        val t = timeAt(along)
        val r = activeAt(iso, t, maxT)
        if (r != null) {
          hit = if (hit == null) Hit(r, t, t, along) else hit.copy(to = t)
        } else if (hit != null) break
        along += 5000.0
      }
      if (hit != null) hits += hit
    }
    return hits
  }

  fun dayName(t: LocalDateTime): String = t.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
}
