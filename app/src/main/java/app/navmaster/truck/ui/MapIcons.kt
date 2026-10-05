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
  val json = androidx.compose.runtime.remember(at) { points(at) }
  val src = rememberJsonSource(json)
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
  PinLayer("nm-pin-camera", androidx.compose.runtime.remember(cameras) { cameras.map { it.lat to it.lon } }, "📷", 0xFFD32F2F)
  // grouped once per change of the events, not at every position
  val byKind = androidx.compose.runtime.remember(live) { LiveKind.entries.associateWith { k -> live.filter { it.e.kind == k }.map(where) } }
  for (k in LiveKind.entries) {
    PinLayer("nm-pin-${k.name.lowercase()}", byKind[k].orEmpty(), k.icon, k.color)
  }
}

/** A pin painted here, with the ratio of its sides (a pin with a tag is wider). */
private class TagPin(val painter: BitmapPainter, val widthFactor: Float)

/**
 * A pin like [pin] with a coloured tag on its right ("Inizio pedaggio"); the picture is symmetric
 * so that the tip of the pin stays at the bottom centre, where the map puts the spot.
 */
private fun tagPin(ring: Int, tag: String?, glyph: (android.graphics.Canvas, Float, Float, Float) -> Unit): TagPin {
  val pinW = 132f
  val h = 162
  val r = pinW / 2f - 4f
  val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 44f
    typeface = Typeface.DEFAULT_BOLD
    color = 0xFFFFFFFF.toInt()
  }
  val tagW = tag?.let { tp.measureText(it) + 32f } ?: 0f
  val half = if (tag == null) pinW / 2f else pinW / 2f + 6f + tagW
  val w = (half * 2f).toInt()
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val c = android.graphics.Canvas(bmp)
  val p = Paint(Paint.ANTI_ALIAS_FLAG)
  val cx = w / 2f
  val cy = r + 4f
  if (tag != null) {
    val top = cy - 30f
    p.color = 0x55000000
    c.drawRoundRect(cx + r - 6f + 3f, top + 4f, cx + r + 6f + tagW + 3f, top + 64f, 18f, 18f, p)
    p.color = ring
    c.drawRoundRect(cx + r - 6f, top, cx + r + 6f + tagW, top + 60f, 18f, 18f, p)
    val fm = tp.fontMetrics
    c.drawText(tag, cx + r + 16f, top + 30f - (fm.ascent + fm.descent) / 2f, tp)
  }
  p.color = 0x55000000
  c.drawCircle(cx + 3f, cy + 3f, r, p)
  p.color = ring
  c.drawPath(Path().apply {
    moveTo(cx - r * 0.45f, r * 1.7f)
    lineTo(cx + r * 0.45f, r * 1.7f)
    lineTo(cx, h - 2f)
    close()
  }, p)
  c.drawCircle(cx, cy, r, p)
  p.color = 0xFFFFFFFF.toInt()
  c.drawCircle(cx, cy, r * 0.80f, p)
  glyph(c, cx, cy, r * 0.80f)
  return TagPin(BitmapPainter(bmp.asImageBitmap()), w / pinW)
}

/** The euro sign of the toll. */
private fun drawEuro(c: android.graphics.Canvas, cx: Float, cy: Float, r: Float) {
  val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = r * 1.25f
    textAlign = Paint.Align.CENTER
    typeface = Typeface.DEFAULT_BOLD
    color = 0xFF111111.toInt()
  }
  val fm = p.fontMetrics
  c.drawText("€", cx, cy - (fm.ascent + fm.descent) / 2f, p)
}

