package app.navmaster.truck.routing

import app.navmaster.truck.core.Geo
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.ferrostar.GeographicCoordinate

/**
 * The guidance of a route from the engine's answer (openrouteservice's GeoJSON with its Italian
 * instructions, plus the facts about its roads): the OSRM answer Ferrostar reads (manoeuvres,
 * voice, banners, lanes, speed limits) and the roads of the route for RouteAnalysis.
 */
object OrsGuide {
  private val json = Json { ignoreUnknownKeys = true }

  /** The OSRM answer for Ferrostar, and the roads of the route for RouteAnalysis (null without roads). */
  class Output(val osrm: String, val attributes: String?, val roads: Int, val timings: String = "")

  /** The OSRM answer of route [index] of the engine's GeoJSON [text]; null when it has no instructions. */
  fun osrm(text: String, index: Int = 0): String? = build(text, index)?.osrm

  /**
   * Route [index] of the engine's GeoJSON [text] guided by its instructions. [heavy]: a lorry or a
   * bus (its own speed limits), [countryOf]: the ISO code of the country at a point.
   */
  /**
   * [kinds]: what each point of the trip is ("break": a stop, "via": a point the vehicle only passes
   * through), the first being the start; the arrival at a stop is said as such, a point passed
   * through is not said at all (before, both were announced as the destination).
   */
  fun build(text: String, index: Int = 0, heavy: Boolean = false, countryOf: ((Double, Double) -> String?)? = null, kinds: List<String>? = null): Output? {
    val t0 = System.nanoTime()
    val root = json.parseToJsonElement(text).jsonObject
    val tParsed = System.nanoTime()
    val f = root["features"]?.jsonArray?.getOrNull(index)?.jsonObject ?: return null
    val coords = f["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
    if (coords.size < 2) return null
    val pts = coords.map { val c = it.jsonArray; GeographicCoordinate(c[1].jsonPrimitive.doubleOrNull ?: 0.0, c[0].jsonPrimitive.doubleOrNull ?: 0.0) }
    val n = pts.size
    val cum = DoubleArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + Geo.dist(pts[i - 1], pts[i])
    val props = f["properties"]?.jsonObject ?: return null
    val segments = props["segments"]?.jsonArray ?: return null
    if (segments.isEmpty()) return null
    val wayPoints = props["way_points"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: listOf(0, n - 1)
    val totalD = props["summary"]?.jsonObject?.get("distance")?.jsonPrimitive?.doubleOrNull ?: cum[n - 1]
    val totalT = props["summary"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
    // the roads of the route, from the graph (one per edge)
    val roads = RouteRoad.parse(props)
    val on = RouteRoad.perSegment(roads, n)
    val points = RouteRoad.parsePoints(props)

    val b = StringBuilder(64 * 1024)
    b.append("{\"code\":\"Ok\",\"routes\":[{\"distance\":").append(num(totalD)).append(",\"duration\":").append(num(totalT))
        .append(",\"weight_name\":\"auto\",\"weight\":").append(num(totalT)).append(",\"voiceLocale\":\"it-IT\",\"geometry\":")
    q(b, Geo.encodePolyline6(pts))
    b.append(",\"legs\":[")
    var firstLeg = true
    for ((L, seg) in segments.withIndex()) {
      val steps = seg.jsonObject["steps"]?.jsonArray?.map { it.jsonObject } ?: continue
      if (steps.isEmpty()) continue
      val parsed = steps.map { step(it, pts) }.map { st -> st.copy(lanes = lanesAt(st, on, cum)) }
      val lp0 = parsed.first().p0
      val lp1 = parsed.last().p1.coerceAtLeast(lp0 + 1).coerceAtMost(n - 1)
      if (!firstLeg) b.append(',')
      firstLeg = false
      val ld = seg.jsonObject["distance"]?.jsonPrimitive?.doubleOrNull ?: (cum[lp1] - cum[lp0])
      val lt = seg.jsonObject["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
      b.append("{\"distance\":").append(num(ld)).append(",\"duration\":").append(num(lt)).append(",\"weight\":").append(num(lt))
          .append(",\"summary\":\"\",\"via_waypoints\":[],\"annotation\":{")
      // per point segment: the time of the leg spread by distance; the speed limit of the road
      val legLen = (cum[lp1] - cum[lp0]).coerceAtLeast(1.0)
      val dd = StringBuilder(); val du = StringBuilder(); val sp = StringBuilder(); val mx = StringBuilder()
      for (i in lp0 until lp1) {
        if (i > lp0) { dd.append(','); du.append(','); sp.append(','); mx.append(',') }
        val d = cum[i + 1] - cum[i]
        val t = lt * d / legLen
        // the numbers written by hand: String.format costs microseconds on the tablet and here there are three per point (950 km: tens of thousands)
        fixed(dd, d, 1)
        fixed(du, t, 3)
        fixed(sp, if (t > 0) d / t else 0.0, 1)
        val lim = on.getOrNull(i)?.limit(heavy)
        if (lim != null && lim > 0) mx.append("{\"speed\":").append(Math.round(lim)).append(",\"unit\":\"km/h\"}")
        else mx.append("{\"unknown\":true}")
      }
      b.append("\"distance\":[").append(dd).append("],\"duration\":[").append(du).append("],\"speed\":[").append(sp)
          .append("],\"maxspeed\":[").append(mx).append("]},\"steps\":[")
      // the end of a leg that is not the last one: a stop ("Hai raggiunto la tappa", said by the
      // step before it, whose next manoeuvre it is), or a point only passed through (nothing said,
      // and the start of the next leg not said either)
      val passEnd = L < segments.size - 1 && kinds?.getOrNull(L + 1) == "via"
      val passStart = L > 0 && kinds?.getOrNull(L) == "via"
      val named = parsed.map { st ->
        when {
          st.type == "arrive" && L < segments.size - 1 -> st.copy(instruction = if (passEnd) "" else "Hai raggiunto la tappa", quiet = passEnd)
          st.type == "depart" && passStart -> st.copy(quiet = true)
          else -> st
        }
      }
      for ((k, s) in named.withIndex()) {
        if (k > 0) b.append(',')
        writeStep(b, s, named.getOrNull(k + 1), pts)
      }
      b.append("]}")
    }
    b.append("]}],\"waypoints\":[")
    for ((w, at) in wayPoints.withIndex()) {
      if (w > 0) b.append(',')
      val i = at.coerceIn(0, n - 1)
      b.append("{\"name\":\"\",\"distance\":0,\"location\":[").append(num6(pts[i].lng)).append(',').append(num6(pts[i].lat)).append("]}")
    }
    b.append("]}")
    val tSteps = System.nanoTime()
    val attributes = if (roads.isEmpty()) null else attributes(pts, cum, roads, points, countryOf)
    val tEnd = System.nanoTime()
    val ms = { a: Long, z: Long -> (z - a) / 1_000_000 }
    return Output(b.toString(), attributes, roads.size, "lettura ${ms(t0, tParsed)} ms, passi ${ms(tParsed, tSteps)} ms, strade ${ms(tSteps, tEnd)} ms, $n punti")
  }

  /**
   * The lanes before manoeuvre [s]: the turn lanes of the road arriving at it (or of the road before
   * it when close), each lane's arrows and whether it goes the route's way.
   */
  private fun lanesAt(s: Step, on: Array<RouteRoad?>, cum: DoubleArray): List<Array<String>>? {
    if (s.type == "depart" || s.type == "arrive" || s.p0 <= 0) return null
    var tl: String? = null
    var k = s.p0 - 1
    while (k >= 0) {
      val o = on.getOrNull(k)
      if (o != null) {
        tl = o.turnLanes
        if (tl != null) break
      }
      if (cum[s.p0] - cum[k] > 150) break
      k--
    }
    val parts = tl?.split("|") ?: return null
    if (parts.size < 2) return null
    val want = s.modifier ?: "straight"
    var any = false
    val out = parts.map { p ->
      val dirs = p.split(";").map { laneDir(it) }.ifEmpty { listOf("straight") }
      val active = match(dirs, want)
      if (active != null) any = true
      arrayOf(dirs.joinToString(";"), if (active != null) "1" else "0", active ?: "")
    }
    return if (any) out else null
  }

  private fun laneDir(osm: String): String = when (osm.trim()) {
    "through" -> "straight"
    "slight_right" -> "slight right"
    "slight_left" -> "slight left"
    "sharp_right" -> "sharp right"
    "sharp_left" -> "sharp left"
    "merge_to_left" -> "merge to left"
    "merge_to_right" -> "merge to right"
    "reverse" -> "uturn"
    "left", "right" -> osm.trim()
    else -> "straight"
  }

  private fun match(dirs: List<String>, want: String): String? {
    dirs.firstOrNull { it == want }?.let { return it }
    val side = if ("right" in want) "right" else if ("left" in want) "left" else "straight"
    return dirs.firstOrNull { d ->
      if (side == "straight") d == "straight" || d == "none" else side in d && !d.startsWith("merge")
    }
  }

  private fun lanesJson(b: StringBuilder, lanes: List<Array<String>>, banner: Boolean) {
    for ((k, l) in lanes.withIndex()) {
      if (k > 0) b.append(',')
      val dirs = l[0].split(";").joinToString(",", "[", "]") { "\"$it\"" }
      val active = l[1] == "1"
      if (banner) {
        b.append("{\"type\":\"lane\",\"text\":\"\",\"directions\":").append(dirs).append(",\"active\":").append(active)
        if (active) b.append(",\"active_direction\":\"").append(l[2]).append('"')
      } else {
        b.append("{\"indications\":").append(dirs).append(",\"valid\":").append(active).append(",\"active\":").append(active)
        if (active) b.append(",\"valid_indication\":\"").append(l[2]).append('"')
      }
      b.append('}')
    }
  }

  /**
   * The roads of the route in the form RouteAnalysis reads: one edge per road of the graph, cut
   * at toll booths, gantries and border controls (the node then ends the edge), with the signed
   * limits, the direction signs (destination tags, motorway junctions) and the lanes.
   */
  private fun attributes(pts: List<GeographicCoordinate>, cum: DoubleArray, roads: List<RouteRoad>, points: List<RoutePoint>, countryOf: ((Double, Double) -> String?)?): String {
    val n = pts.size
    val b = StringBuilder(32 * 1024)
    b.append("{\"units\":\"kilometers\",\"shape\":")
    q(b, Geo.encodePolyline6(pts))
    val admins = mutableListOf<String>()
    val edges = StringBuilder()
    var prevLink = false
    for (r in roads) {
      val from = r.from.coerceIn(0, n - 1)
      val to = r.to.coerceIn(from, n - 1)
      if (to <= from && !(from == 0 && to == 0)) continue
      // the pieces of this road: cut where a toll booth / gantry / border control stands
      val cuts = (r.nodes.map { it.second }.filter { it > from && it < to }.sorted() + to).distinct()
      var a = from
      for (c in cuts) {
        val nodeType = r.nodes.firstOrNull { it.second == c }?.first
        if (edges.isNotEmpty()) edges.append(',')
        edges.append("{\"way_id\":").append(r.way).append(",\"road_class\":")
        q(edges, r.roadClass)
        edges.append(",\"use\":")
        q(edges, if (r.ferry) "ferry" else if (r.link) "ramp" else "road")
        edges.append(",\"toll\":").append(r.toll).append(",\"surface\":")
        q(edges, when (r.surface) {
          "asphalt", "concrete", "paved", "metal", "wood" -> "paved_smooth"
          "paving_stones" -> "paved_rough"
          "compacted_gravel" -> "compacted"
          "gravel" -> "gravel"
          "dirt", "ground", "grass", "grass_paver", "sand", "unpaved", "ice" -> "dirt"
          else -> "paved"
        })
        edges.append(",\"lane_count\":").append(r.lanes ?: 0)
            .append(",\"length\":").also { fixed(it, (cum[c] - cum[a]) / 1000, 4) }
            .append(",\"begin_shape_index\":").append(a).append(",\"end_shape_index\":").append(c)
            .append(",\"tunnel\":").append(r.tunnel).append(",\"bridge\":").append(r.bridge)
            .append(",\"roundabout\":").append(r.roundabout)
        r.maxspeed?.takeIf { it > 0 }?.let { edges.append(",\"speed_limit\":").append(it) }
        r.turnLanes?.let { edges.append(",\"turn_lanes\":").append(JsonPrimitive(it)) }
        val names = r.names
        if (names.isNotEmpty()) edges.append(",\"names\":").append(names.joinToString(",", "[", "]") { JsonPrimitive(it).toString() })
        if (r.limits.isNotEmpty()) {
          edges.append(",\"limits\":{").append(r.limits.entries.joinToString(",") { (k, v) -> JsonPrimitive(k).toString() + ":" + String.format(Locale.ROOT, "%.2f", v) }).append('}')
        }
        if (r.hazmat) edges.append(",\"hazmat\":true")
        r.adrTunnel?.let { edges.append(",\"adr_tunnel\":").append(JsonPrimitive(it)) }
        if (r.conditional.isNotEmpty()) edges.append(",\"conditional\":").append(r.conditional.joinToString(",", "[", "]") { JsonPrimitive(it).toString() })
        r.laneSigns?.let { ls ->
          edges.append(",\"lane_signs\":{\"destination\":").append(JsonPrimitive(ls.destination)).append(",\"ref\":").append(JsonPrimitive(ls.ref))
              .append(",\"colour\":").append(JsonPrimitive(ls.colour)).append('}')
        }
        // the direction sign at the start of a ramp (exit number and name of the motorway junction,
        // destinations of the way or of a destination_sign relation)
        if (a == from && (r.exit != null || r.sign != null || (r.link && !prevLink && (r.destination != null || r.destinationRef != null)))) {
          val exitRef = r.exit?.substringBefore('|')?.takeIf { it.isNotBlank() }
          val exitName = r.exit?.substringAfter('|', "")?.takeIf { it.isNotBlank() }
          val toward = r.sign?.destination?.takeIf { it.isNotBlank() } ?: r.destination
          val branch = r.sign?.ref?.takeIf { it.isNotBlank() } ?: r.destinationRef
          edges.append(",\"sign\":{\"exit_number\":").append(texts(exitRef)).append(",\"exit_branch\":").append(texts(branch))
              .append(",\"exit_toward\":").append(texts(toward)).append(",\"exit_name\":").append(texts(exitName))
          r.sign?.colour?.takeIf { it.isNotBlank() }?.let { edges.append(",\"colour\":").append(JsonPrimitive(it)) }
          edges.append('}')
        }
        edges.append(",\"begin_heading\":").append(heading(pts, a, 1)).append(",\"end_heading\":").append(heading(pts, c, -1))
        val cc = countryOf?.invoke(pts[c].lat, pts[c].lng)
        var ai = -1
        if (cc != null) {
          ai = admins.indexOf(cc)
          if (ai < 0) { admins += cc; ai = admins.size - 1 }
        }
        edges.append(",\"end_node\":{")
        if (ai >= 0) edges.append("\"admin_index\":").append(ai).append(',')
        edges.append("\"type\":")
        q(edges, nodeType ?: "street_intersection")
        // the other roads leaving the end of this road (only at the real end of the edge)
        if (c == to && r.branches.isNotEmpty()) {
          edges.append(",\"intersecting_edges\":[")
          for ((k, br) in r.branches.withIndex()) {
            if (k > 0) edges.append(',')
            val drive = when { br.access && br.accessBack -> "both"; br.access -> "forward"; br.accessBack -> "backward"; else -> "none" }
            val rc = when (br.cls) { "living_street" -> "residential"; "service", "road", "other", "track" -> "service_other"; else -> br.cls }
            edges.append("{\"driveability\":").append(JsonPrimitive(drive)).append(",\"use\":").append(JsonPrimitive(if (br.link) "ramp" else "road"))
                .append(",\"begin_heading\":").append(Math.round(br.heading)).append(",\"road_class\":").append(JsonPrimitive(rc))
                .append(",\"lane_count\":").append(br.lanes ?: 0).append(",\"way_id\":").append(br.way)
            val bExitRef = br.exit?.substringBefore('|')?.takeIf { it.isNotBlank() }
            val bExitName = br.exit?.substringAfter('|', "")?.takeIf { it.isNotBlank() }
            val bToward = br.sign?.destination?.takeIf { it.isNotBlank() } ?: br.destination
            val bBranch = br.sign?.ref?.takeIf { it.isNotBlank() } ?: br.destinationRef
            if (bExitRef != null || bToward != null || bBranch != null) {
              edges.append(",\"sign\":{\"exit_number\":").append(texts(bExitRef)).append(",\"exit_branch\":").append(texts(bBranch))
                  .append(",\"exit_toward\":").append(texts(bToward)).append(",\"exit_name\":").append(texts(bExitName))
              br.sign?.colour?.takeIf { it.isNotBlank() }?.let { edges.append(",\"colour\":").append(JsonPrimitive(it)) }
              edges.append('}')
            }
            edges.append('}')
          }
          edges.append(']')
        }
        edges.append("}}")
        a = c
      }
      prevLink = r.link
    }
    b.append(",\"admins\":[")
    b.append(admins.joinToString(",") { "{\"country_code\":${JsonPrimitive(it)}}" })
    b.append("],\"edges\":[").append(edges).append("],\"points\":[")
    for ((k, p) in points.withIndex()) {
      if (k > 0) b.append(',')
      b.append("{\"type\":").append(JsonPrimitive(p.type)).append(",\"lat\":").append(num6(p.lat)).append(",\"lon\":").append(num6(p.lon))
          .append(",\"value\":").append(p.valueKmh).append(",\"bearing\":").append(p.bearing).append(",\"offset\":").append(num(p.offsetM))
          .append(",\"distance\":").append(num(p.distanceM)).append('}')
    }
    b.append("]}")
    return b.toString()
  }

  /** Texts of a sign ("A; B" → two entries) in the form RouteAnalysis reads. */
  private fun texts(v: String?): String = (v?.split(";")?.map(String::trim)?.filter { it.isNotEmpty() } ?: emptyList()).joinToString(",", "[", "]") { "{\"text\":${JsonPrimitive(it)}}" }

  /** The direction of the line at point [i] (degrees), looking [dir] = 1 forward, -1 backward. */
  private fun heading(pts: List<GeographicCoordinate>, i: Int, dir: Int): Int {
    val j = (i + dir).coerceIn(0, pts.size - 1)
    if (j == i) return 0
    val h = if (dir > 0) Geo.bearing(pts[i], pts[j]) else Geo.bearing(pts[j], pts[i])
    return ((h.toInt() % 360) + 360) % 360
  }

  private data class Step(
      val type: String, val modifier: String?, val exit: Int?, val name: String, val instruction: String,
      val distance: Double, val duration: Double, val p0: Int, val p1: Int,
      val bearingBefore: Int, val bearingAfter: Int,
      /** The lanes before this manoeuvre: [arrows, active 0/1, active arrow]. */
      val lanes: List<Array<String>>? = null,
      /** Not said (a point passed through: its arrival, the start of the next leg). */
      val quiet: Boolean = false,
  )

  /** One step of openrouteservice: its manoeuvre in OSRM terms (types 0..13 of openrouteservice). */
  private fun step(o: JsonObject, pts: List<GeographicCoordinate>): Step {
    val t = o["type"]?.jsonPrimitive?.intOrNull ?: 6
    val wp = o["way_points"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull } ?: listOf(0, 0)
    val p0 = wp.getOrElse(0) { 0 }.coerceIn(0, pts.size - 1)
    val p1 = wp.getOrElse(1) { p0 }.coerceIn(p0, pts.size - 1)
    val exit = o["exit_number"]?.jsonPrimitive?.intOrNull
    val (type, modifier) = when (t) {
      0 -> "turn" to "left"
      1 -> "turn" to "right"
      2 -> "turn" to "sharp left"
      3 -> "turn" to "sharp right"
      4 -> "turn" to "slight left"
      5 -> "turn" to "slight right"
      6 -> "continue" to "straight"
      7 -> "roundabout" to "right"
      8 -> "exit roundabout" to "right"
      9 -> "turn" to "uturn"
      10 -> "arrive" to null
      11 -> "depart" to null
      12 -> "fork" to "slight left"
      13 -> "fork" to "slight right"
      else -> "continue" to "straight"
    }
    val man = o["maneuver"]?.jsonObject
    val bb = man?.get("bearing_before")?.jsonPrimitive?.doubleOrNull ?: bearing(pts, p0, -1)
    val ba = man?.get("bearing_after")?.jsonPrimitive?.doubleOrNull ?: bearing(pts, p0, 1)
    val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "-" } ?: ""
    val instr = o["instruction"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: when (type) {
      "arrive" -> "Sei arrivato a destinazione"
      "depart" -> "Parti"
      else -> "Prosegui"
    }
    return Step(type, modifier, exit, name, instr,
        o["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0, o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        p0, p1, ((bb.toInt() % 360) + 360) % 360, ((ba.toInt() % 360) + 360) % 360)
  }

  private fun bearing(pts: List<GeographicCoordinate>, i: Int, dir: Int): Double {
    val j = (i + dir).coerceIn(0, pts.size - 1)
    if (j == i) return 0.0
    return if (dir > 0) Geo.bearing(pts[i], pts[j]) else Geo.bearing(pts[j], pts[i])
  }

  private fun sd0(s: Step) = s.distance

  private fun writeStep(b: StringBuilder, s: Step, next: Step?, pts: List<GeographicCoordinate>) {
    b.append("{\"distance\":").append(num(s.distance)).append(",\"duration\":").append(num(s.duration)).append(",\"weight\":").append(num(s.duration))
        .append(",\"mode\":\"driving\",\"driving_side\":\"right\",\"name\":")
    q(b, s.name)
    b.append(",\"geometry\":")
    val geo = if (s.type == "arrive" || s.p1 <= s.p0) listOf(pts[s.p0], pts[s.p0]) else pts.subList(s.p0, s.p1 + 1)
    q(b, Geo.encodePolyline6(geo))
    b.append(",\"maneuver\":{\"type\":")
    q(b, s.type)
    if (s.modifier != null) { b.append(",\"modifier\":"); q(b, s.modifier) }
    if (s.type == "roundabout" && s.exit != null) b.append(",\"exit\":").append(s.exit)
    b.append(",\"bearing_before\":").append(s.bearingBefore).append(",\"bearing_after\":").append(s.bearingAfter)
        .append(",\"location\":[").append(num6(pts[s.p0].lng)).append(',').append(num6(pts[s.p0].lat)).append("],\"instruction\":")
    q(b, s.instruction)
    b.append("},\"intersections\":[{\"location\":[").append(num6(pts[s.p0].lng)).append(',').append(num6(pts[s.p0].lat))
        .append("],\"bearings\":[").append(s.bearingAfter).append("],\"entry\":[true],\"in\":0,\"out\":0")
    if (s.lanes != null) {
      b.append(",\"lanes\":[")
      lanesJson(b, s.lanes, false)
      b.append(']')
    }
    b.append("}]")
    b.append(",\"bannerInstructions\":[")
    if (next != null) {
      val text = next.name.ifBlank { next.instruction }
      b.append("{\"distanceAlongGeometry\":").append(num(s.distance)).append(",\"primary\":{\"text\":")
      q(b, text)
      b.append(",\"type\":")
      q(b, next.type)
      if (next.modifier != null) { b.append(",\"modifier\":"); q(b, next.modifier) }
      b.append(",\"driving_side\":\"right\",\"components\":[{\"type\":\"text\",\"text\":")
      q(b, text)
      b.append("}]}")
      if (next.name.isNotBlank() && next.instruction != next.name) {
        b.append(",\"secondary\":{\"text\":")
        q(b, next.instruction)
        b.append(",\"components\":[{\"type\":\"text\",\"text\":")
        q(b, next.instruction)
        b.append("}]}")
      }
      if (next.lanes != null) {
        b.append(",\"sub\":{\"text\":\"\",\"components\":[")
        lanesJson(b, next.lanes, true)
        b.append("]}")
      }
      b.append('}')
    }
    b.append("],\"voiceInstructions\":[")
    if (next != null && !(next.quiet && sd0(s) < 600)) {
      val sd = s.distance
      val fast = s.duration > 0 && sd / s.duration > 20.0
      val say = next.instruction.trimEnd('.') + "."
      val vs = mutableListOf<Pair<Double, String>>()
      if (s.type == "depart" && !s.quiet) vs += sd to (s.instruction.trimEnd('.') + "." + if (sd < 400 && !next.quiet) " Poi " + say.replaceFirstChar { it.lowercase() } else "")
      else if (sd >= 600) vs += (sd - 10).coerceAtLeast(0.0) to "Continua per ${distanceWords(sd)}."
      val pre = if (fast) 1300.0 else 450.0
      val fin = if (fast) 300.0 else 140.0
      if (!next.quiet) {
        if (sd > pre + 200) vs += pre to "Tra ${distanceWords(pre)}, ${say.replaceFirstChar { it.lowercase() }}"
        // the manoeuvre's own cue comes after the start's one (a short first step: both were at the
        // same distance, and the start's "Avanti Nord su …" was taken for the manoeuvre)
        val at = minOf(sd, fin)
        vs += (if (s.type == "depart" && at >= sd) sd * 0.5 else at) to say
      }
      for ((k, v) in vs.withIndex()) {
        if (k > 0) b.append(',')
        b.append("{\"distanceAlongGeometry\":").append(num(v.first)).append(",\"announcement\":")
        q(b, v.second)
        b.append(",\"ssmlAnnouncement\":")
        q(b, "<speak>" + v.second.replace("&", "e").replace("<", "").replace(">", "") + "</speak>")
        b.append('}')
      }
    }
    b.append("]}")
  }

  private fun distanceWords(m: Double): String = when {
    m < 1000 -> "${(Math.round(m / 50.0) * 50).coerceAtLeast(50L)} metri"
    m < 1500 -> "1 chilometro"
    else -> {
      val km = Math.round(m / 100.0) / 10.0
      if (km == Math.floor(km)) "${km.toLong()} chilometri" else String.format(Locale.ITALIAN, "%.1f chilometri", km)
    }
  }

  private fun num(d: Double): String = StringBuilder(12).also { fixed(it, d, 1) }.toString()
  private fun num6(d: Double): String = StringBuilder(14).also { fixed(it, d, 6) }.toString()

  private val POW10 = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000)

  /**
   * [v] with [dec] decimals, as `String.format(Locale.ROOT, "%.<dec>f", v)` writes it (rounded half
   * up), without the formatter. Out of range (or not a number) the formatter all the same.
   */
  internal fun fixed(sb: StringBuilder, v: Double, dec: Int) {
    if (v.isNaN() || v.isInfinite() || kotlin.math.abs(v) >= 1e12 || dec !in 0..7) { sb.append(String.format(Locale.ROOT, "%.${dec}f", v)); return }
    val scale = POW10[dec]
    val x = Math.round(kotlin.math.abs(v) * scale)
    if (v < 0) sb.append('-')
    sb.append(x / scale)
    if (dec == 0) return
    sb.append('.')
    val frac = (x % scale).toString()
    for (i in frac.length until dec) sb.append('0')
    sb.append(frac)
  }

  private fun q(b: StringBuilder, s: String) {
    b.append(JsonPrimitive(s).toString())
  }
}
