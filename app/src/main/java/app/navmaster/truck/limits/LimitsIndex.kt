package app.navmaster.truck.limits

import android.util.Log
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.routing.RouteAnalysis
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
          "adr_tunnel" -> "Galleria ADR cat. ${raw ?: ""}"
          "speed_camera" -> "Autovelox"
          "average_speed" -> "Tutor"
          else -> "Divieto a orario"
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
 * Reads limiti.sqlite of every installed country (built from OSM by data/build_limits.py) and finds
 * every limit lying on a route. The routing engine already avoids what the vehicle cannot pass;
 * this is what lets the driver see and hear about it in advance, and what catches data the engine
 * cannot use (ADR tunnel categories, restrictions valid only at some hours).
 */
class LimitsIndex(regions: RegionManager) {
  private val dbs = RegionDbs(regions) { it.limits }

  /**
   * [a] (when the graph could describe the route) gives the OSM ways the route drives on: a limit
   * mapped on a way counts only when the route is on that very way, so a road passing under a
   * bridge of the route, or running right next to it, never lends it its limits. A limit mapped on
   * a point (a barrier, a sign) counts only when the route goes through it (3 m).
   */
  fun scan(m: RouteMatcher, vehicle: VehicleProfile, weightT: Double, departure: LocalDateTime = LocalDateTime.now(),
           avgSpeedMs: Double = 16.0, a: RouteAnalysis? = null): List<RouteLimit> {
    if (m.route.size < 2) return emptyList()
    val started = System.currentTimeMillis()
    val found = HashMap<String, RouteLimit>()
    val ways = a?.wayIds ?: emptySet()
    var elsewhere = 0
    var otherSide = 0
    var implausible = 0
    val settings = app.navmaster.truck.AppGraph.settings.settings.value
    // the owner's own list of speed cameras (Impostazioni › Autovelox) comes first: where it and
    // OpenStreetMap have the same camera, the list's one is kept
    val imported = if (settings.speedCamerasImported) CameraImport.db() else null
    val rows: (android.database.sqlite.SQLiteDatabase, String) -> List<RouteLimit> = { db, inList ->
      val out = mutableListOf<RouteLimit>()
      val hasVer = hasCheck(db)
      db.rawQuery(
              "SELECT DISTINCT l.id, l.kind, l.value, l.raw, l.cond, l.name, l.pts, l.osm, " +
                  (if (hasVer) "l.ver" else "NULL") + " FROM cells c " +
                  "JOIN limits l ON l.id = c.lid WHERE c.cell IN ($inList)",
              null,
          )
          .use { c ->
            while (c.moveToNext()) {
              val pts = RouteMatcher.parsePts(c.getString(6))
              val kind = c.getString(1)
              val value = c.getDouble(2)
              // widths and heights no road vehicle fits: bollards and barriers of paths
              if ((kind == "maxwidth" || kind == "maxheight") && value < 1.6) continue
              val osm = c.getString(7) ?: ""
              val wayId = osm.takeIf { it.startsWith("w") }?.substring(1)?.toLongOrNull()
              val lo: Double
              val p: GeographicCoordinate
              val byWay = wayId != null && ways.isNotEmpty() && a != null &&
                  (wayId in ways || !a.partialWays || (m.span(pts, 9.0)?.let { sp -> (a.edgeAt(sp.first)?.wayId ?: 0L) > 0 } ?: true))
              if (byWay && wayId != null && a != null) {
                if (wayId !in ways) {
                  if (m.span(pts, 9.0) != null) elsewhere++
                  continue
                }
                lo = a.wayExtent(wayId)?.startM ?: continue
                p = a.pointAt(lo)
              } else {
                // a camera stands beside the road; a barrier or a sign on a point is on it
                lo = m.span(pts, if (kind == "speed_camera") 25.0 else if (pts.size <= 1) 3.0 else 9.0)?.first ?: continue
                p = pts.minByOrNull { pt -> m.nearest(pt)?.first ?: 1e9 } ?: continue
                // on a motorway or trunk road the other carriageway runs 10-25 m to the left (right
                // where traffic keeps left): a camera over there is not on this road
                if (kind == "speed_camera" && a != null) {
                  val e = a.edgeAt(lo)
                  if (e != null && (e.roadClass == "motorway" || e.roadClass == "trunk")) {
                    val right = sideOf(m, lo, p)
                    val away = if (e.country?.uppercase() in LHT) right else -right
                    if (away > 7.0) {
                      otherSide++
                      continue
                    }
                  }
                }
              }
              val cond = c.getString(4)
              val at = departure.plusSeconds((lo / avgSpeedMs).toLong())
              val ver = c.getString(8)
              // on a motorway a height under 4.6 m or a width under 3 m is a mistake of the data
              // (the street under the bridge): not a limit of this road
              val hereEdge = a?.edgeAt(lo)
              if (hereEdge?.roadClass == "motorway" && ((kind == "maxheight" && value < 4.6) || (kind == "maxwidth" && value < 3.0))) {
                implausible++
                continue
              }
              // a doubtful limit (not plausible, contradictory, not confirmed) is told, never a block
              out += RouteLimit(kind, value, c.getString(3), c.getString(5), cond, lo, p.lat, p.lng,
                  blocks(kind, value, vehicle, weightT) && (cond == null || conditionalActive(cond, at)) && ver != "dubbio", osm.ifBlank { null },
                  ver)
            }
          }
      out
    }
    val all = ArrayList<RouteLimit>()
    if (imported != null) {
      for (chunk in m.cells.chunked(400)) all += runCatching { rows(imported, chunk.joinToString(",")) }
          .onFailure { Log.w("NavMasterLimits", "imported cameras: $it") }.getOrDefault(emptyList())
    }
    all += dbs.perCells(m.cells, rows)
    all.forEach { l -> found.putIfAbsent("${l.kind}:${(l.alongM / 30).toLong()}", l) }
    val out = mutableListOf<RouteLimit>()
    var unverified = 0
    for (l in found.values.sortedBy { it.alongM }) {
      if (out.any { it.kind == l.kind && abs(it.alongM - l.alongM) < 60 }) continue
      if (!relevant(l, vehicle)) continue
      if (l.kind == "speed_camera") {
        // nobody confirmed it (a bare point on a motorway: often not a camera at all): left out unless asked
        if (l.ver == "dubbio" && !settings.speedCamerasUnverified) {
          unverified++
          continue
        }
        // the owner trusts his list only
        if (imported != null && settings.speedCamerasImportedOnly && l.ver != CameraImport.VER) continue
      }
      out += l
    }
    out += runCatching { sections(m, vehicle) }.getOrElse { Log.w("NavMasterLimits", "sections: $it"); emptyList() }
    out.sortBy { it.alongM }
    if (a != null) a.osmSigns = runCatching { signs(m, a) }.getOrElse { Log.w("NavMasterLimits", "signs: $it"); emptyList() }
    if (a != null) a.laneSigns = runCatching { laneSigns(m, a) }.getOrElse { Log.w("NavMasterLimits", "lane signs: $it"); emptyList() }
    Log.i("NavMasterLimits", "scan: ${m.route.size} pts, ${m.cells.size} cells, ${out.size} limits " +
        "(${out.count { it.blocking }} blocking) in ${System.currentTimeMillis() - started} ms, " +
        "$elsewhere on roads next to the route left out, $otherSide cameras on the other carriageway, $implausible implausible on motorways, $unverified unverified" +
        (if (ways.isEmpty()) " (no way ids: matched by distance)" else ""))
    for (l in out) Log.d("NavMasterLimits", "limit ${l.kind} ${l.value} @${l.alongM.toInt()} ${"%.5f".format(java.util.Locale.ROOT, l.lat)}," +
        "${"%.5f".format(java.util.Locale.ROOT, l.lon)} blocking=${l.blocking} ${l.name ?: ""}")
    return out
  }

