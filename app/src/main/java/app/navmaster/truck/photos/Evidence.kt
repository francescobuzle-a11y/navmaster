package app.navmaster.truck.photos

import android.util.Log
import app.navmaster.truck.core.Geo
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A road sign seen in the street photos (Mapillary's automatic sign detection). */
data class SignSighting(
    val type: String,
    val thumbUrl: String?,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val firstSeen: String?,
)

enum class Reliability(val label: String) {
  HIGH("Affidabilità alta"),
  MEDIUM("Affidabilità media"),
  LOW("Da verificare"),
}

/** Everything that says whether a mapped limit is real and up to date, with where to check it. */
data class EvidenceReport(
    val osmUrl: String?,
    val historyUrl: String?,
    val tags: List<Pair<String, String>>,
    val lastEdit: String?,
    val version: Int?,
    val signs: List<SignSighting>,
    val reliability: Reliability,
    val reasons: List<String>,
    val mapillaryUsed: Boolean,
)

/**
 * Checks a limit or a difficulty against its sources, online, when the driver opens it:
 *  - the OpenStreetMap object itself (current tags, when it was last edited, the "source" and
 *    "check_date" the mappers left), with links to see it and its history;
 *  - the road signs Mapillary detected in its street photos around the point (with the photo of the
 *    sign), when the driver added a free Mapillary token.
 * From these a simple judgement: high (value mapped from a survey or confirmed by a sign in the
 * photos), medium, or "to be checked".
 */
object Evidence {
  private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  /** Mapillary sign names for each limit kind. */
  private val SIGN_WORDS = mapOf(
      "maxheight" to listOf("maximum-height", "height-restriction", "height-limit"),
      "maxweight" to listOf("maximum-weight", "weight-limit", "maximum-gross-weight"),
      "maxwidth" to listOf("maximum-width", "width-restriction", "road-narrows"),
      "maxlength" to listOf("maximum-length"),
      "maxaxleload" to listOf("axle"),
      "hgv" to listOf("no-heavy-goods-vehicles", "no-trucks", "no-goods-vehicles"),
      "hazmat" to listOf("hazardous", "dangerous-goods"),
      "bus" to listOf("no-buses"),
  )

  private val USEFUL_TAGS = listOf(
      "maxheight", "maxheight:physical", "maxheight:signed", "maxweight", "maxweightrating", "maxwidth", "maxwidth:physical", "maxlength",
      "maxaxleload", "hgv", "hgv:conditional", "maxweight:conditional", "hazmat", "access", "motor_vehicle", "width", "lanes", "surface",
      "smoothness", "incline", "narrow", "source", "source:maxheight", "source:maxweight", "source:maxwidth", "check_date",
      "check_date:maxheight", "survey:date", "note", "fixme", "name", "ref",
  )

  suspend fun check(osm: String?, lat: Double, lon: Double, limitKind: String?, mapillaryToken: String): EvidenceReport =
      withContext(Dispatchers.IO) {
        coroutineScope {
          val osmJob = async { runCatching { osmObject(osm) }.getOrNull() }
          val signsJob = async {
            if (mapillaryToken.isBlank()) emptyList()
            else runCatching { mapillarySigns(lat, lon, limitKind, mapillaryToken) }.onFailure { Log.w("NavMasterEvidence", "signs: $it") }
                .getOrDefault(emptyList())
          }
          val o = osmJob.await()
          val signs = signsJob.await()
          judge(osm, o, signs, limitKind, mapillaryToken.isNotBlank())
        }
      }

  private data class OsmObj(val tags: Map<String, String>, val timestamp: String?, val version: Int?)

  private fun get(url: String): String? =
      client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/1.0 (truck navigation; limit check)").build()).execute().use {
        if (it.isSuccessful) it.body.string() else null
      }

  private fun osmObject(osm: String?): OsmObj? {
    val (type, id) = parseOsm(osm) ?: return null
    val text = get("https://api.openstreetmap.org/api/0.6/$type/$id.json") ?: return null
    val el = json.parseToJsonElement(text).jsonObject["elements"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val tags = (el["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" } ?: emptyMap()
    return OsmObj(tags, el["timestamp"]?.jsonPrimitive?.contentOrNull, el["version"]?.jsonPrimitive?.intOrNull)
  }

  fun parseOsm(osm: String?): Pair<String, Long>? {
    if (osm.isNullOrBlank()) return null
    val type = when (osm.first()) {
      'w' -> "way"
      'n' -> "node"
      'r' -> "relation"
      else -> return null
    }
    val id = osm.drop(1).toLongOrNull() ?: return null
    return type to id
  }

  private fun mapillarySigns(lat: Double, lon: Double, kind: String?, token: String): List<SignSighting> {
    val m = 70.0
    val dLat = m / 110540.0
    val dLon = m / (111320.0 * cos(Math.toRadians(lat)))
    val text = get("https://graph.mapillary.com/map_features?access_token=$token&fields=id,object_value,geometry,first_seen_at,images" +
        "&bbox=${lon - dLon},${lat - dLat},${lon + dLon},${lat + dLat}&limit=50") ?: return emptyList()
    val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: return emptyList()
    val words = kind?.let { SIGN_WORDS[it] }
    return data.mapNotNull { el ->
      val o = el.jsonObject
      val value = o["object_value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      // only road signs, and of the right kind when we know it
      if (!value.startsWith("regulatory--") && !value.startsWith("warning--") && !value.startsWith("complementary--")) return@mapNotNull null
      if (words != null && words.none { value.contains(it) }) return@mapNotNull null
      val c = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
      val plon = c[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
      val plat = c[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
      val imageId = (o["images"] as? JsonObject)?.get("data")?.let { it as? JsonArray }?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
      val thumb = imageId?.let { id ->
        runCatching {
          get("https://graph.mapillary.com/$id?access_token=$token&fields=thumb_1024_url")?.let { t ->
            json.parseToJsonElement(t).jsonObject["thumb_1024_url"]?.jsonPrimitive?.contentOrNull
          }
        }.getOrNull()
      }
      SignSighting(value, thumb, plat, plon, Geo.dist(lat, lon, plat, plon), o["first_seen_at"]?.jsonPrimitive?.contentOrNull?.take(10))
    }.sortedBy { it.distanceM }.take(6)
  }

  private fun judge(osm: String?, o: OsmObj?, signs: List<SignSighting>, kind: String?, mapillary: Boolean): EvidenceReport {
    val reasons = mutableListOf<String>()
    var score = 0
    val parsed = parseOsm(osm)
    if (o != null && kind != null && (o.tags[kind] != null || o.tags["$kind:physical"] != null)) {
      score += 2
      reasons += "Valore presente oggi su OpenStreetMap: ${kind}=${o.tags[kind] ?: o.tags["$kind:physical"]}"
    } else if (o != null && kind != null) {
      reasons += "Su OpenStreetMap oggi il valore non c'è più: forse è stato tolto (dati del tablet più vecchi)"
      score -= 1
    } else if (o != null) {
      score += 1
      reasons += "Strada presente su OpenStreetMap"
    } else if (parsed != null) {
      reasons += "OpenStreetMap non raggiungibile ora (serve la connessione)"
    }
    val sources = listOfNotNull(o?.tags?.get("source"), kind?.let { o?.tags?.get("source:$it") }).joinToString(" ").lowercase()
    if (sources.contains("survey") || sources.contains("sign") || sources.contains("local_knowledge") || o?.tags?.get("$kind:signed") == "yes") {
      score += 1
      reasons += "Rilevato sul posto dai mappatori (fonte: ${sources.ifBlank { "cartello" }})"
    }
    val check = o?.tags?.let { it["check_date:$kind"] ?: it["check_date"] ?: it["survey:date"] }
    val lastEdit = o?.timestamp?.take(10)
    val recent = (check ?: lastEdit)?.let { d -> runCatching { LocalDate.parse(d.take(10)) }.getOrNull() }
    if (recent != null) {
      val years = java.time.temporal.ChronoUnit.YEARS.between(recent, LocalDate.now())
      if (years <= 3) {
        score += 1
        reasons += if (check != null) "Verificato il $check" else "Modificato di recente ($lastEdit)"
      } else if (years >= 8) {
        score -= 1
        reasons += "Ultimo controllo ${check ?: lastEdit}: più di 8 anni fa"
      }
    }
    if (signs.isNotEmpty()) {
      score += 2
      reasons += "Cartello riconosciuto nelle foto stradali a ${signs.first().distanceM.toInt()} m" +
          (signs.first().firstSeen?.let { " (visto dal $it)" } ?: "")
    } else if (mapillary && kind != null) {
      reasons += "Nessun cartello di questo tipo riconosciuto nelle foto Mapillary vicine"
    } else if (!mapillary) {
      reasons += "Con un token Mapillary gratuito (Impostazioni › Foto stradali) si cerca anche la foto del cartello"
    }
    if (o?.tags?.get("fixme") != null) {
      score -= 1
      reasons += "I mappatori hanno lasciato un dubbio: ${o?.tags?.get("fixme")}"
    }
    val level = when {
      score >= 4 -> Reliability.HIGH
      score >= 2 -> Reliability.MEDIUM
      else -> Reliability.LOW
    }
    val tags = o?.tags?.filterKeys { it in USEFUL_TAGS }?.toList()?.sortedBy { USEFUL_TAGS.indexOf(it.first) } ?: emptyList()
    return EvidenceReport(
        osmUrl = parsed?.let { "https://www.openstreetmap.org/${it.first}/${it.second}" },
        historyUrl = parsed?.let { "https://www.openstreetmap.org/${it.first}/${it.second}/history" },
        tags = tags,
        lastEdit = lastEdit,
        version = o?.version,
        signs = signs,
        reliability = level,
        reasons = reasons,
        mapillaryUsed = mapillary,
    )
  }

  /** Where to tell the mappers that something is wrong (an OpenStreetMap note on the point). */
  fun noteUrl(lat: Double, lon: Double): String = "https://www.openstreetmap.org/note/new#map=19/$lat/$lon"

  @Suppress("unused")
  private fun parseTime(s: String?): OffsetDateTime? = s?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
}
