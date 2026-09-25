package app.navmaster.truck.routing

import android.util.Log
import app.navmaster.truck.core.Geo
import app.navmaster.truck.core.LocalPlane
import app.navmaster.truck.core.XY
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.legal.TruckBans
import app.navmaster.truck.limits.RouteLimit
import app.navmaster.truck.ui.Fmt
import app.navmaster.truck.vehicle.VehicleProfile
import app.navmaster.truck.vehicle.VehicleType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.ferrostar.GeographicCoordinate

enum class CritKind(val label: String, val icon: String) {
  LIMIT("Limite del mezzo", "⚠"),
  NARROW("Strada stretta", "↔"),
  ROUGH("Fondo dissestato", "〰"),
  STEEP("Pendenza forte", "⛰"),
  CURVE("Curva stretta", "↪"),
  RAMP("Svincolo stretto", "⤴"),
  JUNCTION("Svolta stretta", "↱"),
  FORD("Guado", "🌊"),
  BAN("Divieto di circolazione", "⛔"),
}

enum class Severity { INFO, WARN, CRITICAL }

/** One difficulty on the route, for the list before departure and the questions while driving. */
data class Criticality(
    val id: String,
    val kind: CritKind,
    val severity: Severity,
    val title: String,
    val detail: String,
    val startM: Double,
    val endM: Double,
    val lat: Double,
    val lon: Double,
    /** Direction of travel there (for the street photos and Street View). */
    val headingDeg: Double? = null,
    /** Swept-path drawing, in metres around [lat]/[lon]. */
    val scene: TurnCheck.Scene? = null,
    /** Widths are estimated from the road type, not measured. */
    val estimated: Boolean = false,
) {
  /** Points the driver can ask to avoid (a ban on a whole country cannot be avoided this way). */
  val avoidable: Boolean
    get() = kind != CritKind.BAN
}

/**
 * Finds the physical and legal difficulties of a route for the vehicle of the trip:
 *  - the ones mapped in criticita.sqlite (narrow, rough, steep, tight curves, fords);
 *  - exit ramps and junctions checked with the swept path of the real vehicle;
 *  - limits the vehicle does not respect or passes with a small margin;
 *  - national driving bans at the time the vehicle will be in each country.
 */
class CriticalityFinder(regions: RegionManager) {
  private val dbs = RegionDbs(regions) { it.critical }
  private val json = Json { ignoreUnknownKeys = true }

