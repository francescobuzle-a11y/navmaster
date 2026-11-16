package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.nav.RouteOffer
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import uniffi.ferrostar.GeographicCoordinate

/** Seconds the offer stays on screen without an answer: then the route being driven stays. */
const val OFFER_SECONDS = 30

/**
 * The route offered while driving (another choice of tolls or motorways), aside: its overview on a
 * small map (the route being driven grey, the one offered blue), what changes, two answers. The
 * guidance goes on under it; without an answer the card goes away and the route stays as it is.
 */
@Composable
fun RouteOfferCard(o: RouteOffer, styleUri: String, onAccept: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, mapHeight: Int = 200) {
  var left by remember(o.askedAt, o.route != null) { mutableIntStateOf(OFFER_SECONDS) }
  LaunchedEffect(o.askedAt, o.route != null) {
    if (o.route == null && o.error == null) return@LaunchedEffect
    while (left > 0) {
      delay(1000)
      left--
    }
    onDismiss()
  }
  Panel(modifier, padding = 14.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("💶", fontSize = 26.sp)
      Spacer(Modifier.width(8.dp))
      Title("Percorso ${o.title}", size = 20)
    }
    Spacer(Modifier.height(8.dp))
    OfferMap(o, styleUri, Modifier.fillMaxWidth().height(mapHeight.dp))
    Spacer(Modifier.height(8.dp))
    when {
      o.error != null -> Caption(o.error, color = Nm.Text, size = 16)
      o.route == null -> Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Nm.Accent, modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(10.dp))
        Caption("Calcolo il percorso ${o.title}… la guida intanto prosegue.", color = Nm.Text, size = 16)
      }
      else -> {
        val min = (o.extraS / 60).roundToInt()
        Text(
            when {
              min >= 1 -> "+$min min"
              min <= -1 -> "−${-min} min"
              else -> "stessa ora"
            } + "  ·  " + (if (o.extraM >= 0) "+" else "−") + fmtNum(kotlin.math.abs(o.extraM) / 1000, 0) + " km",
            color = if (min >= 1) Nm.Amber else Nm.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Caption(tollLine(o), color = Nm.Text, size = 15, lines = 2)
      }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (o.route != null) BigButton("Usa questo", Modifier.weight(1f), Icons.Rounded.Check, onClick = onAccept)
      BigButton(if (o.route != null && left > 0) "Resta ($left s)" else "Resta così", Modifier.weight(1f), Icons.Rounded.Route, BtnStyle.SECONDARY,
          onClick = onDismiss)
    }
  }
}

private fun tollLine(o: RouteOffer): String {
  fun km(v: Double) = "${v.roundToInt()} km"
  val toll = when {
    o.tollKmNew < 0.5 && o.tollKmNow < 0.5 -> "Senza pedaggi, come ora"
    o.tollKmNew < 0.5 -> "Nessun pedaggio (ora ${km(o.tollKmNow)} a pagamento)"
    o.tollKmNow < 0.5 -> "${km(o.tollKmNew)} a pagamento (ora nessuno)"
    else -> "Pedaggio ${km(o.tollKmNew)} (ora ${km(o.tollKmNow)})"
  }
  val mw = if (kotlin.math.abs(o.motorwayKmNew - o.motorwayKmNow) >= 1) " · autostrada ${km(o.motorwayKmNew)} (ora ${km(o.motorwayKmNow)})" else ""
  return toll + mw
}

/** The two routes on a small map, the whole of both in sight. */
@Composable
private fun OfferMap(o: RouteOffer, styleUri: String, modifier: Modifier) {
  val all = o.current + (o.route?.geometry ?: emptyList())
  val first = o.current.firstOrNull() ?: o.route?.geometry?.firstOrNull()
  val camera = rememberCameraState(CameraPosition(target = Position(first?.lng ?: 10.0, first?.lat ?: 45.0), zoom = 8.0))
  LaunchedEffect(all.size, o.route != null) {
    if (all.size < 2) return@LaunchedEffect
    val box = BoundingBox(all.minOf { it.lng }, all.minOf { it.lat }, all.maxOf { it.lng }, all.maxOf { it.lat })
    // the map may not be ready at the first frame: tried again for a moment
    repeat(10) {
      if (runCatching { camera.animateTo(box, 0.0, 0.0, PaddingValues(28.dp)) }.isSuccess) return@LaunchedEffect
      delay(200)
    }
  }
  Box(modifier.clip(RoundedCornerShape(14.dp)).background(Nm.Bg)) {
    MaplibreMap(
        modifier = Modifier.fillMaxSize(),
        baseStyle = BaseStyle.Uri(styleUri),
        cameraState = camera,
        options = MapOptions(ornamentOptions = OrnamentOptions(isCompassEnabled = false, isScaleBarEnabled = false, isLogoEnabled = false,
            isAttributionEnabled = false)),
    ) {
      if (o.current.size > 1) {
        val now = rememberJsonSource(remember(o.current.size, o.current.first()) { line(o.current) })
        LineLayer(id = "offer-now-casing", source = now, color = const(Color(0xFF4B5563)), width = const(9.dp), opacity = const(0.9f))
        LineLayer(id = "offer-now", source = now, color = const(Color(0xFF9AA8B8)), width = const(6.dp), opacity = const(0.95f))
      }
      o.route?.geometry?.takeIf { it.size > 1 }?.let { g ->
        val next = rememberJsonSource(remember(g.size, g.first(), g.last()) { line(g) })
        LineLayer(id = "offer-new-casing", source = next, color = const(Nm.RouteBorder), width = const(10.dp))
        LineLayer(id = "offer-new", source = next, color = const(Nm.Route), width = const(6.dp))
      }
      first?.let { p ->
        val here = rememberJsonSource(remember(p) { """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lng},${p.lat}]},"properties":{}}""" })
        CircleLayer(id = "offer-here", source = here, color = const(Nm.Route), radius = const(7.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
      }
      (o.route?.geometry?.lastOrNull() ?: o.current.lastOrNull())?.let { p ->
        val end = rememberJsonSource(remember(p) { """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lng},${p.lat}]},"properties":{}}""" })
        CircleLayer(id = "offer-end", source = end, color = const(Nm.Red), radius = const(7.dp), strokeColor = const(Color.White), strokeWidth = const(3.dp))
      }
    }
    // the legend: which line is which
    Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF9AA8B8)))
      Text(" ora   ", color = Color.White, fontSize = 12.sp)
      Box(Modifier.size(10.dp).clip(CircleShape).background(Nm.Route))
      Text(" proposto", color = Color.White, fontSize = 12.sp)
    }
  }
}

private fun line(g: List<GeographicCoordinate>): String {
  // at most ~800 points: the overview needs no more (a 900 km route has ~25 000)
  val step = maxOf(1, g.size / 800)
  val pts = g.filterIndexed { i, _ -> i % step == 0 || i == g.size - 1 }
  return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[${pts.joinToString(",") { "[${it.lng},${it.lat}]" }}]},"properties":{}}"""
}
