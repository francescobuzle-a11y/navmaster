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
 * The guidance of a route of openrouteservice from its own instructions (Italian, with the
 * bearings of each manoeuvre), written as the OSRM answer Ferrostar reads: for the places where the
 * tablet has no map nor graph (a country not downloaded), so the driver is guided online anyway.
 * With the light package of the country (limiti.sqlite with its table «roads», RoadsIndex) the
 * route is matched on the roads, and the guidance gets what the graph gives offline: speed limits,
 * lanes and arrows at the manoeuvres, and the roads of the route for RouteAnalysis (road class,
 * lanes, way ids for the limits, tolls, tunnels, countries).
 */
object OrsGuide {
  private val json = Json { ignoreUnknownKeys = true }

  /** The OSRM answer for Ferrostar, and the roads of the route for RouteAnalysis (null without roads). */
  class Output(val osrm: String, val attributes: String?, val matched: Int)

  /** The OSRM answer of route [index] of openrouteservice's GeoJSON [text]; null when it has no instructions. */
  fun osrm(text: String, index: Int = 0): String? = build(text, index)?.osrm

  /**
   * Route [index] of openrouteservice's GeoJSON [text] guided by its instructions. [roads]: the
   * road of every piece of the line (RoadsIndex.along), [heavy]: a lorry or a bus (its own speed
   * limits), [countryOf]: the ISO code of the country at a point.
   */
  fun build(
      text: String,
      index: Int = 0,
      roads: ((List<GeographicCoordinate>) -> Array<app.navmaster.truck.data.RoadsIndex.On?>)? = null,
      heavy: Boolean = false,
      countryOf: ((Double, Double) -> String?)? = null,
  ): Output? {
    val root = json.parseToJsonElement(text).jsonObject
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
    // what openrouteservice says of each piece (tolls, motorway / tunnel / ferry, kind of road)
    val extras = props["extras"]?.jsonObject
    fun extra(key: String): IntArray {
      val a = IntArray(maxOf(0, n - 1)) { -1 }
      extras?.get(key)?.jsonObject?.get("values")?.jsonArray?.forEach { v ->
        val t = v.jsonArray
        val from = t.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@forEach
        val to = t.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return@forEach
        val value = t.getOrNull(2)?.jsonPrimitive?.intOrNull ?: return@forEach
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(a.size)) a[i] = value
      }
      return a
    }
    val tollX = extra("tollways")
    val catX = extra("waycategory")
    val typeX = extra("waytype")
    val on = runCatching { roads?.invoke(pts) }.getOrNull() ?: arrayOfNulls(maxOf(0, n - 1))
    val matched = on.count { it != null }

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
      // per point segment: the time of the leg spread by distance (no speed limits without the graph)
      val legLen = (cum[lp1] - cum[lp0]).coerceAtLeast(1.0)
      val dd = StringBuilder(); val du = StringBuilder(); val sp = StringBuilder(); val mx = StringBuilder()
      for (i in lp0 until lp1) {
        if (i > lp0) { dd.append(','); du.append(','); sp.append(','); mx.append(',') }
        val d = cum[i + 1] - cum[i]
        val t = lt * d / legLen
        dd.append(String.format(Locale.ROOT, "%.1f", d))
        du.append(String.format(Locale.ROOT, "%.3f", t))
        sp.append(String.format(Locale.ROOT, "%.1f", if (t > 0) d / t else 0.0))
        val lim = on.getOrNull(i)?.let { it.road.limit(it.forward, heavy) }
        if (lim != null && lim > 0) mx.append("{\"speed\":").append(Math.round(lim)).append(",\"unit\":\"km/h\"}")
        else mx.append("{\"unknown\":true}")
      }
      b.append("\"distance\":[").append(dd).append("],\"duration\":[").append(du).append("],\"speed\":[").append(sp)
          .append("],\"maxspeed\":[").append(mx).append("]},\"steps\":[")
      for ((k, s) in parsed.withIndex()) {
        if (k > 0) b.append(',')
        val next = parsed.getOrNull(k + 1)
        // the last step of a leg that is not the last one is a stop
        val m = if (s.type == "arrive" && L < segments.size - 1) s.copy(instruction = "Hai raggiunto la tappa") else s
        writeStep(b, m, next, pts)
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
    val attributes = if (matched == 0 && countryOf == null) null else attributes(pts, cum, on, tollX, catX, typeX, countryOf)
    return Output(b.toString(), attributes, matched)
  }