  /**
   * The sections under average speed control the route drives through from their start to their
   * end, in their direction (table «sections», data/build_limits.py; not in older packages). The
   * limit is the heavy vehicles' one when mapped (maxspeed:hgv) and the vehicle is one.
   */
  private fun sections(m: RouteMatcher, vehicle: VehicleProfile): List<RouteLimit> {
    val heavy = vehicle.isHgv || vehicle.type == VehicleType.AUTOBUS
    val rows = dbs.perCells(m.cells) { db, inList ->
      val out = mutableListOf<RouteLimit>()
      try {
        db.rawQuery("SELECT DISTINCT s.id, s.osm, s.value, s.hgv, s.name, s.pts, s.ver FROM section_cells c " +
            "JOIN sections s ON s.id = c.sid WHERE c.cell IN ($inList)", null).use { c ->
          while (c.moveToNext()) {
            val pts = RouteMatcher.parsePts(c.getString(5))
            if (pts.size < 2) continue
            val (da, a) = m.nearest(pts.first()) ?: continue
            val (db2, b) = m.nearest(pts.last()) ?: continue
            // both ends on the route, in this order (the other carriageway has them the other way)
            if (da > 40 || db2 > 40 || b - a < 300) continue
            val hgv = if (c.isNull(3)) null else c.getDouble(3)
            val limit = if (heavy && hgv != null && hgv > 0) hgv else c.getDouble(2)
            out += RouteLimit("average_speed", limit, hgv?.toInt()?.toString(), c.getString(4), null, a,
                pts.first().lat, pts.first().lng, false, c.getString(1), c.getString(6), endM = b)
          }
        }
      } catch (e: android.database.sqlite.SQLiteException) {
        // a package made before the sections
      }
      out
    }
    return rows.distinctBy { (it.alongM / 50).toLong() }
  }

