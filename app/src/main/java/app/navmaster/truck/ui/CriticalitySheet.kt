package app.navmaster.truck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Streetview
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.core.XY
import app.navmaster.truck.photos.StreetPhoto
import app.navmaster.truck.photos.StreetPhotos
import app.navmaster.truck.routing.Criticality
import app.navmaster.truck.routing.TurnCheck
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.GeographicCoordinate

/**
 * One difficulty of the route, to decide before leaving: what it is, how the vehicle goes through
 * it (swept path), what it looks like from above (satellite) and from the road (street photos,
 * Street View), and what to do: accept it, avoid it, or add a stop to pass elsewhere.
 */
@Composable
fun CriticalitySheet(
    c: Criticality,
    route: List<GeographicCoordinate>?,
    onAvoid: (() -> Unit)?,
    onAddStop: (() -> Unit)?,
    onClose: () -> Unit,
) {
  val context = LocalContext.current
  val settings by AppGraph.settings.settings.collectAsState()
  AdaptiveSheet(c.title, onClose, wide = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Badge(when (c.severity) {
        app.navmaster.truck.routing.Severity.CRITICAL -> "CRITICO"
        app.navmaster.truck.routing.Severity.WARN -> "ATTENZIONE"
        else -> "INFORMAZIONE"
      }, severityColor(c.severity))
      Spacer(Modifier.width(8.dp))
      Caption("${c.kind.icon} ${c.kind.label} · a ${Fmt.distanceText(c.startM)} dalla partenza")
    }
    // what is checked in the background, fetched once for all the tabs
    val hasSource = c.osm != null || c.limitKind != null
    val report by produceState<app.navmaster.truck.photos.EvidenceReport?>(null, c.id) {
      if (hasSource) value = app.navmaster.truck.photos.Evidence.check(c.osm, c.lat, c.lon, c.limitKind, settings.mapillaryToken)
    }
    val photos by produceState<List<StreetPhoto>?>(null, c.id) {
      value = StreetPhotos.near(c.lat, c.lon, c.headingDeg, settings.mapillaryToken)
    }
    val r = report
    if (r != null) {
      Spacer(Modifier.height(4.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(r.reliability.label, reliabilityColor(r.reliability))
        Spacer(Modifier.width(8.dp))
        Caption(if (r.signs.isNotEmpty()) "cartello visto nelle foto" else "fonte: OpenStreetMap", size = 12, lines = 1)
      }
    }
    Spacer(Modifier.height(6.dp))
    Caption(c.detail, color = Nm.Text, size = 16, lines = 8)
    if (c.estimated) Caption("Larghezze stimate dal tipo di strada: controlla con le immagini.", size = 13)

    // one tab at a time; the choices stay below, always visible
    val tabs = buildList {
      add("Dettaglio")
      if (hasSource) add("Fonti")
      add("Foto" + (photos?.size?.takeIf { it > 0 }?.let { " $it" } ?: ""))
      add("Satellite")
    }
    var tab by remember(c.id) { mutableStateOf(UiHints.take(tabs.size)) }
    Spacer(Modifier.height(8.dp))
    TabPills(tabs, tab) { tab = it }
    when (tabs.getOrNull(tab)?.substringBefore(' ')) {
      "Fonti" -> GroupCard("Fonti e affidabilità") {
        if (r == null) {
          Caption("Controllo la fonte su OpenStreetMap e i cartelli nelle foto…")
        } else {
          Badge(r.reliability.label, reliabilityColor(r.reliability))
          Spacer(Modifier.height(6.dp))
          for (reason in r.reasons) Caption("• $reason", color = Nm.Text, size = 14, lines = 3)
          if (r.signs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Caption("Il cartello nelle foto stradali (Mapillary)", size = 13)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              for (sg in r.signs) {
                Column(Modifier.width(220.dp).clickable { sg.thumbUrl?.let { StreetPhotos.openPhoto(context, it) } }) {
                  if (sg.thumbUrl != null) NetImage(sg.thumbUrl, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)))
                  Caption("${sg.type.substringAfter("--").substringBeforeLast("--").replace('-', ' ')} · ${sg.distanceM.toInt()} m" +
                      (sg.firstSeen?.let { " · dal $it" } ?: ""), size = 12, lines = 2)
                }
              }
            }
          }
          if (r.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Caption("Dati OpenStreetMap" + (r.lastEdit?.let { " (ultima modifica $it" + (r.version?.let { v -> ", versione $v" } ?: "") + ")" } ?: ""), size = 13)
            for ((k, v) in r.tags.take(12)) Caption("$k = $v", color = Nm.Text, size = 13, lines = 2)
          }
          Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            r.osmUrl?.let { u -> BigButton("OpenStreetMap", Modifier.weight(1f), style = BtnStyle.SECONDARY) { StreetPhotos.openPhoto(context, u) } }
            r.historyUrl?.let { u -> BigButton("Storico", Modifier.weight(1f), style = BtnStyle.SECONDARY) { StreetPhotos.openPhoto(context, u) } }
          }
          BigButton("Segnala un errore sulla mappa", Modifier.fillMaxWidth().padding(top = 8.dp), style = BtnStyle.GHOST) {
            StreetPhotos.openPhoto(context, app.navmaster.truck.photos.Evidence.noteUrl(c.lat, c.lon))
          }
          Caption("Il cartello sulla strada vale sempre più della mappa.", size = 12)
        }
      }
      "Foto" -> GroupCard("Foto dalla strada") {
        when {
          photos == null -> Caption("Cerco foto di questo punto…")
          photos!!.isEmpty() -> Caption("Nessuna foto libera vicina: apri Street View.")
          else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (p in photos!!) {
              Column(Modifier.width(220.dp).clickable { StreetPhotos.openPhoto(context, p.fullUrl) }) {
                NetImage(p.thumbUrl, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)))
                Caption("${p.source} · ${p.distanceM.toInt()} m" + (p.date?.let { " · $it" } ?: ""), size = 12, lines = 1)
              }
            }
          }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          BigButton("Street View", Modifier.weight(1f), Icons.Rounded.Streetview, BtnStyle.SECONDARY) {
            StreetPhotos.openStreetView(context, c.lat, c.lon, c.headingDeg)
          }
          BigButton("Mapillary", Modifier.weight(1f), style = BtnStyle.SECONDARY) { StreetPhotos.openMapillary(context, c.lat, c.lon) }
        }
      }
      "Satellite" -> GroupCard("Vista dal satellite") {
        SatelliteView(c.lat, c.lon, route, Modifier.fillMaxWidth().height(260.dp))
        Caption(MapStyles.ESRI_ATTRIBUTION + " · serve la connessione", size = 11)
      }
      else -> {
        val sc = c.scene
        if (sc != null) {
          GroupCard("Come passa il tuo mezzo") {
            SweptDiagram(sc, Modifier.fillMaxWidth().height(260.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
              Caption("━ ruote anteriori", color = Nm.Accent, size = 13)
              Caption("━ ruote posteriori", color = Nm.Red, size = 13)
              Caption("▭ ingombro", color = Color(0xCCFFFFFF), size = 13)
            }
            Caption("Raggio seguito ${sc.radiusM.toInt().takeIf { sc.radiusM < 999 } ?: "—"} m · raggio minimo del mezzo ${sc.vehicleMinRadiusM.toInt()} m", size = 13)
          }
        } else {
          // no drawing: the satellite picture tells more than words
          GroupCard("Il punto dall'alto") {
            SatelliteView(c.lat, c.lon, route, Modifier.fillMaxWidth().height(220.dp))
            Caption(MapStyles.ESRI_ATTRIBUTION, size = 11)
          }
        }
      }
    }

    Spacer(Modifier.height(10.dp))
    SectionHeader("Cosa vuoi fare?")
    if (onAvoid != null && c.avoidable) {
      BigButton("Evita questo punto e ricalcola", Modifier.fillMaxWidth(), Icons.Rounded.Block, BtnStyle.DANGER, onClick = onAvoid)
      Spacer(Modifier.height(8.dp))
    }
    if (onAddStop != null) {
      BigButton("Scegli io da dove passare (tappa)", Modifier.fillMaxWidth(), Icons.Rounded.AddLocationAlt, BtnStyle.SECONDARY, onClick = onAddStop)
      Spacer(Modifier.height(8.dp))
    }
    BigButton("Va bene, lo affronto", Modifier.fillMaxWidth(), Icons.Rounded.Check, onClick = onClose)
    Spacer(Modifier.height(20.dp))
  }
}