  fun find(
      a: RouteAnalysis,
      m: RouteMatcher,
      limits: List<RouteLimit>,
      v: VehicleProfile,
      loadT: Double,
      departure: LocalDateTime,
  ): List<Criticality> {
    val started = System.currentTimeMillis()
    // the swept-path checks never hold up the route choice for long: after this they are skipped
    val deadline = started + 8_000
    val out = mutableListOf<Criticality>()
    val line = a.route.geometry
    val heavy = v.type != VehicleType.FURGONE && (v.type != VehicleType.CAMPER || v.lengthM > 8)
    fun headingAt(along: Double): Double = Geo.bearing(a.pointAt((along - 10).coerceAtLeast(0.0)), a.pointAt(along + 10))

    // 1. mapped difficulties
    val rows = dbs.perCells(m.cells) { db, inList ->
      val list = mutableListOf<DbRow>()
      db.rawQuery(
          "SELECT DISTINCT k.id, k.kind, k.value, k.info, k.name, k.lat, k.lon, k.pts FROM crit_cells c " +
              "JOIN crit k ON k.id = c.cid WHERE c.cell IN ($inList)", null).use { c ->
        while (c.moveToNext()) {
          list += DbRow(c.getLong(0), c.getString(1), c.getDouble(2), c.getString(3), c.getString(4), c.getDouble(5), c.getDouble(6),
              RouteMatcher.parsePts(c.getString(7)))
        }
      }
      list
    }
    val seen = HashSet<Long>()
    for (r in rows) {
      if (!seen.add(r.id)) continue
      val (lo, hi) = m.span(r.pts, 12.0) ?: continue
      val info = runCatching { json.parseToJsonElement(r.info).jsonObject }.getOrNull()
      judge(r, lo, hi, info, v, heavy, a, m, deadline, ::headingAt)?.let { out += it }
    }

    // 2. exit ramps with the swept path of this vehicle
    if (heavy) {
      for ((span, edges) in a.ramps) {
        if (out.any { it.kind == CritKind.RAMP && it.startM < span.endM && it.endM > span.startM }) continue
        if (span.length < 25 || System.currentTimeMillis() > deadline) continue
        val lanes = edges.maxOf { it.lanes }.coerceAtLeast(1)
        val width = lanes * 3.5 + 1.0
        val pts = Geo.slice(line, a.cum, span.startM, span.endM)
        val plane = LocalPlane(pts.first().lat, pts.first().lng)
        val scene = TurnCheck.curve(pts.map { plane.toXY(it) }, width, v, 0.6) ?: continue
        if (scene.verdict == TurnCheck.Verdict.OK) continue
        val p = pts.first()
        out += Criticality(
            id = "ramp:${span.startM.toLong()}",
            kind = CritKind.RAMP,
            severity = if (scene.verdict == TurnCheck.Verdict.NO) Severity.CRITICAL else Severity.WARN,
            title = "Svincolo con curva stretta" + (edges.firstNotNullOfOrNull { it.name }?.let { " ($it)" } ?: ""),
            detail = rampText(scene, v),
            startM = span.startM, endM = span.endM, lat = p.lat, lon = p.lng, headingDeg = headingAt(span.startM),
            scene = scene, estimated = true,
        )
      }
    }

    // 3. junctions: turns of 50 degrees or more on ordinary roads
    if (heavy && v.lengthM >= 9.0) {
      var along = 0.0
      val steps = a.route.steps
      for ((i, step) in steps.withIndex()) {
        val stepStart = along
        along += step.distance
        if (i == 0) continue
        val at = stepStart
        if (at < 30 || at > a.length - 10) continue
        val before = a.edgeAt((at - 8).coerceAtLeast(0.0))
        val after = a.edgeAt(at + 8)
        if (before == null || after == null) continue
        if (before.isRamp || after.isRamp || before.roadClass == "motorway" || after.roadClass == "motorway") continue
        val hIn = Geo.bearing(a.pointAt(at - 25), a.pointAt(at - 3))
        val hOut = Geo.bearing(a.pointAt(at + 3), a.pointAt(at + 25))
        val turn = Geo.angleDiff(hIn, hOut)
        if (abs(turn) < 50 || System.currentTimeMillis() > deadline) continue
        val wIn = roadWidth(before)
        val wOut = roadWidth(after)
        val kerb = minOf(kerbRadius(before), kerbRadius(after))
        val inDir = dirOf(hIn)
        val outDir = dirOf(hOut)
        val scene = TurnCheck.junction(inDir, outDir, wIn, wOut, kerb, v) ?: continue
        if (scene.verdict == TurnCheck.Verdict.OK) continue
        if (out.any { it.kind == CritKind.JUNCTION && abs(it.startM - at) < 40 }) continue
        val p = a.pointAt(at)
        val side = if (turn > 0) "a destra" else "a sinistra"
        out += Criticality(
            id = "turn:${at.toLong()}",
            kind = CritKind.JUNCTION,
            // widths are estimated from the road type: never more than a warning
            severity = Severity.WARN,
            title = "Svolta $side stretta" + (after.name?.let { " in $it" } ?: ""),
            detail = junctionText(scene, v, wIn, wOut),
            startM = at - 15, endM = at + 15, lat = p.lat, lon = p.lng, headingDeg = hIn,
            scene = scene, estimated = true,
        )
      }
    }

    // 4. unpaved stretches the database did not have
    if (heavy) {
      for (s in a.unpaved) {
        if (s.length < 50) continue
        if (out.any { it.kind == CritKind.ROUGH && it.startM < s.endM && it.endM > s.startM }) continue
        val p = a.pointAt(s.startM)
        out += Criticality("unpaved:${s.startM.toLong()}", CritKind.ROUGH, Severity.WARN, "Strada sterrata",
            "Tratto non asfaltato di ${Fmt.distanceText(s.length)}.", s.startM, s.endM, p.lat, p.lng, headingAt(s.startM))
      }
    }

    // 5. limits: the ones not respected and the ones passed with little margin
    for (l in limits) {
      val margin = when (l.kind) {
        "maxheight" -> l.value - v.heightM
        "maxwidth" -> l.value - v.widthM
        "maxlength" -> l.value - v.lengthM
        "maxweight" -> l.value - v.tripWeightT(loadT)
        else -> null
      }
      if (l.blocking) {
        out += Criticality("limit:${l.kind}:${l.alongM.toLong()}", CritKind.LIMIT, Severity.CRITICAL,
            "${l.label} ${l.signValue ?: ""}".trim(),
            (l.name?.let { "$it: " } ?: "") + "il mezzo non rispetta questo limite." + (l.conditional?.let { " ($it)" } ?: ""),
            l.alongM, l.alongM + 10, l.lat, l.lon, headingAt(l.alongM))
      } else if (margin != null && margin >= 0 && margin < (if (l.kind == "maxweight") 1.0 else 0.15)) {
        out += Criticality("margin:${l.kind}:${l.alongM.toLong()}", CritKind.LIMIT, Severity.WARN,
            "${l.label} ${l.signValue ?: ""}: margine ridotto".trim(),
            "Passi con appena ${if (l.kind == "maxweight") Fmt.tonnes(margin) else Fmt.metres(margin)} di margine: verifica le misure reali.",
            l.alongM, l.alongM + 10, l.lat, l.lon, headingAt(l.alongM))
      }
    }

    // 6. national driving bans at the expected time in each country
    if (v.isHgv && a.countries.isNotEmpty() && a.length > 0) {
      val timeAt = { along: Double -> departure.plusSeconds((a.durationS * along / a.length).toLong()) }
      val fmt = DateTimeFormatter.ofPattern("HH:mm")
      for (h in TruckBans.check(a.countries, a.length, v.maxWeightT, timeAt)) {
        val p = a.pointAt(h.alongM)
        out += Criticality("ban:${h.rule.iso}:${h.alongM.toLong()}", CritKind.BAN, Severity.WARN,
            "Divieto di circolazione in ${h.rule.country}",
            "Mezzi oltre ${Fmt.tonnes(h.rule.overT)}: ${h.rule.what} (${h.rule.roads}). Sei previsto lì ${TruckBans.dayName(h.from)} " +
                "dalle ${h.from.format(fmt)} alle ${h.to.format(fmt)}. Verifica deroghe e calendario ufficiale.",
            h.alongM, h.alongM + 1, p.lat, p.lng)
      }
      val ro = a.edges.firstOrNull { e -> e.country == "RO" && (e.name?.contains(Regex("\\bDN ?1\\b|\\bE ?60\\b")) == true) }
      if (ro != null) {
        val p = a.pointAt(ro.startM)
        out += Criticality("ban:RO:DN1", CritKind.BAN, Severity.INFO, "Restrizioni su DN1 (E60)", TruckBans.ROMANIA_NOTE,
            ro.startM, ro.startM + 1, p.lat, p.lng)
      }
    }

    val sorted = out.sortedBy { it.startM }
    Log.i("NavMasterCrit", "criticalities: ${sorted.size} (${sorted.count { it.severity == Severity.CRITICAL }} critical) " +
        "from ${rows.size} rows in ${System.currentTimeMillis() - started} ms")
    // one line per difficulty, to check them against the map
    for (c in sorted) {
      Log.d("NavMasterCrit", "crit ${c.kind} ${c.severity} @${c.startM.toInt()} ${"%.5f".format(java.util.Locale.ROOT, c.lat)},${"%.5f".format(java.util.Locale.ROOT, c.lon)} " +
          "r=${c.scene?.radiusM?.toInt() ?: -1} over=${c.scene?.overrunM?.let { "%.2f".format(java.util.Locale.ROOT, it) } ?: "-"} ${c.id} | ${c.title}")
    }
    return sorted
  }

