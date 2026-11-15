package app.navmaster.truck.limits

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The kinds of alert drawn on the map (autovelox, Tutor, semafori, ZTL, pericoli…), as the driver
 * chooses them in Impostazioni › Mappa e vista. [cluster]: from far away they are grouped in a
 * bubble with their number; the others are shown only from close by ([minZoom]).
 */
enum class AlertGroup(
    val id: String,
    val label: String,
    val title: String,
    val color: Long,
    val priority: Int,
    val cluster: Boolean,
    val minZoom: Float,
) {
  FISSO("fisso", "Autovelox fissi", "Autovelox fisso", 0xFFD32F2F, 0, true, 6f),
  TUTOR("tutor", "Tutor (velocità media)", "Tutor", 0xFF1565C0, 1, true, 6f),
  SEMAFORO("semaforo", "Semafori con telecamera", "Semaforo rosso", 0xFFC62828, 2, true, 6f),
  MOBILE("mobile", "Autovelox mobili", "Autovelox mobile", 0xFFF9A825, 3, true, 6f),
  ZTL("ztl", "Varchi ZTL", "Varco ZTL", 0xFF6A1B9A, 4, false, 11f),
  LEZ("lez", "Zone ambientali", "Zona ambientale", 0xFF2E7D32, 5, false, 11f),
  CAMION("camion", "Controlli camion", "Controllo camion", 0xFF37474F, 6, false, 10f),
  CINTURE("cinture", "Controllo cinture e cellulare", "Controllo cinture e cellulare", 0xFF00838F, 7, false, 12f),
  DOSSO("dosso", "Dossi e strade dissestate", "Dosso", 0xFF8D6E63, 8, false, 12f),
  PERICOLO("pericolo", "Pericoli (curve, tornanti, animali…)", "Pericolo", 0xFFEF6C00, 9, false, 12f);

  companion object {
    val ids: Set<String> = entries.map { it.id }.toSet()

    fun byId(id: String?): AlertGroup? = entries.firstOrNull { it.id == id }
  }
}

/** Where an alert comes from (several when the same alert is in more lists). */
enum class AlertSource(val label: String) {
  PACK("Radarbot"),
  DATA("dati controllati (OpenStreetMap, elenchi ufficiali, censimento)"),
  IMPORTED("il tuo elenco"),
}

/** An alert on the map. */
data class AlertPoint(
    val lat: Double,
    val lon: Double,
    val group: AlertGroup,
    /** What it is in words ("Autovelox fisso", "Curva pericolosa", "Inizio Tutor"). */
    val title: String,
    /** The speed it checks (km/h), 0 when not known. */
    val speed: Int = 0,
    val road: String? = null,
    val country: String? = null,
    /** Radarbot's direction code (1–4): its meaning is not documented, kept but not shown. */
    val dirCode: String? = null,
    /** The direction the camera looks at (OpenStreetMap, degrees or N/NE/…), when known. */
    val facing: String? = null,
    /** How sure the checked data is (build_limits.py: doppio, ufficiale, osm, dubbio), null when only in other lists. */
    val ver: String? = null,
    /** The checked data's words about it (for a camera put aside by Italy's census, why). */
    val note: String? = null,
    val sources: Set<AlertSource> = emptySet(),
) {
  /** Shown on the map: a camera put aside by the checks only when the driver wants them, or when Radarbot has it too. */
  fun visible(unverified: Boolean): Boolean = ver != "dubbio" || unverified || AlertSource.PACK in sources

  /** The lines of the card opened by a touch on the icon. */
  fun details(): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    if (speed > 0) out += "Limite" to "$speed km/h"
    road?.let { out += "Strada" to it }
    // Radarbot's direction code (1–4) has no documented meaning and no angle: not shown
    facing?.let { f -> compass(f)?.let { out += "Inquadra" to it } }
    val check = when (ver) {
      "doppio" -> "confermato (elenco ufficiale o relazione)"
      "ufficiale" -> "elenco ufficiale"
      "osm" -> "OpenStreetMap"
      "dubbio" -> note?.takeIf { it.contains("censimento") } ?: "dato da verificare"
      else -> null
    }
    check?.let { out += "Verifica" to it }
    if (sources.isNotEmpty()) out += "Fonte" to sources.sortedBy { it.ordinal }.joinToString(" · ") { it.label }
    return out
  }

  private fun compass(f: String): String? {
    val names = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ovest", "ovest", "nord-ovest")
    val deg = f.trim().toDoubleOrNull()
        ?: mapOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)[f.trim().uppercase()]
        ?: return null
    // the camera looks towards the traffic it checks: that traffic drives the other way
    val towards = names[(((deg + 180.0) % 360.0 + 22.5) / 45.0).toInt() % 8]
    return "il traffico diretto a $towards"
  }
}

