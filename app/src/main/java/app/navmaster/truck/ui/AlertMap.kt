package app.navmaster.truck.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.navmaster.truck.limits.AlertGroup
import app.navmaster.truck.limits.AlertMapData
import app.navmaster.truck.limits.AlertPoint
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.any
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.linear
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.step
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/*
 * The alerts on the map, like on the dedicated navigators (TomTom GO): always there, not only while
 * driving. From far away the cameras are grouped in a bubble with their number (MapLibre groups the
 * points itself, nothing is redone here when the map moves); from closer every camera has its icon,
 * and where they crowd the most important ones win (fixed cameras and Tutor first); the other
 * alerts (ZTL, zone ambientali, controlli camion, dossi, pericoli) come from close by. A touch on an
 * icon opens its card, a touch on a bubble brings the map closer.
 */

private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

/** The icon of a group: a rounded badge of the group's colour with a white sign, a white border and a soft shadow. */
private fun alertIcon(g: AlertGroup): BitmapPainter {
  val s = 96
  val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
  val c = android.graphics.Canvas(bmp)
  val p = Paint(Paint.ANTI_ALIAS_FLAG)
  val triangle = g == AlertGroup.PERICOLO || g == AlertGroup.DOSSO
  if (triangle) {
    // a warning sign: white triangle with a red border
    fun tri(inset: Float, dy: Float) = Path().apply {
      moveTo(s / 2f, 6f + inset + dy)
      lineTo(s - 6f - inset * 1.15f, s - 10f - inset * 0.6f + dy)
      lineTo(6f + inset * 1.15f, s - 10f - inset * 0.6f + dy)
      close()
    }
    p.color = 0x55000000
    c.drawPath(tri(0f, 3f), p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawPath(tri(0f, 0f), p)
    p.color = 0xFFD32F2F.toInt()
    c.drawPath(tri(5f, 0f), p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawPath(tri(16f, 0f), p)
    p.color = 0xFF111111.toInt()
    if (g == AlertGroup.PERICOLO) {
      p.textSize = 40f
      p.textAlign = Paint.Align.CENTER
      p.typeface = Typeface.DEFAULT_BOLD
      c.drawText("!", s / 2f, s - 24f, p)
    } else {
      // a bump on the road
      p.style = Paint.Style.FILL
      c.drawArc(RectF(s / 2f - 17f, s - 42f, s / 2f + 17f, s - 14f), 180f, 180f, true, p)
      c.drawRect(s / 2f - 26f, s - 28f, s / 2f + 26f, s - 24f, p)
    }
    return BitmapPainter(bmp.asImageBitmap())
  }
  val r = RectF(6f, 6f, s - 6f, s - 6f)
  p.color = 0x55000000
  c.drawRoundRect(RectF(r.left + 2f, r.top + 4f, r.right + 2f, r.bottom + 4f), 22f, 22f, p)
  p.color = 0xFFFFFFFF.toInt()
  c.drawRoundRect(r, 22f, 22f, p)
  p.color = g.color.toInt()
  c.drawRoundRect(RectF(r.left + 6f, r.top + 6f, r.right - 6f, r.bottom - 6f), 17f, 17f, p)
  val ink = if (g == AlertGroup.MOBILE) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
  val cx = s / 2f
  val cy = s / 2f
  fun text(t: String, size: Float) {
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = ink
      textSize = size
      textAlign = Paint.Align.CENTER
      typeface = Typeface.DEFAULT_BOLD
    }
    val fm = tp.fontMetrics
    c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
  }
  fun camera() {
    // a camera: the body, the lens, the stand
    val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    c.drawRoundRect(RectF(cx - 22f, cy - 14f, cx + 16f, cy + 10f), 6f, 6f, q)
    c.drawPath(Path().apply { moveTo(cx + 16f, cy - 6f); lineTo(cx + 26f, cy - 12f); lineTo(cx + 26f, cy + 8f); lineTo(cx + 16f, cy + 2f); close() }, q)
    c.drawRect(cx - 5f, cy + 10f, cx + 1f, cy + 22f, q)
    q.color = g.color.toInt()
    c.drawCircle(cx - 3f, cy - 2f, 7f, q)
  }
  when (g) {
    AlertGroup.FISSO, AlertGroup.MOBILE -> camera()
    AlertGroup.TUTOR -> text("T", 46f)
    AlertGroup.SEMAFORO -> {
      // a traffic light, red lit
      val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }
      c.drawRoundRect(RectF(cx - 13f, cy - 30f, cx + 13f, cy + 30f), 9f, 9f, q)
      q.color = 0xFFFF5252.toInt(); c.drawCircle(cx, cy - 17f, 7f, q)
      q.color = 0xFF5D4037.toInt(); c.drawCircle(cx, cy, 7f, q)
      q.color = 0xFF2E4D32.toInt(); c.drawCircle(cx, cy + 17f, 7f, q)
    }
    AlertGroup.ZTL -> text("ZTL", 28f)
    AlertGroup.LEZ -> text("ECO", 26f)
    AlertGroup.CAMION -> {
      // a lorry: the box, the cab, the wheels
      val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
      c.drawRect(cx - 26f, cy - 14f, cx + 6f, cy + 10f, q)
      c.drawPath(Path().apply { moveTo(cx + 8f, cy - 6f); lineTo(cx + 20f, cy - 6f); lineTo(cx + 27f, cy + 3f); lineTo(cx + 27f, cy + 10f); lineTo(cx + 8f, cy + 10f); close() }, q)
      c.drawCircle(cx - 16f, cy + 14f, 6f, q)
      c.drawCircle(cx + 18f, cy + 14f, 6f, q)
    }
    AlertGroup.CINTURE -> {
      // a seat belt across a person
      val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
      c.drawCircle(cx, cy - 18f, 8f, q)
      c.drawRoundRect(RectF(cx - 15f, cy - 7f, cx + 15f, cy + 26f), 8f, 8f, q)
      q.color = g.color.toInt()
      q.strokeWidth = 6f
      c.drawLine(cx - 13f, cy - 5f, cx + 13f, cy + 22f, q)
    }
    else -> text("!", 44f)
  }
  return BitmapPainter(bmp.asImageBitmap())
}