  private data class DbRow(
      val id: Long, val kind: String, val value: Double, val info: String, val name: String?,
      val lat: Double, val lon: Double, val pts: List<GeographicCoordinate>,
  )

  private val ROUNDABOUT_NAME = Regex("(?i)rotonda|rotatoria|roundabout|kreisel|kreisverkehr|rond-point|giratoriu|rondo|körforgalom|glorieta|rotunda")

  private fun judge(
      r: DbRow, lo: Double, hi: Double, info: JsonObject?, v: VehicleProfile, heavy: Boolean, a: RouteAnalysis,
      m: RouteMatcher, deadline: Long, headingAt: (Double) -> Double,
  ): Criticality? {
    val len = max(hi - lo, 0.0)
    val oneway = info?.get("ow")?.jsonPrimitive?.booleanOrNull ?: false
    val hw = info?.get("hw")?.jsonPrimitive?.contentOrNull ?: ""
    val where = r.name?.let { " ($it)" } ?: ""
    val p = a.pointAt(lo)
    fun c(kind: CritKind, sev: Severity, title: String, detail: String, scene: TurnCheck.Scene? = null, est: Boolean = false) =
        Criticality("db:${r.id}", kind, sev, title + where, detail, lo, max(hi, lo + 10), p.lat, p.lng, headingAt(lo), scene, est)
    return when (r.kind) {
      "narrow" -> {
        val w = r.value
        val vw = v.widthM
        if (w > 0) {
          when {
            w < vw + 0.3 -> c(CritKind.NARROW, Severity.CRITICAL, "Strada troppo stretta",
                "Larga circa ${Fmt.metres(w)} per ${Fmt.distanceText(len)}: il mezzo è largo ${Fmt.metres(vw)}.")
            oneway && w < vw + 0.9 -> c(CritKind.NARROW, Severity.WARN, "Carreggiata stretta",
                "Senso unico largo circa ${Fmt.metres(w)} per ${Fmt.distanceText(len)}.")
            !oneway && w < 2 * vw + 0.8 && heavy -> c(CritKind.NARROW, if (w < vw + 1.2) Severity.CRITICAL else Severity.WARN,
                "Strada stretta: difficile incrociare",
                "Larga circa ${Fmt.metres(w)} per ${Fmt.distanceText(len)}: con un altro mezzo pesante non si passa in due.")
            else -> null
          }
        } else if (heavy && len > 40) {
          c(CritKind.NARROW, Severity.WARN, "Strada stretta", "Segnalata come stretta o a una sola corsia per ${Fmt.distanceText(len)}.")
        } else null
      }
      "rough" -> {
        if (len < 30) return null
        val level = r.value.toInt()
        val surface = info?.get("surface")?.jsonPrimitive?.contentOrNull
        val sev = if (level >= 2 && heavy) Severity.CRITICAL else if (heavy || level >= 2) Severity.WARN else return null
        c(CritKind.ROUGH, sev, if (level >= 2) "Strada dissestata" else "Fondo non asfaltato",
            "Per ${Fmt.distanceText(len)}" + (surface?.let { ", fondo: ${surfaceIt(it)}" } ?: "") + ".")
      }
      "steep" -> {
        val pct = r.value
        val sev = when {
          heavy && pct >= 12 -> Severity.CRITICAL
          heavy && pct >= 8 -> Severity.WARN
          pct >= 15 -> Severity.WARN
          else -> return null
        }
        c(CritKind.STEEP, sev, "Pendenza del ${pct.toInt()}%", "Salita o discesa ripida per ${Fmt.distanceText(len)}.")
      }
      "ford" -> c(CritKind.FORD, Severity.CRITICAL, "Guado", "La strada attraversa un corso d'acqua.")
      "curve" -> {
        if (!heavy || System.currentTimeMillis() > deadline) return null
        // roundabouts are tight by design and a lorry uses the apron: only a really small one counts
        val roundabout = info?.get("junction")?.jsonPrimitive?.contentOrNull in setOf("roundabout", "circular") ||
            ROUNDABOUT_NAME.containsMatchIn(r.name ?: "") ||
            (r.pts.size > 4 && Geo.dist(r.pts.first(), r.pts.last()) < 3.0)
        if (roundabout && r.value > 9.0) return null
        val (dist, at) = m.nearest(GeographicCoordinate(r.lat, r.lon)) ?: return null
        if (dist > 15) return null
        // only where the route is really on this road around the tightest point: a turn from or
        // onto another road at a junction is the junction check's job, not a "hairpin"
        val onWay = r.pts.mapNotNull { q -> m.nearest(q)?.takeIf { it.first <= 8 && abs(it.second - at) <= 70 }?.second }
        if (onWay.size < 3) return null
        val from = onWay.minOrNull() ?: return null
        val to = onWay.maxOrNull() ?: return null
        if (to - from < 20 || at < from - 5 || at > to + 5) return null
        // the curve as the route drives it (full detail)
        val forward = Geo.slice(a.route.geometry, a.cum, from, to)
        if (forward.size < 3) return null
        val plane = LocalPlane(forward.first().lat, forward.first().lng)
        val lanes = info?.get("lanes")?.jsonPrimitive?.doubleOrNull?.toInt()
        val wTag = info?.get("w")?.jsonPrimitive?.doubleOrNull
        val isLink = hw.endsWith("_link")
        val width = wTag ?: if (isLink) (lanes ?: 1) * 3.5 + 1.0 else roadWidthOf(hw, lanes ?: if (oneway) 1 else 1, oneway)
        val scene = TurnCheck.curve(forward.map { plane.toXY(it) }, width, v, if (isLink) 0.6 else 0.3) ?: return null
        if (scene.verdict == TurnCheck.Verdict.OK) return null
        val sev = if (scene.verdict == TurnCheck.Verdict.NO && wTag != null) Severity.CRITICAL else Severity.WARN
        val here = a.pointAt(at)
        (if (isLink) c(CritKind.RAMP, sev, "Svincolo con curva stretta", rampText(scene, v), scene, wTag == null)
        else if (roundabout) c(CritKind.CURVE, Severity.WARN, "Rotatoria molto piccola", rampText(scene, v), scene, true)
        else c(CritKind.CURVE, sev, if (scene.radiusM < 15) "Tornante" else "Curva stretta", rampText(scene, v), scene, wTag == null))
            .copy(startM = (at - 30).coerceAtLeast(0.0), endM = at + 30, lat = here.lat, lon = here.lng, headingDeg = headingAt(at))
      }
      else -> null
    }
  }