/** A tunnel mouth: a dark arch in the rock with the road going in. */
private fun drawTunnel(c: android.graphics.Canvas, cx: Float, cy: Float, r: Float) {
  val p = Paint(Paint.ANTI_ALIAS_FLAG)
  // the rock
  p.color = 0xFF7A6A58.toInt()
  c.drawCircle(cx, cy, r * 0.82f, p)
  // the mouth: a half circle on a rectangle
  val mw = r * 0.55f
  val top = cy - r * 0.30f
  val bottom = cy + r * 0.62f
  p.color = 0xFF15191E.toInt()
  c.drawArc(cx - mw, top - mw, cx + mw, top + mw, 180f, 180f, true, p)
  c.drawRect(cx - mw, top, cx + mw, bottom, p)
  // the road
  p.color = 0xFFFFFFFF.toInt()
  p.strokeWidth = r * 0.10f
  p.strokeCap = Paint.Cap.ROUND
  c.drawLine(cx, bottom - r * 0.05f, cx, bottom - r * 0.22f, p)
  c.drawLine(cx, top + r * 0.18f, cx, top + r * 0.32f, p)
}

@Composable
@MaplibreComposable
private fun TagPinLayer(id: String, at: List<Pair<Double, Double>>, pin: TagPin, overlap: Boolean) {
  val json = remember(at) { points(at) }
  val src = rememberJsonSource(json)
  SymbolLayer(
      id = id,
      source = src,
      iconImage = image(pin.painter, DpSize((38f * pin.widthFactor).dp, 47.dp), alpha = 1f),
      iconAnchor = const(SymbolAnchor.Bottom),
      iconAllowOverlap = const(overlap),
      iconIgnorePlacement = const(overlap),
  )
}

/**
 * On the route shown before departure: where the toll starts and ends (a pin with "Inizio
 * pedaggio" / "Fine pedaggio") and the tunnels (darker stretches of the route, a pin at the mouth
 * of those longer than 300 m; where they crowd at low zoom the map shows only some of them).
 */
@Composable
@MaplibreComposable
fun RouteFeaturePins(a: app.navmaster.truck.routing.RouteAnalysis) {
  val tolls = remember(a) { a.tollStretches }
  val tunnels = remember(a) { a.tunnels }
  val tunnelJson = remember(a) {
    if (tunnels.isEmpty()) null
    else buildString {
      append("""{"type":"FeatureCollection","features":[""")
      for ((k, t) in tunnels.withIndex()) {
        if (k > 0) append(',')
        append("""{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
        val pts = ArrayList<uniffi.ferrostar.GeographicCoordinate>()
        pts += a.pointAt(t.startM)
        for (i in a.route.geometry.indices) if (a.cum[i] > t.startM && a.cum[i] < t.endM) pts += a.route.geometry[i]
        pts += a.pointAt(t.endM)
        pts.forEachIndexed { i, p -> if (i > 0) append(','); append('[').append(p.lng).append(',').append(p.lat).append(']') }
        append("""]},"properties":{}}""")
      }
      append("]}")
    }
  }
  if (tunnelJson != null) NmLine(tunnelJson, "nm-tunnels", androidx.compose.ui.graphics.Color(0xFF2B3138), 7f, 2f,
      androidx.compose.ui.graphics.Color(0xFFB0B8C0))
  val startPin = remember { tagPin(0xFFF59F00.toInt(), "Inizio pedaggio", ::drawEuro) }
  val endPin = remember { tagPin(0xFF2E7D32.toInt(), "Fine pedaggio", ::drawEuro) }
  val tunnelPin = remember { tagPin(0xFF455A64.toInt(), null, ::drawTunnel) }
  TagPinLayer("nm-pin-tunnel", remember(a) { tunnels.filter { it.length >= 300 }.map { a.pointAt(it.startM).let { p -> p.lat to p.lng } } }, tunnelPin, false)
  TagPinLayer("nm-pin-toll-end", remember(a) { tolls.map { a.pointAt(it.endM).let { p -> p.lat to p.lng } } }, endPin, true)
  TagPinLayer("nm-pin-toll-start", remember(a) { tolls.map { a.pointAt(it.startM).let { p -> p.lat to p.lng } } }, startPin, true)
}
