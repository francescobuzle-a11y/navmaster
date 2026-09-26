package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.poi.Poi
import app.navmaster.truck.settings.PoiCategories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate

/** Where to look for places. */
enum class PoiArea(val label: String) {
  ROUTE("Lungo il percorso"),
  HERE("Intorno a me"),
  DESTINATION("Vicino alla destinazione"),
  STOP("Vicino alla tappa"),
}

/** In which order to show them. */
enum class PoiOrder(val label: String) {
  NEAR("Più vicini prima"),
  FAR("Più lontani prima"),
  NAME("A–Z"),
}

/** A place found, with its distance (along the route for the route, straight otherwise). */
data class PoiHit(val poi: Poi, val distanceM: Double, val alongRoute: Boolean, val offRouteM: Double = 0.0)

/**
 * All the places, beyond the few of the side panel: along the route ahead (nearest first, the
 * default), around the vehicle, near the destination or near a stop; any category; in order of
 * distance either way or by name, with a name filter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoiBrowser(
    route: List<GeographicCoordinate>?,
    traveledM: Double,
    here: GeographicCoordinate?,
    destination: GeographicCoordinate?,
    stop: GeographicCoordinate?,
    navigating: Boolean,
    onAddStop: (Poi) -> Unit,
    onGo: (Poi) -> Unit,
    onClose: () -> Unit,
) {
  val settings = AppGraph.settings.settings.value
  val areas = PoiArea.entries.filter {
    when (it) {
      PoiArea.ROUTE -> route != null && route.size > 1
      PoiArea.HERE -> here != null
      PoiArea.DESTINATION -> destination != null
      PoiArea.STOP -> stop != null
    }
  }
  var area by rememberSaveable { mutableStateOf(areas.firstOrNull() ?: PoiArea.HERE) }
  var order by rememberSaveable { mutableStateOf(PoiOrder.NEAR) }
  // the categories of the settings first; "all" is one touch away
  var cats by remember { mutableStateOf(settings.poiCategories.ifEmpty { PoiCategories.all.map { it.id }.toSet() }) }
  var text by rememberSaveable { mutableStateOf("") }
  var hits by remember { mutableStateOf<List<PoiHit>?>(null) }
  var open by remember { mutableStateOf<PoiHit?>(null) }

  LaunchedEffect(area, cats) {
    hits = null
    hits = withContext(Dispatchers.IO) {
      runCatching {
        when (area) {
          PoiArea.ROUTE -> {
            val m = RouteMatcher(route ?: emptyList())
            AppGraph.poi.alongRoute(m, cats, settings.poiOnlyTruckFriendly && AppGraph.profiles.garage.value.active.type.heavy,
                maxOf(settings.poiMaxDetourM, 1500))
                .filter { it.alongM > traveledM - 50 }
                .map { PoiHit(it.poi, it.alongM - traveledM, true, it.offRouteM) }
          }
          else -> {
            val c = when (area) {
              PoiArea.DESTINATION -> destination
              PoiArea.STOP -> stop
              else -> here
            } ?: return@runCatching emptyList<PoiHit>()
            AppGraph.poi.near(c.lat, c.lng, if (area == PoiArea.HERE) 25_000.0 else 10_000.0, cats, limit = 300)
                .map { (p, d) -> PoiHit(p, d, false) }
          }
        }
      }.getOrDefault(emptyList())
    }
  }

  val shown = (hits ?: emptyList())
      .filter { text.isBlank() || it.poi.title.contains(text.trim(), ignoreCase = true) || (it.poi.brand ?: "").contains(text.trim(), ignoreCase = true) }
      .let { list ->
        when (order) {
          PoiOrder.NEAR -> list.sortedBy { it.distanceM }
          PoiOrder.FAR -> list.sortedByDescending { it.distanceM }
          PoiOrder.NAME -> list.sortedBy { it.poi.title.ifBlank { "~" }.lowercase() }
        }
      }

  val sel = open
  if (sel != null) {
    PoiDetail(sel, navigating, onAddStop = { onAddStop(sel.poi) }, onGo = { onGo(sel.poi) }, onBack = { open = null })
    return
  }

  AdaptiveSheet("Punti di interesse", onClose, wide = true) {
    Text("DOVE", color = Nm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (a in areas) Pill(a.label, area == a) { area = a }
    }
    Text("ORDINE", color = Nm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (o in PoiOrder.entries) Pill(o.label, order == o) { order = o }
    }
    Text("COSA", color = Nm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val all = PoiCategories.all.map { it.id }.toSet()
      CatChip("Tutte", cats == all) { cats = all }
      for (c in PoiCategories.all) {
        CatChip("${c.icon} ${c.label}", c.id in cats && cats != all) {
          cats = if (cats == all) setOf(c.id) else if (c.id in cats) (cats - c.id).ifEmpty { all } else cats + c.id
        }
      }
    }
    OutlinedTextField(
        text, { text = it }, label = { Text("Filtra per nome") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent),
    )
    when {
      hits == null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = Nm.Accent)
        Spacer(Modifier.width(12.dp))
        Caption("Cerco…", size = 15)
      }
      shown.isEmpty() -> Caption("Nessun punto trovato: prova un'altra zona o più categorie.", Modifier.padding(12.dp), size = 15)
      else -> {
        Caption("${shown.size} punti" + if (area == PoiArea.ROUTE) " (distanze lungo il percorso)" else " (distanze in linea d'aria)", size = 13)
        for (h in shown.take(150)) PoiHitRow(h) { open = h }
      }
    }
    Spacer(Modifier.height(20.dp))
  }
}

@Composable
private fun CatChip(text: String, selected: Boolean, onClick: () -> Unit) {
  Text(text, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      modifier = Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(17.dp)).background(if (selected) Nm.Accent.copy(alpha = 0.85f) else Nm.PanelSolid)
          .border(1.dp, if (selected) Nm.Accent else Nm.Line, RoundedCornerShape(17.dp)).clickable(onClick = onClick)
          .padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
private fun PoiHitRow(h: PoiHit, onClick: () -> Unit) {
  val cat = PoiCategories.byId(h.poi.cat)
  Row(
      Modifier.fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 54.dp).clip(RoundedCornerShape(14.dp)).background(Nm.Raised)
          .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(cat?.icon ?: "📍", fontSize = 22.sp, modifier = Modifier.width(34.dp))
    Column(Modifier.weight(1f)) {
      Text(h.poi.title.ifBlank { cat?.label ?: "" }, color = Nm.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      val tags = listOfNotNull(cat?.label,
          if ("hgv" in h.poi.flags) "TIR" else null, if ("adblue" in h.poi.flags) "AdBlue" else null,
          if ("h24" in h.poi.flags) "24h" else null,
          if (h.alongRoute && h.offRouteM > 150) "a ${h.offRouteM.toInt()} m dalla strada" else null)
      Caption(tags.joinToString(" · "), size = 12, lines = 1)
    }
    Text(Fmt.distanceText(h.distanceM.coerceAtLeast(0.0)), color = Nm.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun PoiDetail(h: PoiHit, navigating: Boolean, onAddStop: () -> Unit, onGo: () -> Unit, onBack: () -> Unit) {
  val cat = PoiCategories.byId(h.poi.cat)
  AdaptiveSheet(h.poi.title.ifBlank { cat?.label ?: "Punto di interesse" }, onBack) {
    Caption("${cat?.icon ?: ""} ${cat?.label ?: h.poi.cat} · " + (if (h.alongRoute) "tra " else "a ") + Fmt.distanceText(h.distanceM.coerceAtLeast(0.0)),
        color = Nm.Text, size = 16)
    h.poi.brand?.let { KeyValue("Marchio", it) }
    h.poi.hours?.let { KeyValue("Orari", it) }
    h.poi.phone?.let { KeyValue("Telefono", it) }
    Spacer(Modifier.height(12.dp))
    BigButton("Aggiungi come tappa", Modifier.fillMaxWidth(), onClick = onAddStop)
    Spacer(Modifier.height(8.dp))
    BigButton(if (navigating) "Vai qui (nuova destinazione)" else "Vai qui", Modifier.fillMaxWidth(), style = BtnStyle.SECONDARY, onClick = onGo)
    Spacer(Modifier.height(8.dp))
    BigButton("Indietro", Modifier.fillMaxWidth(), style = BtnStyle.GHOST, onClick = onBack)
  }
}