/** Reading the lists into [AlertPoint]s, merging them, and the GeoJSON the map draws. */
object Alerts {
  /** The alert of a row of the alert pack (data/allerte.py). */
  fun fromPack(lat: Double, lon: Double, grp: String?, type: String?, kind: String?, name: String?, speed: Double,
               road: String?, dir: String?, cc: String?): AlertPoint? {
    val g = AlertGroup.byId(grp) ?: groupOf(kind, name) ?: return null
    val title = when (type) {
      "F", "FT" -> "Autovelox fisso"
      "U" -> "Autovelox in galleria"
      "SC" -> "Tutor (velocità media)"
      "S" -> "Semaforo rosso"
      "C" -> "Autovelox mobile"
      "R", "RV" -> "Dosso"
      else -> name ?: g.title
    }
    return AlertPoint(lat, lon, g, title, speedOf(speed), road?.ifBlank { null }, cc?.ifBlank { null }, dir?.ifBlank { null },
        sources = setOf(AlertSource.PACK))
  }

  /**
   * The alert of a row of limiti.sqlite / autovelox-<country>.sqlite (the checked data) or of the
   * imported list: what it is from its kind and its words, null when it is not an alert.
   */
  fun fromLimit(lat: Double, lon: Double, kind: String?, name: String?, value: Double, raw: String?, ver: String?,
                source: AlertSource): AlertPoint? {
    val g = groupOf(kind, name) ?: return null
    val title = when {
      g == AlertGroup.SEMAFORO -> "Semaforo rosso"
      g == AlertGroup.TUTOR -> "Tutor (velocità media)"
      kind == "speed_camera" && name?.startsWith("passaggio") == true -> "Telecamera al passaggio a livello"
      g == AlertGroup.FISSO -> "Autovelox fisso"
      g == AlertGroup.PERICOLO || g == AlertGroup.CINTURE -> name?.takeIf { it.isNotBlank() && !it.contains("censimento") } ?: g.title
      else -> g.title
    }
    // a camera's name is what it checks or why the census puts it aside, not a road
    return AlertPoint(lat, lon, g, title, speedOf(value), facing = raw?.ifBlank { null }, ver = ver, note = name, sources = setOf(source))
  }

  /** The group of a limit kind ("speed_camera", "danger"…) with its words ("semaforo rosso"), null when it is not an alert. */
  fun groupOf(kind: String?, name: String?): AlertGroup? {
    val n = name?.lowercase() ?: ""
    return when (kind) {
      "speed_camera" -> when {
        n.startsWith("semaforo") -> AlertGroup.SEMAFORO
        n.startsWith("controllo della velocità media") || n.contains("tutor") -> AlertGroup.TUTOR
        n.contains("cintur") -> AlertGroup.CINTURE
        else -> AlertGroup.FISSO
      }
      "average_speed" -> AlertGroup.TUTOR
      "speed_camera_mobile" -> AlertGroup.MOBILE
      "ztl" -> AlertGroup.ZTL
      "access" -> if (n.contains("ztl") || n.contains("traffico limitato")) AlertGroup.ZTL else null
      "low_emission_zone" -> AlertGroup.LEZ
      "truck_control" -> AlertGroup.CAMION
      "speed_bump" -> AlertGroup.DOSSO
      "danger" -> when {
        n.contains("cintur") || n.contains("cellular") -> AlertGroup.CINTURE
        n.contains("dissest") -> AlertGroup.DOSSO
        else -> AlertGroup.PERICOLO
      }
      else -> null
    }
  }

  private fun speedOf(v: Double): Int = if (v >= 5 && v <= 150) v.toInt() else 0

  /** Two alerts closer than this are the same one when they are of the same group (the lists place them a few metres apart). */
  const val SAME_M = 40.0

  /** Cameras compare with cameras (a fixed camera in one list is a red-light or Tutor camera in another only by its words). */
  private fun matchClass(g: AlertGroup): Int = when (g) {
    AlertGroup.FISSO, AlertGroup.SEMAFORO, AlertGroup.TUTOR -> 0
    else -> 1 + g.ordinal
  }

