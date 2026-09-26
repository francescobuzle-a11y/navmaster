package app.navmaster.truck.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The vehicle's top speed applied to the times of a route, after Valhalla has chosen it.
 *
 * Valhalla's own "top_speed" also makes it avoid every road faster than that value: with a lorry's
 * 80 km/h it left the A14 for 25 km of local roads that take 65% longer. So the route is chosen
 * without that limit and the times are corrected here: every stretch takes at least
 * distance / top speed. Durations of steps, legs, routes and the per-segment annotations are all
 * adjusted, so arrival times, the remaining time and the speeds shown stay consistent.
 */
object SpeedCap {
  private val json = Json { ignoreUnknownKeys = true }

  /** Valhalla's OSRM-format answer with the times of the vehicle; the same text if nothing changes. */
  fun apply(raw: String, topSpeedKmh: Int): String {
    if (topSpeedKmh <= 0) return raw
    val vmax = topSpeedKmh / 3.6
    return try {
      val root = json.parseToJsonElement(raw) as? JsonObject ?: return raw
      val routes = root["routes"] as? JsonArray ?: return raw
      val newRoutes = JsonArray(routes.map { r -> capRoute(r as JsonObject, vmax) })
      JsonObject(root + ("routes" to newRoutes)).toString()
    } catch (e: Exception) {
      raw
    }
  }

  private fun num(e: JsonElement?): Double = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0

  private fun capRoute(route: JsonObject, vmax: Double): JsonObject {
    val legs = (route["legs"] as? JsonArray) ?: return route
    var total = 0.0
    val newLegs = JsonArray(legs.map { l ->
      val leg = l as JsonObject
      var legTotal = 0.0
      val steps = (leg["steps"] as? JsonArray)?.map { s ->
        val step = s as JsonObject
        val d = num(step["distance"])
        val t = maxOf(num(step["duration"]), d / vmax)
        legTotal += t
        JsonObject(step + ("duration" to JsonPrimitive(round1(t))))
      }
      val ann = leg["annotation"] as? JsonObject
      val newAnn = ann?.let { a ->
        val dist = (a["distance"] as? JsonArray)?.map { num(it) }
        val dur = (a["duration"] as? JsonArray)?.map { num(it) }
        if (dist != null && dur != null && dist.size == dur.size) {
          val capped = dist.indices.map { i -> maxOf(dur[i], dist[i] / vmax) }
          val out = a.toMutableMap()
          out["duration"] = JsonArray(capped.map { JsonPrimitive(round1(it)) })
          if (a["speed"] is JsonArray) {
            out["speed"] = JsonArray(dist.indices.map { i -> JsonPrimitive(round1(if (capped[i] > 0) dist[i] / capped[i] else 0.0)) })
          }
          JsonObject(out)
        } else a
      }
      if (steps == null) legTotal = maxOf(num(leg["duration"]), num(leg["distance"]) / vmax)
      total += legTotal
      val out = leg.toMutableMap()
      if (steps != null) out["steps"] = JsonArray(steps)
      if (newAnn != null) out["annotation"] = newAnn
      out["duration"] = JsonPrimitive(round1(legTotal))
      JsonObject(out)
    })
    return JsonObject(route + mapOf("legs" to newLegs, "duration" to JsonPrimitive(round1(total))))
  }

  private fun round1(v: Double) = Math.round(v * 10) / 10.0
}
