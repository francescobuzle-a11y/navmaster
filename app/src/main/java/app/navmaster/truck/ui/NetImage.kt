package app.navmaster.truck.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** A picture from the network, kept in memory while the app runs. */
object ImageCache {
  private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
  private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
  }

  suspend fun load(url: String): Bitmap? =
      cache.get(url) ?: withContext(Dispatchers.IO) {
        try {
          client.newCall(Request.Builder().url(url).header("User-Agent", "NavMaster/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val bytes = r.body.bytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache.put(url, it) }
          }
        } catch (e: Exception) {
          null
        }
      }
}

@Composable
fun NetImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
  val bmp by produceState<Bitmap?>(null, url) { value = ImageCache.load(url) }
  Box(modifier.background(Nm.Raised), contentAlignment = Alignment.Center) {
    val b = bmp
    if (b == null) CircularProgressIndicator(color = Nm.Accent)
    else Image(b.asImageBitmap(), null, contentScale = contentScale, modifier = Modifier.matchParentSize())
  }
}
