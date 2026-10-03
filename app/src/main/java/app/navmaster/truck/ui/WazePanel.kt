package app.navmaster.truck.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The traffic and the reports of Waze, as Waze itself shows them: its official live map for
 * embedding (embed.waze.com, free, made to be put inside other pages and apps), centred where the
 * driver is. Jams in colour, police, accidents, hazards, closures, as on waze.com.
 *
 * It is a view of Waze, not data for NavMaster: the reports are not read by the app (the data of
 * the Waze live map is protected and may not be taken automatically), so they are seen here and
 * not said by the voice.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WazePanel(lat: Double?, lon: Double?, onClose: () -> Unit, modifier: Modifier = Modifier) {
  // the place is fixed when the panel opens (the map of Waze is then moved by hand)
  val url = remember {
    val la = lat ?: 41.9
    val lo = lon ?: 12.5
    val z = if (lat != null) 13 else 6
    String.format(java.util.Locale.ROOT, "https://embed.waze.com/it/iframe?zoom=%d&lat=%.5f&lon=%.5f&ct=livemap", z, la, lo)
  }
  Column(
      modifier.shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Nm.PanelSolid),
  ) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Traffico e segnalazioni Waze", color = Nm.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Caption("Mappa ufficiale di Waze · si muove con le dita", size = 12)
      }
      Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Close, "Chiudi", tint = Nm.Text)
      }
    }
    Spacer(Modifier.size(2.dp))
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(url)
          }
        },
        onRelease = { it.destroy() },
    )
  }
}