/**
 * The layers of the alerts. [layer] null: nothing (none on the tablet, or the driver turned them
 * off). [onPick] opens the card of an alert, [onCluster] brings the map closer to a bubble.
 */
@Composable
@MaplibreComposable
fun AlertLayers(layer: AlertMapData.Layer?, onPick: (AlertPoint) -> Unit, onCluster: (Position) -> Unit) {
  val icons = remember { AlertGroup.entries.associateWith { alertIcon(it) } }
  val size = DpSize(26.dp, 26.dp)
  val points = layer?.points.orEmpty()
  // one data object per file: the map is handed a new one only when the alerts change
  val clusteredData = remember(layer?.clusteredUri) { layer?.clusteredUri?.let { GeoJsonData.Uri(it) } ?: GeoJsonData.JsonString(EMPTY) }
  val plainData = remember(layer?.plainUri) { layer?.plainUri?.let { GeoJsonData.Uri(it) } ?: GeoJsonData.JsonString(EMPTY) }
  val clustered = rememberGeoJsonSource(clusteredData, GeoJsonOptions(cluster = true, clusterRadius = 44, clusterMaxZoom = 10))
  val plain = rememberGeoJsonSource(plainData, GeoJsonOptions())

  // the expressions made once (while driving the map is recomposed at every frame of the arrow)
  val iconExpr = remember(icons) {
    switch(
        feature.get("g").asString(),
        *AlertGroup.entries.map { g -> case(g.id, image(icons.getValue(g), size)) }.toTypedArray(),
        fallback = image(icons.getValue(AlertGroup.FISSO), size),
    )
  }
  val iconSize = remember { interpolate(linear(), zoom(), 6 to const(0.6f), 11 to const(0.8f), 15 to const(1.0f)) }
  // the most important first where icons crowd; from zoom 14 every one, even overlapping
  val overlap = remember { step(zoom(), const(false), 14 to const(true)) }
  // the speed under the icon from zoom 13
  val speedText = remember { step(zoom(), const(""), 13 to feature.get("v").asString()) }
  val isCluster = remember { feature.has("point_count") }
  val notCluster = remember { !feature.has("point_count") }
  val sortKey = remember { feature.get("p").asNumber() }
  val clusterRadius = remember { step(feature.get("point_count").asNumber(), const(13.dp), 10 to const(16.dp), 50 to const(19.dp), 200 to const(23.dp)) }
  val font = remember { const(listOf("NotoSansMedium")) }
  val nearFilter = remember { any(*AlertGroup.entries.filter { !it.cluster && it.minZoom <= 11f }.map { g -> feature.get("g").asString().eq(const(g.id)) }.toTypedArray()) }
  val closeFilter = remember { any(*AlertGroup.entries.filter { !it.cluster && it.minZoom > 11f }.map { g -> feature.get("g").asString().eq(const(g.id)) }.toTypedArray()) }
  val pickNow = androidx.compose.runtime.rememberUpdatedState(onPick)
  val clusterNow = androidx.compose.runtime.rememberUpdatedState(onCluster)
  val pick: FeaturesClickHandler = remember(points) {
    { features ->
      val i = features.firstNotNullOfOrNull { f -> f.properties?.get("i")?.jsonPrimitive?.intOrNull }
      val p = i?.let { points.getOrNull(it) }
      if (p != null) {
        pickNow.value(p)
        ClickResult.Consume
      } else ClickResult.Pass
    }
  }
  val zoomIn: FeaturesClickHandler = remember {
    { features ->
      val at = features.firstNotNullOfOrNull { f -> (f.geometry as? Point)?.coordinates }
      if (at != null) {
        clusterNow.value(at)
        ClickResult.Consume
      } else ClickResult.Pass
    }
  }

  // the bubbles: how many cameras there are around, from far away
  CircleLayer(
      id = "nm-alert-cluster",
      source = clustered,
      minZoom = 6f,
      filter = isCluster,
      color = const(Color(0xE6B71C1C)),
      radius = clusterRadius,
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
      onClick = zoomIn,
  )
  SymbolLayer(
      id = "nm-alert-cluster-count",
      source = clustered,
      minZoom = 6f,
      filter = isCluster,
      textField = feature.get("point_count_abbreviated").asString(),
      textFont = font,
      textSize = const(13.sp),
      textColor = const(Color.White),
      textAllowOverlap = const(true),
      textIgnorePlacement = const(true),
  )
  // the cameras one by one
  SymbolLayer(
      id = "nm-alert-cameras",
      source = clustered,
      minZoom = 6f,
      filter = notCluster,
      sortKey = sortKey,
      iconImage = iconExpr,
      iconSize = iconSize,
      iconAllowOverlap = overlap,
      textField = speedText,
      textFont = font,
      textSize = const(12.sp),
      textColor = const(Color(0xFF111111)),
      textHaloColor = const(Color.White),
      textHaloWidth = const(2.dp),
      textAnchor = const(SymbolAnchor.Top),
      textOffset = offset(0.em, 1.1.em),
      textOptional = const(true),
      onClick = pick,
  )
  // the other alerts, from close by only
  for ((id, minZ, filter) in listOf(Triple("nm-alert-near", 10f, nearFilter), Triple("nm-alert-close", 12f, closeFilter))) {
    SymbolLayer(
        id = id,
        source = plain,
        minZoom = minZ,
        filter = filter,
        sortKey = sortKey,
        iconImage = iconExpr,
        iconSize = iconSize,
        iconAllowOverlap = overlap,
        onClick = pick,
    )
  }
}

