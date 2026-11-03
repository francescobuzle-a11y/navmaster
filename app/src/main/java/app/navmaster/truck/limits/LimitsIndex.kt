package app.navmaster.truck.limits

import android.util.Log
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.routing.RouteAnalysis
import app.navmaster.truck.routing.RoutePointAt
import app.navmaster.truck.vehicle.AdrTunnel
import app.navmaster.truck.vehicle.VehicleProfile
import app.navmaster.truck.vehicle.VehicleType
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import uniffi.ferrostar.GeographicCoordinate

/** A limit found on the route, with where it is along the route and whether this vehicle passes. */
data class RouteLimit(
    val kind: String,
    val value: Double,
    val raw: String?,
    val name: String?,
    val conditional: String?,
    val alongM: Double,
    val lat: Double,
    val lon: Double,
    val blocking: Boolean,
    /** OpenStreetMap object ("w123" / "n456"). */
    val osm: String? = null,
    /**
     * How sure the data is (data/build_limits.py): "doppio" two sources agree (two tags, the sign,
     * an official list), "ufficiale" from an official list only, "osm" one OpenStreetMap tag,
     * "dubbio" not plausible or contradictory; null for packages made before the checks.
     */
    val ver: String? = null,
    /** Where it ends along the route: a section under average speed control (Tutor); else [alongM]. */
    val endM: Double = alongM,
) {
  /** A section under average speed control (Tutor, trajectcontrole, ETVM sections). */
  val isSection: Boolean
    get() = kind == "average_speed"

  /**
   * What a speed camera of an official list checks when it is not the speed ("semaforo rosso",
   * "passaggio a livello", "controllo della velocità media"), for the voice; else null.
   */
  val cameraWhat: String?
    get() = name?.takeIf { kind == "speed_camera" && (it.startsWith("semaforo") || it.startsWith("passaggio") || it.startsWith("controllo")) }
        ?.substringBefore(" (")

  /** What the driver is told about the data, or null when there is nothing to say. */
  val check: String?
    get() = when (ver) {
      "dubbio" -> "dato da verificare"
      "doppio" -> if (kind == "speed_camera") "confermato (elenco ufficiale o relazione)" else "confermato da due fonti"
      "ufficiale" -> "elenco ufficiale"
      CameraImport.VER -> "dal tuo elenco"
      else -> null
    }

  /** Short text for the banner: "Altezza max", "Divieto mezzi pesanti" ... */
  val label: String
    get() =
        when (kind) {
          "maxheight" -> "Altezza max"
          "maxwidth" -> "Larghezza max"
          "maxlength" -> "Lunghezza max"
          "maxweight" -> "Peso max"
          "maxaxleload" -> "Peso per asse max"
          "hgv" -> "Divieto mezzi pesanti"
          "bus" -> "Divieto autobus"
          "motorhome" -> "Divieto camper"
          "hazmat" -> "Divieto merci pericolose"
          "access" -> "Divieto di transito"
          "adr_tunnel" -> "Galleria ADR cat. ${raw ?: ""}"
          "speed_camera" -> "Autovelox"
          "average_speed" -> "Tutor"
          else -> "Divieto"
        }

  /** The value shown inside the round sign: "3,8m", "7,5t", or nothing for a ban. */
  val signValue: String?
    get() =
        when (kind) {
          "maxheight", "maxwidth", "maxlength" -> fmt(value) + "m"
          "maxweight", "maxaxleload" -> fmt(value) + "t"
          "adr_tunnel" -> raw
          "speed_camera", "average_speed" -> if (value > 0) value.toInt().toString() else null
          else -> null
        }

  private fun fmt(v: Double): String =
      if (v == floor(v)) v.toInt().toString() else String.format(java.util.Locale.ITALY, "%.1f", v)
}

/**
 * The limits on a route, from the engine's graph (the roads of the route, RouteAnalysis): the
 * engine already avoids what the vehicle cannot pass; this is what lets the driver see and hear
 * about every signed limit in advance, and what warns of a limit on a motorway (told, never a
 * block: the route stays on the motorway).
 */
