package app.navmaster.truck.live

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The keys of the online services typed on the tablet (Settings › Chiavi dei servizi, behind the
 * owner's password): each one is tried on the service itself with one small request, so the owner
 * sees at once whether it works. One request per check, counted in the service's allowance.
 */
object KeyCheck {
  enum class Service(val label: String, val where: String) {
    TOMTOM("TomTom Traffic", "developer.tomtom.com"),
    HERE("HERE Traffic", "platform.here.com"),
    ORS("openrouteservice", "openrouteservice.org"),
    TRAFIKVERKET("Trafikverket (Svezia)", "api.trafikinfo.trafikverket.se"),
    MAPILLARY("Mapillary", "mapillary.com/developer"),
  }

  /** What the service answered: ok, refused (a wrong key or one not enabled for this), or unreachable. */
  data class Result(val ok: Boolean, val text: String)

  private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

  private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

  /** Blocking: call it off the main thread. */
  fun check(service: Service, key: String): Result {
    val k = key.trim()
    if (k.isBlank()) return Result(false, "Chiave vuota")
    val req = when (service) {
      // an incident tile over Rimini (a tile: the large allowance)
      Service.TOMTOM -> Request.Builder().url("https://api.tomtom.com/traffic/map/4/tile/incidents/12/2190/1494.pbf?key=${enc(k)}&t=-1").build()
      Service.HERE -> Request.Builder()
          .url("https://data.traffic.hereapi.com/v7/incidents?in=circle:44.06,12.57;r=2000&locationReferencing=shape&apiKey=${enc(k)}").build()
      // a short route in Rimini
      Service.ORS -> Request.Builder().url("https://api.openrouteservice.org/v2/directions/driving-car")
          .header("Authorization", k).header("Content-Type", "application/json")
          .post("""{"coordinates":[[12.5695,44.0575],[12.5800,44.0620]]}""".toRequestBody("application/json".toMediaType())).build()
      Service.TRAFIKVERKET -> Request.Builder().url("https://api.trafikinfo.trafikverket.se/v2/data.json")
          .post("""<REQUEST><LOGIN authenticationkey="$k"/><QUERY objecttype="Situation" schemaversion="1.5" limit="1"/></REQUEST>"""
              .toRequestBody("text/xml".toMediaType())).build()
      Service.MAPILLARY -> Request.Builder()
          .url("https://graph.mapillary.com/images?access_token=${enc(k)}&fields=id&limit=1&bbox=12.56,44.05,12.58,44.07").build()
    }
    return try {
      client.newCall(req.newBuilder().header("User-Agent", "NavMaster/1.0 (key check)").build()).execute().use { r ->
        when {
          r.isSuccessful -> Result(true, "Chiave valida (risposta ${r.code})")
          r.code == 401 || r.code == 403 -> Result(false, "Chiave rifiutata (${r.code}): controlla la chiave e che il servizio sia abilitato")
          r.code == 429 -> Result(true, "Chiave valida, ma la quota di oggi è finita (429)")
          else -> Result(false, "Risposta ${r.code}: ${r.body.string().take(120)}")
        }
      }
    } catch (e: Exception) {
      Result(false, "Servizio non raggiungibile: ${e.javaClass.simpleName}")
    }
  }

  /** SHA-256 of [text], in hex: the owner's password is kept only this way. */
  fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
