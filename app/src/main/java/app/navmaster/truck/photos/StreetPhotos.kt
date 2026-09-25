package app.navmaster.truck.photos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.navmaster.truck.core.Geo
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A street-level photo near a point of the route. */
data class StreetPhoto(
    val thumbUrl: String,
    val fullUrl: String,
    val source: String,
    val date: String?,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double?,
    val distanceM: Double,
)

/**
 * Street-level photos of a difficult point, so the driver sees what the road looks like before
 * getting there. They come from free, open collections that need no contract: Panoramax (open
 * street photos, no key), KartaView (OpenStreetCam) and, if the driver adds a free token in the
 * settings, Mapillary (the largest). Google Street View cannot be shown inside another app for free,
 * so it opens in the Google Maps app on the exact spot and direction.
 */
object StreetPhotos {
  private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun near(lat: Double, lon: Double, heading: Double?, mapillaryToken: String): List<StreetPhoto> =
      withContext(Dispatchers.IO) {
        coroutineScope {
          val jobs = listOf(
              async { safe { panoramax(lat, lon) } },
              async { safe { kartaview(lat, lon) } },
              async { if (mapillaryToken.isNotBlank()) safe { mapillary(lat, lon, mapillaryToken) } else emptyList() },
          )
          jobs.awaitAll().flatten()
              // closest first, and facing the same way as the vehicle
              .sortedBy { p -> p.distanceM + (if (heading != null && p.headingDeg != null) abs(Geo.angleDiff(heading, p.headingDeg)) / 6 else 15.0) }
              .take(12)
        }
      }

  private inline fun safe(block: () -> List<StreetPhoto>): List<StreetPhoto> =
      try {
        block()
      } catch (e: Exception) {
        Log.w("NavMasterPhotos", "photos: $e")
        emptyList()
      }

  private fun get(url: String): String? =
      client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/1.0 (truck navigation)").build()).execute().use {
        if (it.isSuccessful) it.body.string() else null
      }

  private fun box(lat: Double, lon: Double, m: Double): DoubleArray {
    val dLat = m / 110540.0
    val dLon = m / (111320.0 * cos(Math.toRadians(lat)))
    return doubleArrayOf(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
  }

  private fun panoramax(lat: Double, lon: Double): List<StreetPhoto> {
    val b = box(lat, lon, 60.0)
    val text = get("https://api.panoramax.xyz/api/search?bbox=${b[0]},${b[1]},${b[2]},${b[3]}&limit=12") ?: return emptyList()
    val root = json.parseToJsonElement(text).jsonObject
    return root["features"]?.jsonArray?.mapNotNull { f ->
      val o = f.jsonObject
      val assets = o["assets"]?.jsonObject ?: return@mapNotNull null
      val thumb = assets["thumb"]?.jsonObject?.get("href")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      val full = assets["sd"]?.jsonObject?.get("href")?.jsonPrimitive?.contentOrNull ?: thumb
      val c = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
      val plon = c[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
      val plat = c[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
      val props = o["properties"]?.jsonObject
      StreetPhoto(thumb, full, "Panoramax", props?.s("datetime")?.take(10), plat, plon,
          props?.get("view:azimuth")?.jsonPrimitive?.doubleOrNull, Geo.dist(lat, lon, plat, plon))
    } ?: emptyList()
  }

  private fun kartaview(lat: Double, lon: Double): List<StreetPhoto> {
    val text = get("https://api.openstreetcam.org/2.0/photo/?lat=$lat&lng=$lon&radius=60&itemsPerPage=12&orderBy=distance") ?: return emptyList()
    val root = json.parseToJsonElement(text).jsonObject
    val data = root["result"]?.jsonObject?.get("data")?.jsonArray ?: return emptyList()
    return data.mapNotNull { el ->
      val o = el.jsonObject
      val thumb = o.s("fileurlTh") ?: o.s("fileurlProc") ?: return@mapNotNull null
      val full = o.s("fileurlProc") ?: o.s("fileurl") ?: thumb
      val plat = o.s("lat")?.toDoubleOrNull() ?: return@mapNotNull null
      val plon = o.s("lng")?.toDoubleOrNull() ?: return@mapNotNull null
      StreetPhoto(thumb, full, "KartaView", o.s("shotDate")?.take(10), plat, plon, o.s("heading")?.toDoubleOrNull(),
          Geo.dist(lat, lon, plat, plon))
    }
  }

  private fun mapillary(lat: Double, lon: Double, token: String): List<StreetPhoto> {
    val b = box(lat, lon, 50.0)
    val text = get("https://graph.mapillary.com/images?access_token=$token&fields=id,thumb_256_url,thumb_1024_url,captured_at,compass_angle,computed_geometry" +
        "&bbox=${b[0]},${b[1]},${b[2]},${b[3]}&limit=12") ?: return emptyList()
    val data = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: return emptyList()
    return data.mapNotNull { el ->
      val o = el.jsonObject
      val c = o["computed_geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
      val plon = c[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
      val plat = c[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
      val thumb = o.s("thumb_256_url") ?: return@mapNotNull null
      StreetPhoto(thumb, o.s("thumb_1024_url") ?: thumb, "Mapillary", null, plat, plon,
          o["compass_angle"]?.jsonPrimitive?.doubleOrNull, Geo.dist(lat, lon, plat, plon))
    }
  }

  private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

  /** Opens Google Street View (Google Maps app, or the browser) looking along the route. */
  fun openStreetView(context: Context, lat: Double, lon: Double, heading: Double?) {
    val h = (heading ?: 0.0).toInt()
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("google.streetview:cbll=$lat,$lon&cbp=0,$h,0,0,0")).setPackage("com.google.android.apps.maps")
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$lat,$lon&heading=$h"))
    app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(app)
    } catch (e: Exception) {
      runCatching { context.startActivity(web) }
    }
  }

  fun openMapillary(context: Context, lat: Double, lon: Double) {
    runCatching {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mapillary.com/app/?lat=$lat&lng=$lon&z=18")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  fun openPhoto(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }
}