class LimitsIndex(@Suppress("unused") private val regions: RegionManager) {

  /**
   * The limits signed on the roads of the route [a], in route order, for this vehicle: only what
   * the map has as a sign (maxheight, maxwidth, maxlength, maxweight, maxaxleload; the width of a
   * carriageway is not a limit and the engine leaves it out), the bans that concern this kind of
   * vehicle (dangerous goods, ADR tunnels, lorries, buses), and the restrictions at given hours
   * only when they are in force at the time the vehicle is expected there. Nothing without the roads.
   */
  fun scan(m: RouteMatcher, vehicle: VehicleProfile, weightT: Double, departure: LocalDateTime = LocalDateTime.now(),
           avgSpeedMs: Double = 16.0, a: RouteAnalysis? = null): List<RouteLimit> {
    if (a == null || a.edges.isEmpty() || m.route.size < 2) return emptyList()
    val out = mutableListOf<RouteLimit>()
    val seen = HashSet<String>()
    // when the vehicle passes a point: the route's own times when known
    fun at(alongM: Double): LocalDateTime =
        departure.plusSeconds((if (a.length > 0 && a.durationS > 0) a.durationS * alongM / a.length else alongM / avgSpeedMs).toLong())
    for (e in a.edges) {
      val osm = if (e.wayId > 0) "w${e.wayId}" else null
      val items = ArrayList<Pair<String, Double>>()
      for ((k, value) in e.limits) items += (SIGNED[k] ?: k) to value
      if (e.hazmat && vehicle.hazmat) items += "hazmat" to 0.0
      for ((kind, value) in items) {
        if (kind !in SIGNED.values && kind != "hazmat") continue
        // widths and heights no road vehicle fits: bollards and barriers of paths
        if ((kind == "maxwidth" || kind == "maxheight") && value < 1.6) continue
        // the same way signed with the same limit over several edges: said once, where it starts
        if (!seen.add("${e.wayId}:$kind:$value")) continue
        val p = a.pointAt(e.startM)
        // on a motorway a height, width or length limit is told as a warning to check on the spot, never a block
        val sizeOnMotorway = e.roadClass == "motorway" && (kind == "maxheight" || kind == "maxwidth" || kind == "maxlength")
        out += RouteLimit(kind, value, null, null, null, e.startM, p.lat, p.lng, blocks(kind, value, vehicle, weightT) && !sizeOnMotorway, osm, "osm")
      }
      // ADR tunnel category: only for a load with a tunnel code
      if (e.adrTunnel in 2..5 && vehicle.adr != AdrTunnel.NONE && seen.add("${e.wayId}:adr:${e.adrTunnel}")) {
        val p = a.pointAt(e.startM)
        out += RouteLimit("adr_tunnel", e.adrTunnel.toDouble(), "BCDE"[e.adrTunnel - 2].toString(), null, null, e.startM, p.lat, p.lng,
            blocks("adr_tunnel", e.adrTunnel.toDouble(), vehicle, weightT), osm, "osm")
      }
      // restrictions at given hours: only those understood exactly, that concern this vehicle and
      // are in force when it passes
      for (c in e.conditional) {
        val r = conditionalRestriction(c, vehicle) ?: continue
        val when0 = at(e.startM)
        if (conditionalWindow(r.condition, when0) != true) continue
        if (!seen.add("${e.wayId}:${r.kind}:${r.value}:${r.condition}")) continue
        val p = a.pointAt(e.startM)
        out += RouteLimit(r.kind, r.value, r.text, null, r.condition, e.startM, p.lat, p.lng, blocks(r.kind, r.value, vehicle, weightT), osm, "osm")
      }
    }
    // speed cameras, red-light cameras and average-speed sections (Tutor), from the graph
    var sectionStart: RoutePointAt? = null
    for (p in a.points) {
      when (p.type) {
        "speed_camera" -> out += RouteLimit("speed_camera", p.valueKmh.toDouble(), null, null, null, p.alongM, p.lat, p.lon, false, null, "osm")
        "traffic_signals_camera" -> out += RouteLimit("speed_camera", 0.0, null, "semaforo rosso", null, p.alongM, p.lat, p.lon, false, null, "osm")
        "average_speed_start" -> sectionStart = p
        "average_speed_end" -> sectionStart?.let { s ->
          if (p.alongM > s.alongM) out += RouteLimit("average_speed", (if (s.valueKmh > 0) s.valueKmh else p.valueKmh).toDouble(), null, null, null, s.alongM, s.lat, s.lon, false, null, "osm", p.alongM)
          sectionStart = null
        }
      }
    }
    Log.i("NavMasterLimits", "limits on the route: ${out.size} (${out.count { it.blocking }} blocking, ${out.count { it.kind == "speed_camera" || it.kind == "average_speed" }} cameras)")
    return out.sortedBy { it.alongM }
  }