  /**
   * The lists merged without doubles, the first list's alert kept and completed with what the
   * others know (speed, road, direction, the check, the sources). A red-light or Tutor camera
   * found as a plain fixed camera in a list keeps the more precise group.
   */
  fun merge(lists: List<List<AlertPoint>>, sameM: Double = SAME_M): List<AlertPoint> {
    val out = ArrayList<AlertPoint>()
    // a grid of about 100 m: an alert is compared with those of its cell and the 8 around it
    val grid = HashMap<Long, MutableList<Int>>()
    fun key(la: Double, lo: Double): Long = floor(la * 1000).toLong() * 1_000_000L + floor(lo * 1000).toLong()
    for (list in lists) {
      for (p in list) {
        val cy = floor(p.lat * 1000).toLong()
        val cx = floor(p.lon * 1000).toLong()
        var hit = -1
        var best = sameM
        for (dy in -1L..1L) for (dx in -1L..1L) {
          for (i in grid[(cy + dy) * 1_000_000L + (cx + dx)] ?: continue) {
            val o = out[i]
            if (matchClass(o.group) != matchClass(p.group)) continue
            val d = dist(o.lat, o.lon, p.lat, p.lon)
            if (d < best) {
              best = d
              hit = i
            }
          }
        }
        if (hit < 0) {
          out += p
          grid.getOrPut(key(p.lat, p.lon)) { ArrayList() } += out.size - 1
          continue
        }
        val o = out[hit]
        val group = if (o.group == AlertGroup.FISSO && p.group != AlertGroup.FISSO) p.group else o.group
        out[hit] = o.copy(
            group = group,
            title = if (group != o.group) p.title else o.title,
            speed = if (o.speed > 0) o.speed else p.speed,
            road = o.road ?: p.road,
            country = o.country ?: p.country,
            dirCode = o.dirCode ?: p.dirCode,
            facing = o.facing ?: p.facing,
            ver = o.ver ?: p.ver,
            note = o.note ?: p.note,
            sources = o.sources + p.sources,
        )
      }
    }
    return out
  }

  fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val k = cos(Math.toRadians((lat1 + lat2) / 2))
    return hypot((lon2 - lon1) * 111_195.0 * k, (lat2 - lat1) * 111_195.0)
  }

  /**
   * The alerts the map shows: the groups chosen, the checks respected (a camera put aside only when
   * the driver wants them), none of the places the drivers said are gone ([gone], within 30 m).
   */
  fun shown(all: List<AlertPoint>, groups: Set<String>, unverified: Boolean, gone: List<Pair<Double, Double>> = emptyList()): List<AlertPoint> =
      all.filter { p ->
        p.group.id in groups && p.visible(unverified) &&
            gone.none { (la, lo) -> abs(la - p.lat) < 0.0005 && abs(lo - p.lon) < 0.0007 && dist(la, lo, p.lat, p.lon) <= 30.0 }
      }

  /**
   * The GeoJSON of [points] for the map, written straight into [out] (thousands of points: no
   * big string in memory). Each feature has g (group id), p (priority, for which icon wins when
   * they crowd), v (the speed as text, "" when unknown) and i (its place in [points], for the card).
   */
  fun writeGeoJson(points: List<AlertPoint>, indices: List<Int>, out: Appendable) {
    out.append("""{"type":"FeatureCollection","features":[""")
    var first = true
    for (i in indices) {
      val p = points[i]
      if (!first) out.append(',')
      first = false
      // numbers written digit by digit: no temporary strings for tens of thousands of points
      out.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
      appendCoord(out, p.lon)
      out.append(',')
      appendCoord(out, p.lat)
      out.append("""]},"properties":{"g":"""").append(p.group.id).append("""","p":""")
      appendLong(out, p.group.priority.toLong())
      out.append(""","v":"""")
      if (p.speed > 0) appendLong(out, p.speed.toLong())
      out.append("""","i":""")
      appendLong(out, i.toLong())
      out.append("}}")
    }
    out.append("]}")
  }

  /** A coordinate with 6 decimals (10 cm), as text. */
  fun coord(v: Double): String = StringBuilder(12).also { appendCoord(it, v) }.toString()

  /** A coordinate with 6 decimals (10 cm), written by hand: tens of thousands per map (String.format is slow). */
  fun appendCoord(out: Appendable, v: Double) {
    val micro = Math.round(abs(v) * 1_000_000.0)
    if (v < 0 && micro != 0L) out.append('-')
    appendLong(out, micro / 1_000_000)
    out.append('.')
    val frac = micro % 1_000_000
    var d = 100_000L
    while (d > 0) {
      out.append('0' + ((frac / d) % 10).toInt())
      d /= 10
    }
  }

  private fun appendLong(out: Appendable, n: Long) {
    if (n >= 10) appendLong(out, n / 10)
    out.append('0' + (n % 10).toInt())
  }
}