/** Satellite picture of the point with the route on it. */
@Composable
fun SatelliteView(lat: Double, lon: Double, route: List<GeographicCoordinate>?, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val style = MapStyles.satelliteUri(context)
  val camera = rememberCameraState(CameraPosition(target = Position(lon, lat), zoom = 17.6))
  Box(modifier.clip(RoundedCornerShape(16.dp))) {
    MaplibreMap(
        modifier = Modifier.fillMaxSize(),
        baseStyle = BaseStyle.Uri(style),
        cameraState = camera,
        options = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = true)),
    ) {
      if (route != null && route.size > 1) {
        val near = route.filter { kotlin.math.abs(it.lat - lat) < 0.02 && kotlin.math.abs(it.lng - lon) < 0.03 }
        if (near.size > 1) {
          val line = rememberGeoJsonSource(GeoJsonData.JsonString(
              """{"type":"Feature","geometry":{"type":"LineString","coordinates":[${near.joinToString(",") { "[${it.lng},${it.lat}]" }}]},"properties":{}}"""))
          LineLayer(id = "sat-route", source = line, color = const(Nm.Route), width = const(6.dp), opacity = const(0.85f))
        }
      }
      val pt = rememberGeoJsonSource(GeoJsonData.JsonString("""{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{}}"""))
      CircleLayer(id = "sat-point", source = pt, color = const(Nm.Amber), radius = const(9.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
    }
  }
}

