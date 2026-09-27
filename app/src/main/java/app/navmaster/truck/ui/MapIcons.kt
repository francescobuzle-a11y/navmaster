package app.navmaster.truck.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.navmaster.truck.limits.RouteLimit
import app.navmaster.truck.live.LiveKind
import app.navmaster.truck.live.RouteLiveEvent
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/**
 * A map pin drawn here: a white disc with a coloured ring and a small tail pointing at the spot,
 * with the sign of what is there (camera, police, accident…) inside.
 */
private fun pin(glyph: String, ring: Int): BitmapPainter {
  val w = 132
  val h = 162
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val c = android.graphics.Canvas(bmp)
  val p = Paint(Paint.ANTI_ALIAS_FLAG)
  val r = w / 2f - 4f
  // soft shadow
  p.color = 0x55000000
  c.drawCircle(w / 2f + 3f, r + 7f, r, p)
  // tail
  p.color = ring
  c.drawPath(Path().apply {
    moveTo(w / 2f - r * 0.45f, r * 1.7f)
    lineTo(w / 2f + r * 0.45f, r * 1.7f)
    lineTo(w / 2f, h - 2f)
    close()
  }, p)
  // ring and disc
  c.drawCircle(w / 2f, r + 4f, r, p)
  p.color = 0xFFFFFFFF.toInt()
  c.drawCircle(w / 2f, r + 4f, r * 0.80f, p)
  // the sign
  p.textSize = r * 0.95f
  p.textAlign = Paint.Align.CENTER
  p.typeface = Typeface.DEFAULT_BOLD
  p.color = 0xFF111111.toInt()
  val fm = p.fontMetrics
  c.drawText(glyph, w / 2f, r + 4f - (fm.ascent + fm.descent) / 2f, p)
  return BitmapPainter(bmp.asImageBitmap())
}

private fun points(list: List<Pair<Double, Double>>): String =
    """{"type":"FeatureCollection","features":[""" +
        list.joinToString(",") { """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.second},${it.first}]},"properties":{}}""" } +
        "]}"

@Composable
@MaplibreComposable
private fun PinLayer(id: String, at: List<Pair<Double, Double>>, glyph: String, ring: Long) {
  val painter = remember(glyph, ring) { pin(glyph, ring.toInt()) }
  val src = rememberGeoJsonSource(GeoJsonData.JsonString(points(at)))
  SymbolLayer(
      id = id,
      source = src,
      iconImage = image(painter, DpSize(38.dp, 47.dp), alpha = 1f),
      iconAnchor = const(SymbolAnchor.Bottom),
      iconAllowOverlap = const(true),
      iconIgnorePlacement = const(true),
  )
}

/**
 * Speed cameras and the events on the road (police, accidents, queues, roadworks…) as pins on the
 * map at their place, like on the dedicated navigators.
 */
@Composable
@MaplibreComposable
fun RoadPins(cameras: List<RouteLimit>, live: List<RouteLiveEvent>, where: (RouteLiveEvent) -> Pair<Double, Double>) {
  PinLayer("nm-pin-camera", cameras.map { it.lat to it.lon }, "📷", 0xFFD32F2F)
  for (k in LiveKind.entries) {
    PinLayer("nm-pin-${k.name.lowercase()}", live.filter { it.e.kind == k }.map(where), k.icon, k.color)
  }
}
