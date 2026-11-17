package app.navmaster.truck.routing

import android.os.SystemClock
import android.util.Log
import it.navmaster.engine.routing.CancelToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the engine says of one of its variants (`properties.navmaster.variant`): [rank] 0 the best,
 * 1.. the variants in order; [tier] 1 as good as the best, 2 a little longer (or not on motorways);
 * [label] the road ("via A21", "via A4 + A22"), [diff] the differences from the best
 * ("+4 min, −6 km"), [longer] only for a variant clearly longer than the best.
 */
class VariantInfo(
    val rank: Int,
    val tier: Int,
    val source: String,
    val label: String,
    val diff: String,
    val signature: String,
    val refs: List<Pair<String, Double>>,
    val extraS: Double,
    val extraM: Double,
    val longer: Boolean,
)

/**
 * A route as the engine returns it: its line, where each stop is on it, length and time; [variant]
 * when it is one of the engine's variants, [feature] its place in the answer (for its guidance).
 */
class RouteResult(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val waypointIndex: IntArray,
    val distanceM: Double,
    val timeMs: Long,
    val variant: VariantInfo? = null,
    val feature: Int = 0,
)

/**
 * The engine's answer to one request: the routes (null when there is none, then [note] says why)
 * and the answer itself ([text], GeoJSON: its instructions guide the routes, OrsGuide). Returned,
 * not kept: several requests may run at the same time.
 */
class NmAnswer(val results: List<RouteResult>?, val text: String?, val note: String?)

/**
 * The engine's own variants of a trip (`navmaster.alternatives`), with any number of points: [count]
 * routes in all, the best one included (it is not sent again: the app has it), as good as the best
 * within [tier1Factor] of its time and [tier1KmFactor] of its length, else a little longer within
 * [maxFactor] / [maxKmFactor]; [allowFewerKm]: a shorter one is kept up to [maxFactor] of the time.
 */
class VariantAsk(
    val count: Int,
    val tier1Factor: Double,
    val tier1KmFactor: Double,
    val maxFactor: Double,
    val maxKmFactor: Double,
    val allowFewerKm: Boolean = false,
    val budgetMs: Long? = null,
) {
  companion object {
    /** Computed by itself right after the best route: three more as good as it (≤ 1.10×, else ≤ 1.20×). */
    val AUTO = VariantAsk(count = 4, tier1Factor = 1.10, tier1KmFactor = 1.10, maxFactor = 1.20, maxKmFactor = 1.20, budgetMs = 25_000)
    /** "Altri percorsi": more, also longer (up to 1.5× the time), and the shorter ones even when slower. */
    val MORE = VariantAsk(count = 6, tier1Factor = 1.25, tier1KmFactor = 1.25, maxFactor = 1.5, maxKmFactor = 1.6, allowFewerKm = true)
  }
}

/**
 * The route computed by navmaster-engine on the tablet with openrouteservice's own logic: the
 * request is the JSON openrouteservice's API takes (profile driving-hgv with the vehicle's measures
 * for lorries and buses, driving-car for campers and cars), the answer its GeoJSON with the
 * Italian instructions and, next to it, the facts about the roads of the route (`navmaster.roads`).
 */
