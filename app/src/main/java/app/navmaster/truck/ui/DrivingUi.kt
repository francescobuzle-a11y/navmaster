package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.MoneyOff
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.nav.DriverPrompt
import app.navmaster.truck.poi.RoutePoi
import app.navmaster.truck.routing.Criticality
import app.navmaster.truck.settings.PoiCategories
import kotlinx.coroutines.delay

/** The question of the moment, in the middle of the screen with two big answers. */
@Composable
fun PromptCard(p: DriverPrompt, traveledM: Double, onRamp: (Boolean) -> Unit, onToll: (Boolean) -> Unit, onBreakGo: (RoutePoi) -> Unit,
               onDismiss: () -> Unit, onClosure: (Boolean) -> Unit = {}) {
  Box(Modifier.fillMaxSize().background(Color(0x55000000)), contentAlignment = Alignment.Center) {
    Panel(Modifier.widthIn(max = 620.dp).padding(18.dp), padding = 20.dp) {
      when (p) {
        is DriverPrompt.TightRamp -> {
          val d = (p.crit.startM - traveledM).coerceAtLeast(0.0)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⤴", fontSize = 34.sp)
            Spacer(Modifier.width(10.dp))
            Title("Svincolo stretto tra ${Fmt.distanceText(d)}", size = 24)
          }
          Caption(p.crit.detail, color = Nm.Text, size = 17, lines = 5)
          p.crit.scene?.let { SweptDiagram(it, Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp)) }
          Spacer(Modifier.height(12.dp))
          Title("Pensi di poterlo affrontare?", size = 20)
          Spacer(Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Sì, lo affronto", Modifier.weight(1f), Icons.Rounded.Check) { onRamp(true) }
            BigButton("No, uscita successiva", Modifier.weight(1.2f), Icons.Rounded.ExitToApp, BtnStyle.SECONDARY) { onRamp(false) }
          }
        }
        is DriverPrompt.TollChoice -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💶", fontSize = 34.sp)
            Spacer(Modifier.width(10.dp))
            Title("Pedaggio tra ${Fmt.distanceText(p.distanceToTollM)}", size = 24)
          }
          Caption("Senza pedaggio arrivi solo ${p.extraMin} min più tardi" +
              (if (p.extraKm > 0.3) " (+${fmtNum(p.extraKm, 1)} km)" else "") +
              " ed eviti ${p.tollKm.toInt()} km a pagamento. Valuta se la strada normale è adatta al mezzo.",
              color = Nm.Text, size = 17, lines = 5)
          Spacer(Modifier.height(14.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Evita il pedaggio", Modifier.weight(1f), Icons.Rounded.MoneyOff) { onToll(true) }
            BigButton("Resta in autostrada", Modifier.weight(1f), Icons.Rounded.Route, BtnStyle.SECONDARY) { onToll(false) }
          }
        }
        is DriverPrompt.Break -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☕", fontSize = 34.sp)
            Spacer(Modifier.width(10.dp))
            Title("Guidi da ${p.drivenMin / 60} h ${p.drivenMin % 60} min", size = 24)
          }
          Caption("Dopo 4 h 30 di guida servono 45 minuti di pausa (reg. CE 561/2006).", color = Nm.Text, size = 17)
          p.parking?.let { pk ->
            Spacer(Modifier.height(8.dp))
            PoiRow(pk, traveledM) {}
          }
          Spacer(Modifier.height(14.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val pk = p.parking
            if (pk != null) BigButton("Portami lì", Modifier.weight(1f), Icons.Rounded.LocalParking) { onBreakGo(pk) }
            BigButton("Ok", Modifier.weight(1f), style = BtnStyle.SECONDARY, onClick = onDismiss)
          }
        }
        is DriverPrompt.Closure -> {
          val d = (p.event.startM - traveledM).coerceAtLeast(0.0)
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⛔", fontSize = 34.sp)
            Spacer(Modifier.width(10.dp))
            Title("Strada chiusa tra ${Fmt.distanceText(d)}", size = 24)
          }
          Caption(listOfNotNull(p.event.e.title, p.event.e.detail).joinToString(" · ") + " (fonte: ${p.event.e.source})",
              color = Nm.Text, size = 17, lines = 6)
          Spacer(Modifier.height(14.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Cerca un'alternativa", Modifier.weight(1.2f), Icons.Rounded.Route) { onClosure(true) }
            BigButton("Proseguo", Modifier.weight(1f), Icons.Rounded.Check, BtnStyle.SECONDARY) { onClosure(false) }
          }
        }
      }
    }
  }
}