  /** A restriction at given hours, understood: what it limits, its value, the hours ("Mo-Fr 07:00-19:00"). */
  private class TimedRestriction(val kind: String, val value: Double, val text: String, val condition: String)

  /**
   * "key:conditional=value @ (hours)" as a limit of this vehicle, or null when it does not concern
   * it or is not a plain prohibition / limit (a "destination only", a speed, a condition on the
   * weather or the load: not shown).
   */
  private fun conditionalRestriction(c: String, v: VehicleProfile): TimedRestriction? {
    val key = c.substringBefore('=').removeSuffix(":conditional")
    val raw = c.substringAfter('=', "")
    val valueText = raw.substringBefore('@').trim()
    val cond = raw.substringAfter('@', "").trim().removePrefix("(").removeSuffix(")").trim()
    if (cond.isEmpty() || valueText.isEmpty()) return null
    val number = valueText.replace(',', '.').takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull()
    return when (key) {
      "maxweight", "maxweight:hgv", "maxheight", "maxwidth", "maxlength", "maxaxleload" -> {
        if (number == null || number <= 0) return null
        if (key == "maxweight:hgv" && !v.isHgv) return null
        TimedRestriction(key.removeSuffix(":hgv"), number, valueText, cond)
      }
      "hgv", "goods" -> if (valueText == "no" && v.isHgv) TimedRestriction("hgv", 0.0, valueText, cond) else null
      "bus" -> if (valueText == "no" && v.isBus) TimedRestriction("bus", 0.0, valueText, cond) else null
      "access", "vehicle", "motor_vehicle" -> if (valueText == "no") TimedRestriction("access", 0.0, valueText, cond) else null
      "hazmat" -> if (valueText == "no" && v.hazmat) TimedRestriction("hazmat", 0.0, valueText, cond) else null
      else -> null
    }
  }

  /**
   * The direction signs of the route: the one of the road taken (destination tags, motorway
   * junctions, destination_sign relations) and those of the other roads leaving the junction.
   */
  fun signs(m: RouteMatcher, a: RouteAnalysis): List<app.navmaster.truck.routing.RouteSign> {
    val out = mutableListOf<app.navmaster.truck.routing.RouteSign>()
    for (e in a.edges) {
      val sign = e.sign ?: continue
      if (sign.isEmpty) continue
      val last = out.lastOrNull()
      if (last != null && e.startM - last.atM < 40) continue
      out += app.navmaster.truck.routing.RouteSign(e.startM, sign, emptyList())
    }
    // the signs of the roads not taken, at the junctions where the route goes on
    for (js in a.junctionShapes) {
      val others = js.branches.mapNotNull { br ->
        val s = br.sign ?: return@mapNotNull null
        val outH = js.outHeading ?: return@mapNotNull null
        val diff = ((br.heading - outH + 540.0) % 360.0) - 180.0
        app.navmaster.truck.routing.SideSign(s, if (diff < -8) -1 else if (diff > 8) 1 else 0, br.wayId)
      }
      if (others.isEmpty()) continue
      val near = out.indexOfFirst { abs(it.atM - js.atM) < 40 }
      if (near >= 0) out[near] = out[near].copy(others = out[near].others + others)
      else out += app.navmaster.truck.routing.RouteSign(js.atM, null, others)
    }
    return out.sortedBy { it.atM }
  }