/** The card of an alert touched on the map: what it is, the speed it checks, the road, the direction, the check, the sources. */
@Composable
fun AlertCard(p: AlertPoint, distanceM: Double?, modifier: Modifier = Modifier, onClose: () -> Unit) {
  Column(
      modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(Nm.Panel)
          .border(1.dp, Nm.Line, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(p.group.color)), contentAlignment = Alignment.Center) {
        Text(if (p.speed > 0) app.navmaster.truck.core.SpeedUnit.show(p.speed).toString() else "!", color = if (p.group == AlertGroup.MOBILE) Color(0xFF111111) else Color.White,
            fontSize = 15.sp, fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(p.title, color = Nm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val sub = listOfNotNull(p.group.label, distanceM?.let { formatDistance(it) + " da te" }, p.country).joinToString(" · ")
        Text(sub, color = Nm.Muted, fontSize = 13.sp)
      }
      Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Text("✕", color = Nm.Muted, fontSize = 20.sp)
      }
    }
    Spacer(Modifier.size(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
      for ((k, v) in p.details()) {
        Row {
          Text(k, color = Nm.Muted, fontSize = 14.sp, modifier = Modifier.width(92.dp))
          Text(v, color = Nm.Text, fontSize = 14.sp)
        }
      }
    }
  }
}

private fun formatDistance(m: Double): String =
    if (m < 950) "${(m / 10).toInt() * 10} m" else if (m < 9500) String.format(java.util.Locale.ITALY, "%.1f km", m / 1000) else "${(m / 1000).toInt()} km"