/** The swept path of the vehicle, turned so that the vehicle drives upwards. */
@Composable
fun SweptDiagram(s: TurnCheck.Scene, modifier: Modifier = Modifier) {
  Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF3B4A3A))) {
    if (s.front.size < 2) return@Canvas
    val d0 = (s.front[minOf(8, s.front.size - 1)] - s.front[0]).unit()
    val ang = atan2(d0.x, d0.y) // rotate so that d0 points up
    fun rot(p: XY) = XY(p.x * cos(ang) - p.y * sin(ang), p.x * sin(ang) + p.y * cos(ang))
    val pts = (s.front + s.rear + s.outlines.flatten().flatten()).map { rot(it) }
    val minX = pts.minOf { it.x } - 6
    val maxX = pts.maxOf { it.x } + 6
    val minY = pts.minOf { it.y } - 6
    val maxY = pts.maxOf { it.y } + 6
    val scale = minOf(size.width / (maxX - minX), size.height / (maxY - minY)).toFloat()
    val ox = (size.width - (maxX - minX) * scale) / 2
    val oy = (size.height - (maxY - minY) * scale) / 2
    fun o(p: XY): Offset {
      val r = rot(p)
      return Offset((ox + (r.x - minX) * scale).toFloat(), (size.height - oy - (r.y - minY) * scale).toFloat())
    }
    // roads, asphalt grey at their width
    for ((line, width) in s.roads) {
      val path = Path()
      line.forEachIndexed { i, p -> val q = o(p); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
      drawPath(path, Color(0xFF5A6068), style = Stroke(width = (width * scale).toFloat(), cap = StrokeCap.Round))
      drawPath(path, Color(0x88FFFFFF), style = Stroke(width = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 12f))))
    }
    // vehicle outlines at a few moments
    for (shot in s.outlines) for (body in shot) {
      val path = Path()
      body.forEachIndexed { i, p -> val q = o(p); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
      path.close()
      drawPath(path, Color(0x33FFFFFF))
      drawPath(path, Color(0xAAFFFFFF), style = Stroke(2f))
    }
    fun line(list: List<XY>, c: Color) {
      val path = Path()
      list.forEachIndexed { i, p -> val q = o(p); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
      drawPath(path, c, style = Stroke(5f, cap = StrokeCap.Round))
    }
    line(s.front, Nm.Accent)
    line(s.rear, Nm.Red)
  }
}

private fun reliabilityColor(r: app.navmaster.truck.photos.Reliability): Color = when (r) {
  app.navmaster.truck.photos.Reliability.HIGH -> Nm.Accent
  app.navmaster.truck.photos.Reliability.MEDIUM -> Nm.Amber
  app.navmaster.truck.photos.Reliability.LOW -> Nm.Red
}

/** First tab of the next sheet, from the test intents (tools/preview.sh); 0 otherwise. */
object UiHints {
  @Volatile var tab = 0
  fun take(count: Int): Int { val t = tab; tab = 0; return t.coerceIn(0, (count - 1).coerceAtLeast(0)) }
}