  /** The signs over the lanes before a junction (destination:lanes and the like of the roads of the route). */
  fun laneSigns(m: RouteMatcher, a: RouteAnalysis): List<app.navmaster.truck.routing.LaneSigns> {
    val out = mutableListOf<app.navmaster.truck.routing.LaneSigns>()
    for (e in a.edges) {
      val ls = e.laneSigns ?: continue
      val dest = ls.destination.split('|'); val dref = ls.ref.split('|'); val col = ls.colour.split('|')
      val n = maxOf(dest.size, dref.size)
      if (n < 2) continue
      fun vals(l: List<String>, k: Int) = l.getOrNull(k)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
      val list = (0 until n).map { k -> app.navmaster.truck.routing.LaneDest(vals(dest, k), vals(dref, k), col.getOrNull(k)?.takeIf { it.isNotBlank() }) }
      if (list.all { it.isEmpty }) continue
      if (out.any { abs(it.atM - e.endM) < 20 }) continue
      out += app.navmaster.truck.routing.LaneSigns(e.endM, list)
    }
    return out
  }

  fun cameras(all: List<RouteLimit>): List<RouteLimit> = all.filter { it.kind == "speed_camera" }

  private fun blocks(kind: String, value: Double, v: VehicleProfile, weightT: Double): Boolean =
      when (kind) {
        "maxheight" -> v.heightM > value
        "maxwidth" -> v.widthM > value
        "maxlength" -> v.lengthM > value
        "maxweight" -> weightT > value
        "maxaxleload" -> v.axleLoadT > value
        "hgv" -> v.isHgv
        "bus" -> v.type == VehicleType.AUTOBUS
        "motorhome" -> v.type == VehicleType.CAMPER
        "hazmat" -> v.hazmat
        "access" -> true
        // a tunnel of category X is closed to loads with tunnel code X or stricter (B < C < D < E)
        "adr_tunnel" -> v.adr != AdrTunnel.NONE && adrRank(v.adr) <= value.toInt()
        else -> false
      }

  private fun adrRank(a: AdrTunnel): Int =
      when (a) {
        AdrTunnel.B -> 2
        AdrTunnel.C -> 3
        AdrTunnel.D -> 4
        AdrTunnel.E -> 5
        AdrTunnel.NONE -> 99
      }

