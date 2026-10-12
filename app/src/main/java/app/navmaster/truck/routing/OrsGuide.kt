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
 * tablet has no map (a country not downloaded), so the driver is guided online anyway. Without the
 * graph there are no lanes, signs or speed limits of the road: only the manoeuvres, the banners and
 * the voice.
 */
object OrsGuide {
  private val json = Json { ignoreUnknownKeys = true }

  /** The OSRM answer of route [index] of openrouteservice's GeoJSON [text]; null when it has no instructions. */
  fun osrm(text: String, index: Int = 0): String? {
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

    val b = StringBuilder(64 * 1024)
    b.append("{\"code\":\"Ok\",\"routes\":[{\"distance\":").append(num(totalD)).append(",\"duration\":").append(num(totalT))
        .append(",\"weight_name\":\"auto\",\"weight\":").append(num(totalT)).append(",\"voiceLocale\":\"it-IT\",\"geometry\":")
    q(b, Geo.encodePolyline6(pts))
    b.append(",\"legs\":[")
    var firstLeg = true
    for ((L, seg) in segments.withIndex()) {
      val steps = seg.jsonObject["steps"]?.jsonArray?.map { it.jsonObject } ?: continue
      if (steps.isEmpty()) continue
      val parsed = steps.map { step(it, pts) }
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
        mx.append("{\"unknown\":true}")
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
    return b.toString()
  }

  private data class Step(
      val type: String, val modifier: String?, val exit: Int?, val name: String, val instruction: String,
      val distance: Double, val duration: Double, val p0: Int, val p1: Int,
      val bearingBefore: Int, val bearingAfter: Int,
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
        .append("],\"bearings\":[").append(s.bearingAfter).append("],\"entry\":[true],\"in\":0,\"out\":0}]")
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