  /**
   * The lanes before manoeuvre [s]: the turn lanes of the road arriving at it (or of the road before
   * it when close), each lane's arrows and whether it goes the route's way (as GhGuide).
   */
  private fun lanesAt(s: Step, on: Array<app.navmaster.truck.data.RoadsIndex.On?>, cum: DoubleArray): List<Array<String>>? {
    if (s.type == "depart" || s.type == "arrive" || s.p0 <= 0) return null
    var tl: String? = null
    var k = s.p0 - 1
    while (k >= 0) {
      val o = on.getOrNull(k)
      if (o != null) {
        tl = o.road.turns(o.forward)
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
   * The roads of the route in the form RouteAnalysis reads (as GhGuide writes them): one edge per
   * stretch on the same road; where no road is known, the kind of road openrouteservice says.
   */
  private fun attributes(
      pts: List<GeographicCoordinate>, cum: DoubleArray, on: Array<app.navmaster.truck.data.RoadsIndex.On?>,
      toll: IntArray, cat: IntArray, type: IntArray, countryOf: ((Double, Double) -> String?)?,
  ): String {
    val n = pts.size
    val b = StringBuilder(32 * 1024)
    // only the roads of the light packages have their way: the others are matched by distance
    b.append("{\"units\":\"kilometers\",\"partial_ways\":true,\"shape\":")
    q(b, Geo.encodePolyline6(pts))
    val admins = mutableListOf<String>()
    val edges = StringBuilder()
    fun key(i: Int): String {
      val o = on.getOrNull(i)
      return if (o != null) "r${o.road.osm}:${o.forward}" else "x${cat.getOrElse(i) { -1 }}:${type.getOrElse(i) { -1 }}:${toll.getOrElse(i) { -1 }}"
    }
    var from = 0
    var i = 0
    while (from < n - 1) {
      val k = key(from)
      i = from + 1
      while (i < n - 1 && key(i) == k) i++
      val o = on.getOrNull(from)
      val c = cat.getOrElse(from) { -1 }.coerceAtLeast(0)
      val t = type.getOrElse(from) { -1 }
      val ferry = c and 4 != 0 || t == 9
      val rc = o?.road?.roadClass ?: when {
        c and 1 != 0 -> "motorway"
        t == 1 -> "primary"
        t == 2 -> "secondary"
        t == 3 -> "residential"
        else -> "service_other"
      }
      val use = when {
        ferry -> "ferry"
        o?.road?.isLink == true -> "ramp"
        else -> "road"
      }
      val isToll = o?.road?.toll == true || toll.getOrElse(from) { 0 } == 1
      val tunnel = o?.road?.tunnel == true || c and 32 != 0
      if (edges.isNotEmpty()) edges.append(',')
      edges.append("{\"way_id\":").append(o?.road?.osm ?: 0).append(",\"road_class\":")
      q(edges, rc)
      edges.append(",\"use\":")
      q(edges, use)
      edges.append(",\"toll\":").append(isToll).append(",\"surface\":")
      q(edges, if (c and 8 != 0) "dirt" else "paved")
      edges.append(",\"lane_count\":").append(o?.let { it.road.laneCount(it.forward) } ?: 1)
          .append(",\"length\":").append(String.format(Locale.ROOT, "%.4f", (cum[i] - cum[from]) / 1000))
          .append(",\"begin_shape_index\":").append(from).append(",\"end_shape_index\":").append(i)
          .append(",\"tunnel\":").append(tunnel).append(",\"bridge\":").append(o?.road?.bridge == true)
          .append(",\"roundabout\":").append(o?.road?.roundabout == true)
      o?.road?.limit(o.forward, false)?.takeIf { it > 0 }?.let { edges.append(",\"speed_limit\":").append(Math.round(it)) }
      o?.let { it.road.turns(it.forward) }?.takeIf { it.isNotBlank() }?.let { edges.append(",\"turn_lanes\":").append(JsonPrimitive(it)) }
      val names = listOfNotNull(o?.road?.ref, o?.road?.name).flatMap { it.split(";").map(String::trim) }.filter { it.isNotEmpty() }
      if (names.isNotEmpty()) edges.append(",\"names\":").append(names.joinToString(",", "[", "]") { JsonPrimitive(it).toString() })
      val cc = countryOf?.invoke(pts[i].lat, pts[i].lng)
      var ai = -1
      if (cc != null) {
        ai = admins.indexOf(cc)
        if (ai < 0) { admins += cc; ai = admins.size - 1 }
      }
      edges.append(",\"end_node\":{")
      if (ai >= 0) edges.append("\"admin_index\":").append(ai).append(',')
      edges.append("\"type\":\"street_intersection\"}}")
      from = i
    }
    b.append(",\"admins\":[")
    b.append(admins.joinToString(",") { "{\"country_code\":${JsonPrimitive(it)}}" })
    b.append("],\"edges\":[").append(edges).append("]}")
    return b.toString()
  }

  private data class Step(
      val type: String, val modifier: String?, val exit: Int?, val name: String, val instruction: String,
      val distance: Double, val duration: Double, val p0: Int, val p1: Int,
      val bearingBefore: Int, val bearingAfter: Int,
      /** The lanes before this manoeuvre: [arrows, active 0/1, active arrow]. */
      val lanes: List<Array<String>>? = null,
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
    if (next != null) {
      val sd = s.distance
      val fast = s.duration > 0 && sd / s.duration > 20.0
      val say = next.instruction.trimEnd('.') + "."
      val vs = mutableListOf<Pair<Double, String>>()
      if (s.type == "depart") vs += sd to (s.instruction.trimEnd('.') + "." + if (sd < 400) " Poi " + say.replaceFirstChar { it.lowercase() } else "")
      else if (sd >= 600) vs += (sd - 10).coerceAtLeast(0.0) to "Continua per ${distanceWords(sd)}."
      val pre = if (fast) 1300.0 else 450.0
      val fin = if (fast) 300.0 else 140.0
      if (sd > pre + 200) vs += pre to "Tra ${distanceWords(pre)}, ${say.replaceFirstChar { it.lowercase() }}"
      vs += minOf(sd, fin) to say
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

  private fun num(d: Double): String = String.format(Locale.ROOT, "%.1f", d)
  private fun num6(d: Double): String = String.format(Locale.ROOT, "%.6f", d)

  private fun q(b: StringBuilder, s: String) {
    b.append(JsonPrimitive(s).toString())
  }
}