  /**
   * The direction signs at the junctions of the route (table «signs», data/build_signs.py): each
   * road with destination tags that starts on the route is either the road the route takes there
   * (its sign is the one to follow) or a road it leaves aside (its sign says where that one goes).
   */
  fun signs(m: RouteMatcher, a: RouteAnalysis): List<app.navmaster.truck.routing.RouteSign> {
    class Row(val wayId: Long, val along: Double, val brg: Double, val sign: app.navmaster.truck.routing.EdgeSign)
    val rows = dbs.perCells(m.cells) { db, inList ->
      val out = mutableListOf<Row>()
      try {
        db.rawQuery("SELECT DISTINCT s.id, s.osm, s.lat, s.lon, s.brg, s.dest, s.dref, s.colour, s.jref, s.jname FROM sign_cells c " +
            "JOIN signs s ON s.id = c.sid WHERE c.cell IN ($inList)", null).use { c ->
          while (c.moveToNext()) {
            val p = GeographicCoordinate(c.getDouble(2), c.getDouble(3))
            val (d, along) = m.nearest(p) ?: continue
            if (d > 15) continue
            fun list(i: Int) = c.getString(i)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val sign = app.navmaster.truck.routing.EdgeSign(list(8), list(6), list(5), list(9), c.getString(7))
            out += Row(c.getString(1)?.removePrefix("w")?.toLongOrNull() ?: -1L, along, c.getDouble(4), sign)
          }
        }
      } catch (e: Exception) {
        // packages built before the signs have no table: nothing to show
      }
      out
    }
    if (rows.isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<Row>>()
    for (r in rows.sortedBy { it.along }) {
      val g = groups.lastOrNull()
      if (g != null && r.along - g.first().along < 40) g += r else groups += mutableListOf(r)
    }
    val out = mutableListOf<app.navmaster.truck.routing.RouteSign>()
    for (g in groups) {
      val at = g.first().along
      val routeBrg = app.navmaster.truck.core.Geo.bearing(a.pointAt(at), a.pointAt((at + 25).coerceAtMost(a.length)))
      var taken: app.navmaster.truck.routing.EdgeSign? = null
      val others = mutableListOf<app.navmaster.truck.routing.SideSign>()
      for (r in g) {
        val ext = a.wayExtent(r.wayId)
        if (ext != null && kotlin.math.abs(ext.startM - r.along) < 40) {
          taken = taken ?: r.sign
          continue
        }
        if (r.wayId in a.wayIds) continue
        val diff = ((r.brg - routeBrg + 540.0) % 360.0) - 180.0
        others += app.navmaster.truck.routing.SideSign(r.sign, if (diff < -8) -1 else if (diff > 8) 1 else 0, r.wayId)
      }
      if (taken != null || others.isNotEmpty()) out += app.navmaster.truck.routing.RouteSign(at, taken, others)
    }
    Log.i("NavMasterLimits", "signs: ${rows.size} on the route, ${out.size} junctions: " +
        out.take(12).joinToString { s -> "${s.atM.toInt()}:" + (s.taken?.let { t -> (t.branches + t.towards).joinToString("/") } ?: "-") +
            " | " + s.others.joinToString(" ") { o -> "${o.side}:" + (o.sign.branches + o.sign.towards).joinToString("/") } })
    return out
  }

  /**
   * The signs over the lanes on the route (table «lane_signs», data/build_signs.py): the road that
   * carries them must be one the route drives, in the same direction, up to where its lanes split.
   */
  fun laneSigns(m: RouteMatcher, a: RouteAnalysis): List<app.navmaster.truck.routing.LaneSigns> {
    val rows = dbs.perCells(m.cells) { db, inList ->
      val out = mutableListOf<app.navmaster.truck.routing.LaneSigns>()
      try {
        db.rawQuery("SELECT DISTINCT s.id, s.osm, s.lat, s.lon, s.brg, s.dest, s.dref, s.colour FROM lane_sign_cells c " +
            "JOIN lane_signs s ON s.id = c.sid WHERE c.cell IN ($inList)", null).use { c ->
          while (c.moveToNext()) {
            val p = GeographicCoordinate(c.getDouble(2), c.getDouble(3))
            val (d, along) = m.nearest(p) ?: continue
            if (d > 15) continue
            val wayId = c.getString(1)?.removePrefix("w")?.toLongOrNull() ?: -1L
            if (wayId > 0 && a.wayIds.isNotEmpty() && !a.partialWays && wayId !in a.wayIds) continue
            val routeBrg = app.navmaster.truck.core.Geo.bearing(a.pointAt((along - 25).coerceAtLeast(0.0)), a.pointAt(along))
            val diff = abs(((c.getDouble(4) - routeBrg + 540.0) % 360.0) - 180.0)
            if (diff > 50) continue
            fun lanes(i: Int) = c.getString(i)?.split('|') ?: emptyList()
            val dest = lanes(5)
            val dref = lanes(6)
            val col = lanes(7)
            val n = maxOf(dest.size, dref.size)
            if (n < 2) continue
            fun vals(l: List<String>, k: Int) = l.getOrNull(k)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val list = (0 until n).map { k ->
              app.navmaster.truck.routing.LaneDest(vals(dest, k), vals(dref, k), col.getOrNull(k)?.takeIf { it.isNotBlank() })
            }
            if (list.all { it.isEmpty }) continue
            out += app.navmaster.truck.routing.LaneSigns(along, list)
          }
        }
      } catch (e: Exception) {
        // packages built before the lane signs have no table: nothing to show
      }
      out
    }
    val out = rows.sortedBy { it.atM }.fold(mutableListOf<app.navmaster.truck.routing.LaneSigns>()) { acc, r ->
      if (acc.none { abs(it.atM - r.atM) < 20 }) acc += r
      acc
    }
    if (out.isNotEmpty()) Log.i("NavMasterLimits", "lane signs: ${out.size} on the route: " +
        out.take(8).joinToString { s -> "${s.atM.toInt()}:" + s.lanes.joinToString("|") { l -> (l.refs + l.towns).joinToString(";") } })
    return out
  }

  /** Metres of [p] to the right of the route at [alongM] (negative: to the left). */
  private fun sideOf(m: RouteMatcher, alongM: Double, p: GeographicCoordinate): Double {
    val a = app.navmaster.truck.core.Geo.pointAt(m.route, m.cum, (alongM - 5).coerceAtLeast(0.0))
    val b = app.navmaster.truck.core.Geo.pointAt(m.route, m.cum, (alongM + 15).coerceAtMost(m.cum.last()))
    val k = kotlin.math.cos(Math.toRadians(a.lat))
    val rx = (b.lng - a.lng) * k
    val ry = b.lat - a.lat
    val len = kotlin.math.hypot(rx, ry)
    if (len < 1e-9) return 0.0
    val vx = (p.lng - a.lng) * k
    val vy = p.lat - a.lat
    // the cross product: positive = p to the left of the direction of travel
    val cross = (rx * vy - ry * vx) / len
    return -cross * 111_195.0
  }

  private fun relevant(l: RouteLimit, v: VehicleProfile): Boolean =
      when (l.kind) {
        "hgv" -> v.isHgv
        "bus" -> v.type == VehicleType.AUTOBUS
        "motorhome" -> v.type == VehicleType.CAMPER
        "hazmat" -> v.hazmat
        "adr_tunnel" -> v.adr != AdrTunnel.NONE
        else -> true
      }

  /** Speed cameras on the route, for the ones who want the warning (legal in the country). */
  // the packages made before the checks have no "ver" column
  private val checkColumn = java.util.WeakHashMap<android.database.sqlite.SQLiteDatabase, Boolean>()

  private fun hasCheck(db: android.database.sqlite.SQLiteDatabase): Boolean = synchronized(checkColumn) {
    checkColumn.getOrPut(db) {
      runCatching { db.rawQuery("PRAGMA table_info(limits)", null).use { c -> var f = false; while (c.moveToNext()) if (c.getString(1) == "ver") f = true; f } }
          .getOrDefault(false)
    }
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
    /** Countries where traffic keeps left. */
    private val LHT = setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")
    private val DAYS = mapOf("Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "We" to DayOfWeek.WEDNESDAY,
        "Th" to DayOfWeek.THURSDAY, "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY)

    /**
     * Whether a restriction written as "hgv=no @ (Mo-Fr 07:00-19:00)" applies at [at]. Only the
     * common day/hour forms are understood; anything else counts as always valid (safe side).
     */
    fun conditionalActive(cond: String, at: LocalDateTime): Boolean {
      val m = Regex("@\\s*\\(?([^)]*)\\)?").find(cond) ?: return true
      val spec = m.groupValues[1].trim()
      if (spec.isEmpty()) return true
      for (part in spec.split(';').map { it.trim() }) {
        var days: Set<DayOfWeek>? = null
        var rest = part
        val dm = Regex("^((?:Mo|Tu|We|Th|Fr|Sa|Su)(?:\\s*[-,]\\s*(?:Mo|Tu|We|Th|Fr|Sa|Su))*)\\s*").find(part)
        if (dm != null) {
          days = parseDays(dm.groupValues[1])
          rest = part.substring(dm.range.last + 1).trim()
        }
        if (days != null && at.dayOfWeek !in days) continue
        if (rest.isEmpty()) return true
        val hm = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").findAll(rest).toList()
        if (hm.isEmpty()) return true
        val minute = at.hour * 60 + at.minute
        for (h in hm) {
          val from = h.groupValues[1].toInt() * 60 + h.groupValues[2].toInt()
          val to = h.groupValues[3].toInt() * 60 + h.groupValues[4].toInt()
          if (if (from <= to) minute in from until to else minute >= from || minute < to) return true
        }
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