  companion object {
    fun dirOf(bearingDeg: Double): XY {
      val r = Math.toRadians(bearingDeg)
      return XY(kotlin.math.sin(r), kotlin.math.cos(r))
    }

    /** Carriageway width from the road type (metres), when nothing is mapped. */
    fun roadWidth(e: EdgeInfo): Double =
        when (e.roadClass) {
          "motorway", "trunk" -> e.lanes.coerceAtLeast(2) * 3.6
          "primary" -> max(e.lanes * 2 * 3.4, 7.0)
          "secondary" -> max(e.lanes * 2 * 3.2, 6.5)
          "tertiary" -> 6.0
          "unclassified" -> 5.0
          "residential" -> 5.5
          else -> 4.0
        }

    fun roadWidthOf(hw: String, lanes: Int, oneway: Boolean): Double {
      val base = when (hw.removeSuffix("_link")) {
        "motorway", "trunk" -> 3.6 * lanes.coerceAtLeast(2)
        "primary" -> 7.0
        "secondary" -> 6.5
        "tertiary" -> 6.0
        "unclassified" -> 5.0
        "residential", "living_street" -> 5.5
        "track" -> 3.0
        else -> 4.0
      }
      return if (oneway && hw !in setOf("motorway", "trunk")) base * 0.6 else base
    }

    fun kerbRadius(e: EdgeInfo): Double =
        when (e.roadClass) {
          "motorway", "trunk", "primary" -> 9.0
          "secondary" -> 7.0
          "tertiary" -> 5.0
          "unclassified", "residential" -> 3.5
          else -> 2.5
        }

    fun surfaceIt(s: String): String =
        when (s) {
          "gravel", "fine_gravel", "pebblestone" -> "ghiaia"
          "dirt", "earth", "ground" -> "terra battuta"
          "grass" -> "erba"
          "sand" -> "sabbia"
          "mud" -> "fango"
          "compacted" -> "stabilizzato"
          "unpaved" -> "non asfaltato"
          else -> s
        }

    fun rampText(s: TurnCheck.Scene, v: VehicleProfile): String {
      val r = if (s.radiusM < 1000) "raggio circa ${s.radiusM.toInt()} m" else "curva ampia"
      return when (s.verdict) {
        TurnCheck.Verdict.NO ->
            "Curva con $r: con ${Fmt.metres(v.lengthM)} di lunghezza le ruote posteriori escono di circa ${Fmt.metres(s.overrunM)} dalla carreggiata."
        else ->
            "Curva con $r: passi andando piano e usando tutta la corsia" +
                (if (s.overrunM > 0) " (le ruote posteriori sfiorano il bordo)." else ".")
      }
    }

    fun junctionText(s: TurnCheck.Scene, v: VehicleProfile, wIn: Double, wOut: Double): String {
      val base = "Svolta di ${s.angleDeg.toInt()}° tra strade larghe circa ${Fmt.metres(wIn)} e ${Fmt.metres(wOut)} (stima dal tipo di strada)."
      return when (s.verdict) {
        TurnCheck.Verdict.NO ->
            "$base Con ${Fmt.metres(v.lengthM)} il rimorchio taglia l'angolo di circa ${Fmt.metres(s.overrunM)}: probabilmente serve un'altra strada."
        else -> "$base Si passa allargando molto la curva e usando anche l'altra corsia."
      }
    }
  }
}