/** A toll booth (or a border) coming up: the driver gets ready to pay or to stop. */
@Composable
fun BoothBanner(node: app.navmaster.truck.routing.RouteNode, role: String?, distanceM: Double, modifier: Modifier = Modifier) {
  val (icon, title) = when {
    !node.isToll -> "🛂" to "Confine di Stato"
    role == "entrata" -> "🎫" to "Casello d'ingresso"
    role == "uscita" -> "💶" to "Casello di uscita · pagamento"
    else -> "💶" to "Barriera del pedaggio"
  }
  Row(
      modifier.shadow(6.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(NmPanel).padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF1565C0)), contentAlignment = Alignment.Center) { Text(icon, fontSize = 22.sp) }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.padding(end = 12.dp)) {
      Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      Text(if (distanceM < 30) "qui" else "tra " + Fmt.distanceText(distanceM), color = Color(0xFF90CAF9), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/** The next difficulty ahead, under the manoeuvre bar. */
@Composable
fun CritBanner(c: Criticality, distanceM: Double, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Row(
      modifier.shadow(6.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp))
          .background(if (c.severity == app.navmaster.truck.routing.Severity.CRITICAL) Color(0xF2B71C1C) else NmPanel)
          .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(severityColor(c.severity)), contentAlignment = Alignment.Center) {
      Text(c.kind.icon, fontSize = 22.sp)
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.padding(end = 12.dp)) {
      Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(if (distanceM < 30) "qui" else "tra " + Fmt.distanceText(distanceM), color = Color(0xFFFFE082), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
  }
}

/**
 * Places ahead on the route (the categories chosen by the driver), in a narrow panel at the edge of
 * the screen. It opens by itself when new places come up and closes again after the chosen
 * seconds, leaving a small tab the driver can touch to open it; how many places, for how long and
 * how transparent are in the settings.
 */
@Composable
fun PoiRail(
    pois: List<RoutePoi>,
    traveledM: Double,
    count: Int,
    seconds: Int,
    opacity: Int,
    atRight: Boolean,
    narrow: Boolean,
    modifier: Modifier = Modifier,
    onPoi: (RoutePoi) -> Unit,
    onAll: (() -> Unit)? = null,
) {
  if (count <= 0) return
  // the nearest of each kind ahead (a parking, a fuel station, a service area...) rather than four
  // restaurants in the same street
  val ahead = pois.filter { it.alongM > traveledM + 50 && it.alongM < traveledM + 80_000 }
      .groupBy { it.poi.cat }.values.map { it.first() }.sortedBy { it.alongM }.take(count.coerceAtMost(6))
  if (ahead.isEmpty()) return
  val keys = ahead.map { it.poi.key }
  var open by remember { mutableStateOf(true) }
  var seen by remember { mutableStateOf(emptySet<String>()) }
  LaunchedEffect(keys) {
    if (keys.any { it !in seen }) open = true
    seen = seen + keys
  }
  LaunchedEffect(open, keys, seconds) {
    if (open && seconds > 0) {
      delay(seconds * 1000L)
      open = false
    }
  }
  val alpha = (opacity.coerceIn(15, 100)) / 100f
  val bg = Color(0xFF1B222B).copy(alpha = alpha)
  val width = if (narrow) 188.dp else 232.dp
  Column(modifier.width(width), horizontalAlignment = if (atRight) Alignment.End else Alignment.Start,
      verticalArrangement = Arrangement.spacedBy(5.dp)) {
    if (open) {
      for (p in ahead) PoiChip(p, traveledM, bg) { onPoi(p) }
      // a thin handle to close it by hand
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onAll != null) {
          Text("tutti i punti", color = Color.White.copy(alpha = 0.95f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
              modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable { onAll() }
                  .padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Text(if (atRight) "›  chiudi" else "chiudi  ‹", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable { open = false }
                .padding(horizontal = 10.dp, vertical = 4.dp))
      }
    } else {
      // closed: a small tab with the kinds of places ahead
      val icons = ahead.take(3).joinToString(" ") { PoiCategories.byId(it.poi.cat)?.icon ?: "📍" }
      Text(if (atRight) "‹ $icons" else "$icons ›", fontSize = 17.sp, color = Color.White,
          modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(bg).clickable { open = true }
              .padding(horizontal = 10.dp, vertical = 6.dp))
    }
  }
}

/** One place in the side panel: small, one line for the name, the distance on the right. */
@Composable
private fun PoiChip(p: RoutePoi, traveledM: Double, bg: Color, onClick: () -> Unit) {
  val cat = PoiCategories.byId(p.poi.cat)
  Row(
      Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(14.dp)).background(bg)
          .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(cat?.icon ?: "📍", fontSize = 18.sp, modifier = Modifier.width(26.dp))
    Column(Modifier.weight(1f)) {
      Text(p.poi.title.ifBlank { cat?.label ?: "" }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      val tags = listOfNotNull(
          if ("hgv" in p.poi.flags) "TIR" else null, if ("adblue" in p.poi.flags) "AdBlue" else null,
          if ("h24" in p.poi.flags) "24h" else null,
          if (p.offRouteM > 150) "a ${p.offRouteM.toInt()} m" else null,
      )
      if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = Color(0xFFB8C2CC), fontSize = 11.sp, maxLines = 1)
    }
    Text(Fmt.distanceText(p.alongM - traveledM), color = Nm.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
  }
}

/** A sample of the panel for the settings, with the chosen transparency. */
@Composable
fun PoiRailPreview(opacity: Int, modifier: Modifier = Modifier) {
  val bg = Color(0xFF1B222B).copy(alpha = opacity.coerceIn(15, 100) / 100f)
  Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFFD9D3C7)).padding(12.dp)) {
    Column(Modifier.width(232.dp).align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      for ((icon, name, d) in listOf(Triple("⛽", "Distributore TIR", "2,4 km"), Triple("🅿", "Parcheggio camion", "8 km"))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
          Text(icon, fontSize = 18.sp, modifier = Modifier.width(26.dp))
          Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
          Text(d, color = Nm.Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
fun PoiRow(p: RoutePoi, traveledM: Double, onClick: () -> Unit) {
  val cat = PoiCategories.byId(p.poi.cat)
  Row(
      Modifier.fillMaxWidth().heightIn(min = 54.dp).shadow(4.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(NmPanel)
          .border(1.dp, Nm.Line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(cat?.icon ?: "📍", fontSize = 22.sp, modifier = Modifier.width(34.dp))
    Column(Modifier.weight(1f)) {
      Text(p.poi.title.ifBlank { cat?.label ?: "" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      val tags = listOfNotNull(
          if ("hgv" in p.poi.flags) "TIR" else null, if ("adblue" in p.poi.flags) "AdBlue" else null,
          if ("h24" in p.poi.flags) "24h" else null, if ("lpg" in p.poi.flags) "GPL" else null,
          if (p.offRouteM > 150) "a ${p.offRouteM.toInt()} m" else null,
      )
      if (tags.isNotEmpty()) Caption(tags.joinToString(" · "), size = 12, lines = 1)
    }
    Text(Fmt.distanceText(p.alongM - traveledM), color = Nm.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
  }
}

/** Details of a place along the route, with what the driver can do with it. */
@Composable
fun PoiSheet(p: RoutePoi, traveledM: Double, onAddStop: () -> Unit, onClose: () -> Unit) {
  val cat = PoiCategories.byId(p.poi.cat)
  AdaptiveSheet(p.poi.title.ifBlank { cat?.label ?: "Punto di interesse" }, onClose) {
    Caption("${cat?.icon ?: ""} ${cat?.label ?: p.poi.cat} · tra ${Fmt.distanceText(p.alongM - traveledM)}", color = Nm.Text, size = 16)
    p.poi.brand?.let { KeyValue("Marchio", it) }
    p.poi.hours?.let { KeyValue("Orari", it) }
    p.poi.phone?.let { KeyValue("Telefono", it) }
    if (p.offRouteM > 50) KeyValue("Distanza dalla strada", "${p.offRouteM.toInt()} m")
    val flags = p.poi.flags.mapNotNull { when (it) { "hgv" -> "adatto ai mezzi pesanti"; "adblue" -> "AdBlue"; "h24" -> "aperto 24 ore"; "lpg" -> "GPL"; "free" -> "gratuito"; else -> null } }
    if (flags.isNotEmpty()) KeyValue("Servizi", flags.joinToString(", "))
    Spacer(Modifier.height(10.dp))
    SatelliteView(p.poi.lat, p.poi.lon, null, Modifier.fillMaxWidth().height(220.dp))
    Spacer(Modifier.height(12.dp))
    BigButton("Aggiungi come tappa", Modifier.fillMaxWidth(), Icons.Rounded.LocalParking, onClick = onAddStop)
    Spacer(Modifier.height(16.dp))
  }
}