class NmRouting(
    private val engine: NmEngine,
    /** The NavMaster server, for the trips the tablet's graphs cannot cover (null in tests). */
    private val server: NmServer? = null,
    /** The country (catalog id) at a point, null when unknown. */
    private val countryOf: (Double, Double) -> String? = { _, _ -> null },
) {
  /**
   * The routes through [points] ([lat, lon]) for [spec], best first; no results when there is none
   * (then the note says why). [kinds]: "via" for a point only passed through (no u-turn there, the
   * vehicle goes on in its direction of travel: the engine's `navmaster.pass_through`), "break" for
   * a stop. [variants]: the engine's variants instead of the best route (only them, maybe none).
   * [cancel] stops the search: the engine's SearchCancelledException goes up to the caller.
   */
  fun routes(
      points: List<DoubleArray>, spec: RouteSpec, kinds: List<String>, alternates: Int = 0, headings: List<Double?>? = null,
      variants: VariantAsk? = null, call: EngineCall = EngineCall(), cancel: CancelToken = CancelToken.NEVER,
  ): NmAnswer {
    if (points.size < 2) return NmAnswer(null, null, "meno di due punti")
    val started = SystemClock.elapsedRealtime()
    val body = request(points, spec, kinds, alternates, headings, variants).toString()
    // the engine on the tablet (also across borders, with the graphs of the countries crossed); the
    // NavMaster server when a country of the trip is not on the tablet and a server is set
    var from = "engine"
    val reply = engine.directions(points, spec.profile, body, call, cancel)
    var text: String? = reply.text
    if (text == null && server != null && server.unavailable() == null) {
      // the server gets openrouteservice's alternatives (between two points) for the variants, and a
      // server with an older engine refuses them beyond 100 km (openrouteservice's limit): there the
      // request goes without them
      val wanted = variants?.let { it.count - 1 } ?: alternates
      val far = points.size == 2 && app.navmaster.truck.core.Geo.dist(points[0][0], points[0][1], points[1][0], points[1][1]) > 95_000
      if (variants != null && !serverHasVariants(points.size, far)) {
        // the server would compute the same best route again (more than two points, or a far trip:
        // no openrouteservice alternatives there), a long search and megabytes of mobile data for nothing
        val note = reply.problem?.let { "$it; varianti non disponibili dal server" } ?: "varianti non disponibili dal server"
        Log.i(TAG, "variants: not asked of the server (${points.size} points${if (far) ", far" else ""})")
        return NmAnswer(emptyList(), null, note)
      }
      val serverBody = request(points, spec, kinds, if (wanted > 0 && far) 0 else wanted, headings, forServer = true).toString()
      cancel.check()
      text = server.directions(spec.profile, serverBody)
      from = "server"
      if (text == null) Log.w(TAG, "server: ${server.note}")
    }
    if (text == null) {
      val note = reply.problem ?: server?.note ?: "motore non disponibile"
      Log.w(TAG, "engine: $note")
      return NmAnswer(null, null, note)
    }
    return try {
      val root = Json.parseToJsonElement(text).jsonObject
      val error = root["error"]?.jsonObject
      if (error != null) {
        val code = error["code"]?.jsonPrimitive?.intOrNull ?: 0
        val message = error["message"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
        Log.w(TAG, "engine $code: $message")
        NmAnswer(null, null, words(code, message, points.size))
      } else if (variants == null) {
        val found = parse(root, points.size)
        Log.i(TAG, "$from ${spec.profile}: ${found?.size ?: 0} routes in ${SystemClock.elapsedRealtime() - started} ms " +
            found?.joinToString { r -> String.format(java.util.Locale.US, "%.1f km/%d min", r.distanceM / 1000, r.timeMs / 60000) })
        NmAnswer(found, text, if (found == null) "risposta del motore senza percorsi" else null)
      } else {
        // the variants: none is an answer, not an error. The server answers with openrouteservice's
        // alternatives: the best one (already on screen) left out
        val all = parse(root, points.size) ?: emptyList()
        // the server's are openrouteservice's alternatives (up to 1.4 times the time, no limit on the
        // km): only those within the factors of the request (the automatic ones never beyond 1.20×)
        val found = if (from == "server") serverVariants(all, variants) else all
        val alt = ((root["metadata"] as? JsonObject)?.get("navmaster") as? JsonObject)?.get("alternatives") as? JsonObject
        val why = (alt?.get("note") as? JsonPrimitive)?.takeIf { it.isString }?.content
        Log.i(TAG, "$from ${spec.profile}: ${found.size} variants in ${SystemClock.elapsedRealtime() - started} ms " +
            "(${(alt?.get("candidates") as? JsonPrimitive)?.contentOrNullSafe() ?: "-"}${why?.let { "; $it" } ?: ""}) " +
            found.joinToString { r -> String.format(java.util.Locale.US, "%s %.1f km/%d min", r.variant?.label ?: "?", r.distanceM / 1000, r.timeMs / 60000) })
        NmAnswer(found, text, why)
      }
    } catch (e: Exception) {
      val note = "risposta del motore non leggibile: $e"
      Log.w(TAG, note, e)
      NmAnswer(null, null, note)
    }
  }

  private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { content }.getOrNull()

  /** openrouteservice's error in words for the driver. */
  private fun words(code: Int, message: String, nPoints: Int): String = when (code) {
    2009 -> "nessun percorso tra i punti con le misure e le scelte del mezzo"
    2010 -> {
      val which = Regex("coordinate (\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
      val where = when (which) { 0 -> "dalla partenza"; nPoints - 1 -> "dalla destinazione"; null -> "da un punto"; else -> "dalla tappa $which" }
      "nessuna strada percorribile entro 400 m $where"
    }
    2004 -> "percorso troppo lungo per il calcolo sul tablet"
    else -> "errore del motore ($code): ${message.take(120)}"
  }

  companion object {
    private const val TAG = "NavMasterRoute"

    /** The server can give variants only as openrouteservice's alternatives: between two points, not far apart. */
    internal fun serverHasVariants(nPoints: Int, far: Boolean): Boolean = nPoints == 2 && !far

    /**
     * The server's alternatives as variants: [all] is its answer (the best route first, already on
     * screen), kept are those within [ask]'s time and km factors of the best one, in their order.
     */
    internal fun serverVariants(all: List<RouteResult>, ask: VariantAsk): List<RouteResult> {
      val best = all.firstOrNull() ?: return emptyList()
      if (best.timeMs <= 0 || best.distanceM <= 0) return emptyList()
      return all.drop(1).filter { r ->
        val t = r.timeMs.toDouble() / best.timeMs
        val k = r.distanceM / best.distanceM
        t <= ask.maxFactor && k <= ask.maxKmFactor
      }
    }

    /**
     * The request for the engine, or ([forServer]) for the NavMaster server, whose engine may be
     * older: there pass-through points and alternatives go as openrouteservice takes them.
     */
    internal fun request(
        points: List<DoubleArray>, s: RouteSpec, kinds: List<String>, alternates: Int, headings: List<Double?>?,
        variants: VariantAsk? = null, forServer: Boolean = false,
    ): JsonObject {
      val options = mutableMapOf<String, JsonElement>()
      val avoid = mutableListOf<JsonElement>()
      if (s.avoidTolls) avoid += JsonPrimitive("tollways")
      if (s.avoidFerries) avoid += JsonPrimitive("ferries")
      if (s.avoidMotorways) avoid += JsonPrimitive("highways")
      if (avoid.isNotEmpty()) options["avoid_features"] = JsonArray(avoid)
      // the vehicle's measures: for lorries and buses as openrouteservice takes them; for campers and
      // vans too (a NavMaster extension of the engine: the physical limits of the roads, height,
      // width, length, weight, axle load, apply to them, the bans for goods vehicles over 3.5 t do not)
      if (s.hgv) options["vehicle_type"] = JsonPrimitive(s.vehicleType.ifBlank { "hgv" })
      val r = mutableMapOf<String, JsonElement>()
      if (s.heightM > 0) r["height"] = JsonPrimitive(s.heightM)
      if (s.widthM > 0) r["width"] = JsonPrimitive(s.widthM)
      if (s.lengthM > 0) r["length"] = JsonPrimitive(s.lengthM)
      if (s.weightT > 0) r["weight"] = JsonPrimitive(s.weightT)
      if (s.axleLoadT > 0) r["axleload"] = JsonPrimitive(s.axleLoadT)
      if (s.hgv && s.hazmat) r["hazmat"] = JsonPrimitive(true)
      // NavMaster extensions of the engine (an older engine or server ignores them): the tunnel code
      // compared with each tunnel's ADR category, and the roads closed to goods polluting to water
      if (s.hgv && s.adrTunnelCode.isNotEmpty()) r["adr_tunnel_code"] = JsonPrimitive(s.adrTunnelCode)
      if (s.hgv && s.hazmatWater) r["hazmat_water"] = JsonPrimitive(true)
      if (r.isNotEmpty()) options["profile_params"] = JsonObject(mapOf("restrictions" to JsonObject(r)))
      if (s.avoidZones.isNotEmpty()) {
        // rings of [lon, lat] → GeoJSON MultiPolygon
        options["avoid_polygons"] = JsonObject(mapOf(
            "type" to JsonPrimitive("MultiPolygon"),
            "coordinates" to JsonArray(s.avoidZones.map { ring ->
              JsonArray(listOf(JsonArray(ring.map { p -> JsonArray(listOf(JsonPrimitive(p[0]), JsonPrimitive(p[1]))) })))
            }),
        ))
      }
      // NavMaster: the facts about the roads of the route (limits, lanes, signs, tolls), from the graph
      val nav = mutableMapOf<String, JsonElement>("roads" to JsonPrimitive(true))
      // NavMaster: «avoid» as a heavy penalty, not a ban (as TomTom's avoid): a road to avoid is used
      // only when there is no other way (the vehicle already on the motorway, an island, a pass
      // reached only through a toll), so the trip never fails nor starts on a road it cannot reach
      if (avoid.isNotEmpty()) nav["avoid_penalty"] = JsonPrimitive(true)
      // pass-through points (the driver going on along his road, or back onto the planned route): no
      // u-turn at them; point by point for the engine, so a stop among them stays a stop
      val passThrough = points.size > 2 && kinds.size == points.size && kinds.subList(1, kinds.size - 1).any { it == "via" }
      if (passThrough && !forServer) nav["pass_through"] = JsonArray(kinds.map { JsonPrimitive(it == "via") })
      // the engine's variants: any number of points, the best one not sent again (the app has it)
      if (variants != null && !forServer) nav["alternatives"] = JsonObject(buildMap {
        put("count", JsonPrimitive(variants.count.coerceIn(2, 6)))
        put("tier1_factor", JsonPrimitive(variants.tier1Factor))
        put("tier1_km_factor", JsonPrimitive(variants.tier1KmFactor))
        put("max_factor", JsonPrimitive(variants.maxFactor))
        put("max_km_factor", JsonPrimitive(variants.maxKmFactor))
        if (variants.allowFewerKm) put("allow_fewer_km", JsonPrimitive(true))
        variants.budgetMs?.let { put("budget_ms", JsonPrimitive(it)) }
        put("exclude_best", JsonPrimitive(true))
      })
      val o = mutableMapOf<String, JsonElement>(
          "coordinates" to JsonArray(points.map { JsonArray(listOf(JsonPrimitive(it[1]), JsonPrimitive(it[0]))) }),
          "preference" to JsonPrimitive(s.preference),
          "units" to JsonPrimitive("m"),
          "instructions" to JsonPrimitive(true),
          "language" to JsonPrimitive("it"),
          "maneuvers" to JsonPrimitive(true),
          "extra_info" to JsonArray(listOf(JsonPrimitive("tollways"), JsonPrimitive("waycategory"), JsonPrimitive("waytype"))),
          "geometry" to JsonPrimitive(true),
          "navmaster" to JsonObject(nav),
      )
      if (options.isNotEmpty()) o["options"] = JsonObject(options)
      // ORS bearings: the direction of travel at the start and at the points passed through ([] where none)
      if (headings != null && headings.size == points.size && headings.any { it != null }) {
        o["bearings"] = JsonArray(headings.map { h ->
          if (h == null || h.isNaN()) JsonArray(emptyList()) else JsonArray(listOf(JsonPrimitive(((h % 360) + 360) % 360), JsonPrimitive(45)))
        })
      }
      // the server's engine (maybe older): no u-turn at any intermediate point (openrouteservice's continue_straight)
      if (passThrough && forServer) o["continue_straight"] = JsonPrimitive(true)
      // the vehicle's top speed (openrouteservice accepts 80 km/h and more): only when it is below the
      // profile's own top speeds (lorries 85 km/h on motorways, cars 130), else it changes nothing and
      // would only make the search slower
      if (s.topSpeedKmh > 0 && s.topSpeedKmh < (if (s.hgv) 85.0 else 130.0)) o["maximum_speed"] = JsonPrimitive(s.topSpeedKmh.coerceAtLeast(80.0))
      // alternatives (openrouteservice's alternative_routes), between two points: the engine picks the
      // method, openrouteservice's plateau up to 60 km (its full search does not fit the tablet's memory
      // on longer trips, NmEngine's limit), beyond that and across borders routes through points to the
      // side of the trip (navmaster-engine LongAlternatives). The engine's own variants
      // (navmaster.alternatives, any number of points) take their place on the tablet
      if (alternates > 0 && points.size == 2 && (forServer || variants == null)) {
        o["alternative_routes"] = JsonObject(mapOf(
            "target_count" to JsonPrimitive((1 + alternates).coerceIn(2, 3)), "weight_factor" to JsonPrimitive(1.4), "share_factor" to JsonPrimitive(0.6),
        ))
      }
      return JsonObject(o)
    }

    /** The routes of an answer (GeoJSON), with the engine's variant of each one when it says it. */
    internal fun parse(root: JsonObject, nPoints: Int): List<RouteResult>? {
      val features = root["features"]?.jsonArray ?: return null
      val out = mutableListOf<RouteResult>()
      for ((n, f) in features.withIndex()) {
        val fo = f.jsonObject
        val coords = fo["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: continue
        if (coords.size < 2) continue
        val props = fo["properties"]?.jsonObject
        val summary = props?.get("summary")?.jsonObject
        val lat = DoubleArray(coords.size) { coords[it].jsonArray[1].jsonPrimitive.double }
        val lon = DoubleArray(coords.size) { coords[it].jsonArray[0].jsonPrimitive.double }
        val wp = props?.get("way_points")?.jsonArray?.map { it.jsonPrimitive.int }
        val idx = if (wp != null && wp.size == nPoints) wp.toIntArray() else intArrayOf(0, coords.size - 1)
        val variant = ((props?.get("navmaster") as? JsonObject)?.get("variant") as? JsonObject)?.let { variant(it) }
        out += RouteResult(lat, lon, idx, summary?.get("distance")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            ((summary?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(), variant, n)
      }
      return out.takeIf { it.isNotEmpty() }
    }

    private fun variant(v: JsonObject): VariantInfo {
      fun str(k: String) = (v[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
      fun num(k: String) = (v[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
      val refs = (v["refs"] as? JsonArray)?.mapNotNull { e ->
        val r = e as? JsonObject ?: return@mapNotNull null
        val ref = (r["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        ref to ((r["km"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
      } ?: emptyList()
      return VariantInfo(
          rank = (v["rank"] as? JsonPrimitive)?.intOrNull ?: 0,
          tier = (v["tier"] as? JsonPrimitive)?.intOrNull ?: 0,
          source = str("source"), label = str("label"), diff = str("diff"), signature = str("signature"), refs = refs,
          extraS = num("extra_s"), extraM = num("extra_m"),
          longer = (v["longer"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }
  }
}