  companion object {
    /**
     * A limit worth showing to this vehicle: one it does not pass (a real ban for it), or one it
     * passes only just (height within 30 cm, width 20 cm, length 1 m, weight 2 t, axle 1 t). A 44 t
     * limit for a 27 t lorry is not a ban: it is not shown.
     */
    fun relevant(l: RouteLimit, v: VehicleProfile, weightT: Double): Boolean {
      if (l.blocking) return true
      return when (l.kind) {
        "maxheight" -> l.value - v.heightM < 0.3
        "maxwidth" -> l.value - v.widthM < 0.2
        "maxlength" -> l.value - v.lengthM < 1.0
        "maxweight" -> l.value - weightT < 2.0
        "maxaxleload" -> l.value - v.axleLoadT < 1.0
        else -> false
      }
    }

    /** Countries where traffic keeps left. */
    private val LHT = setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")
    private val DAYS = mapOf("Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "We" to DayOfWeek.WEDNESDAY,
        "Th" to DayOfWeek.THURSDAY, "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY)

    /**
     * Whether a restriction written as "hgv=no @ (Mo-Fr 07:00-19:00)" applies at [at]. Only the
     * common day/hour forms are understood; anything else counts as always valid (safe side).
     */
    /** The engine's limit names → the names of the signs. */
    private val SIGNED = mapOf("height" to "maxheight", "width" to "maxwidth", "length" to "maxlength", "weight" to "maxweight", "axleload" to "maxaxleload",
        "maxheight" to "maxheight", "maxwidth" to "maxwidth", "maxlength" to "maxlength", "maxweight" to "maxweight", "maxaxleload" to "maxaxleload")

    /**
     * Whether a time condition of OpenStreetMap ("Mo-Fr 07:00-19:00; Sa 07:00-13:00") holds at
     * [at]: true / false, or null when it is not a plain list of days and hours (months, holidays,
     * sunrise, the weather, the load …): such a restriction is not shown, it cannot be told for sure.
     */
    fun conditionalWindow(cond: String, at: LocalDateTime): Boolean? {
      val spec = cond.trim().removePrefix("(").removeSuffix(")").trim()
      if (spec.isEmpty()) return null
      val dayRx = "(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)"
      val partRx = Regex("^($dayRx(?:\\s*[-,]\\s*$dayRx)*)?\\s*((?:\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2})(?:\\s*,\\s*\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2})*)?$")
      var any = false
      for (part in spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val m = partRx.matchEntire(part) ?: return null
        val daysText = m.groupValues[1]; val hoursText = m.groupValues[2]
        if (daysText.isEmpty() && hoursText.isEmpty()) return null
        if (inWindow(daysText, hoursText, at)) any = true
      }
      return any
    }

    /**
     * The time [at] is in the window "days hours" of OSM's opening hours. A window past midnight
     * ("Mo-Fr 22:00-06:00") goes on into the next day: Saturday 03:00 is in it, Monday 03:00 not.
     * PH (public holiday) is only taken for the holidays every country keeps (1 January, 25
     * December): a ban is shown only when it surely applies.
     */
    private fun inWindow(daysText: String, hoursText: String, at: LocalDateTime): Boolean {
      val days = if (daysText.isEmpty()) null else parseDays(daysText)
      val ph = Regex("\\bPH\\b").containsMatchIn(daysText)
      fun dayOk(d: java.time.LocalDate) = days == null || d.dayOfWeek in days || (ph && sureHoliday(d))
      val today = at.toLocalDate()
      if (hoursText.isEmpty()) return dayOk(today)
      val minute = at.hour * 60 + at.minute
      for (h in Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").findAll(hoursText)) {
        val from = h.groupValues[1].toInt() * 60 + h.groupValues[2].toInt()
        val to = h.groupValues[3].toInt() * 60 + h.groupValues[4].toInt()
        if (from <= to) { if (dayOk(today) && minute in from until to) return true }
        else if ((dayOk(today) && minute >= from) || (dayOk(today.minusDays(1)) && minute < to)) return true
      }
      return false
    }

    private fun sureHoliday(d: java.time.LocalDate) = (d.monthValue == 1 && d.dayOfMonth == 1) || (d.monthValue == 12 && d.dayOfMonth == 25)

    fun conditionalActive(cond: String, at: LocalDateTime): Boolean {
      val m = Regex("@\\s*\\(?([^)]*)\\)?").find(cond) ?: return true
      val spec = m.groupValues[1].trim()
      if (spec.isEmpty()) return true
      for (part in spec.split(';').map { it.trim() }) {
        val dm = Regex("^((?:Mo|Tu|We|Th|Fr|Sa|Su|PH)(?:\\s*[-,]\\s*(?:Mo|Tu|We|Th|Fr|Sa|Su|PH))*)\\s*").find(part)
        val daysText = dm?.groupValues?.get(1) ?: ""
        val rest = if (dm != null) part.substring(dm.range.last + 1).trim() else part
        if (rest.isNotEmpty() && !Regex("\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}").containsMatchIn(rest)) return true
        if (inWindow(daysText, rest, at)) return true
      }
      return false
    }

    private fun parseDays(s: String): Set<DayOfWeek> {
      val out = mutableSetOf<DayOfWeek>()
      for (piece in s.split(',')) {
        val r = piece.split('-').map { it.trim() }
        val a = DAYS[r[0]] ?: continue
        val b = if (r.size > 1) DAYS[r[1]] ?: a else a
        var d = a
        while (true) {
          out += d
          if (d == b) break
          d = d.plus(1)
        }
      }
      return out
    }
  }
}
