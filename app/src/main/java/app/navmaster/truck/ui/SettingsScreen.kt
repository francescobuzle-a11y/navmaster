package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import app.navmaster.truck.settings.DriveView
import app.navmaster.truck.settings.NightMode
import app.navmaster.truck.settings.PoiCategories
import app.navmaster.truck.settings.PoiCategory
import app.navmaster.truck.settings.PoiGroup
import app.navmaster.truck.settings.PoiSide
import app.navmaster.truck.settings.Settings
import app.navmaster.truck.settings.TollPolicy
import app.navmaster.truck.settings.VoiceLevel

/** The pages of the settings: a short list first, each page with its own things only. */
private enum class SettingsPage(val id: String, val title: String, val icon: String) {
  ROUTE("route", "Percorso e pedaggi", "🛣"),
  DRIVING("driving", "Guida e avvisi", "🧭"),
  MAP("map", "Mappa e vista", "🗺"),
  POI("poi", "Punti di interesse", "📍"),
  OFFLINE("offline", "Mappe offline", "📥"),
  PHOTOS("photos", "Foto stradali", "📷"),
  ABOUT("about", "Dati e licenze", "📄"),
}

@Composable
fun SettingsScreen(onClose: () -> Unit, onRegions: () -> Unit, initialPage: String? = null) {
  val s by AppGraph.settings.settings.collectAsState()
  var pageId by rememberSaveable { mutableStateOf(initialPage?.takeIf { p -> SettingsPage.entries.any { it.id == p } }) }
  val page = SettingsPage.entries.firstOrNull { it.id == pageId }
  fun set(block: (Settings) -> Settings) = AppGraph.settings.update(block)

  // a page always opens at its top
  key(pageId) {
    if (page == null) {
      AdaptiveSheet("Impostazioni", onClose, wide = true) {
        for (p in SettingsPage.entries) {
          MenuRow(p.icon, p.title, summary(p, s)) { pageId = p.id }
        }
        Spacer(Modifier.height(16.dp))
      }
    } else {
      AdaptiveSheet(page.title, onClose, wide = true, onBack = { pageId = null }) {
        when (page) {
          SettingsPage.ROUTE -> RoutePage(s, ::set)
          SettingsPage.DRIVING -> DrivingPage(s, ::set)
          SettingsPage.MAP -> MapPage(s, ::set)
          SettingsPage.POI -> PoiPage(s, ::set)
          SettingsPage.OFFLINE -> OfflinePage(s, ::set, onRegions)
          SettingsPage.PHOTOS -> PhotosPage(s, ::set)
          SettingsPage.ABOUT -> AboutPage()
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

/** What is set now, in a few words, under each entry of the list. */
private fun summary(p: SettingsPage, s: Settings): String =
    when (p) {
      SettingsPage.ROUTE -> s.tollPolicy.label + if (s.tollPolicy == TollPolicy.ASK) " · fino a ${s.tollMaxExtraMin} min in più" else ""
      SettingsPage.DRIVING -> listOf(
          "voce " + s.voiceLevel.label.lowercase(),
          if (s.junctionView) "vista svincoli" else null,
          if (s.askTightRamps) "domanda sugli svincoli stretti" else null,
          if (s.driveTimeReminder) "pause di guida" else null,
      ).filterNotNull().joinToString(" · ").replaceFirstChar { it.uppercase() }
      SettingsPage.MAP -> (if (s.driveView == DriveView.VIEW_3D) "3D, inclinata di ${s.tiltDeg}°" else "2D dall'alto") + " · " + s.nightMode.label
      SettingsPage.POI -> if (s.poiRailCount == 0) "Pannello spento" else
        "${s.poiRailCount} punti · ${s.poiCategories.size} categorie · " + (if (s.poiRailSeconds == 0) "sempre aperto" else "${s.poiRailSeconds} s")
      SettingsPage.OFFLINE -> (if (s.wifiOnly) "Scarica solo con Wi-Fi" else "Scarica anche con dati mobili") +
          if (s.useEuropeGraph) " · grafo Europa" else ""
      SettingsPage.PHOTOS -> if (s.mapillaryToken.isBlank()) "Panoramax e KartaView" else "Panoramax, KartaView e Mapillary"
      SettingsPage.ABOUT -> "OpenStreetMap, Esri, divieti di circolazione"
    }

@Composable
private fun MenuRow(icon: String, title: String, summary: String, onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Nm.Raised)
          .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Nm.PanelSolid), contentAlignment = Alignment.Center) {
      Text(icon, fontSize = 22.sp)
    }
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(title, color = Nm.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
      Text(summary, color = Nm.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Icon(Icons.Rounded.ChevronRight, null, tint = Nm.Muted)
  }
}

/** A group of settings on a card, with its title. */
@Composable
private fun Card(title: String? = null, content: @Composable () -> Unit) {
  Column(
      Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(Nm.Raised)
          .border(1.dp, Nm.Line, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
  ) {
    if (title != null) Text(title.uppercase(), color = Nm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
    content()
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(content: @Composable () -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Small pill for the finer choices inside a category. */
@Composable
private fun SmallPill(text: String, selected: Boolean, onClick: () -> Unit) {
  Box(
      Modifier.heightIn(min = 38.dp).clip(RoundedCornerShape(19.dp)).background(if (selected) Nm.Accent.copy(alpha = 0.85f) else Nm.PanelSolid)
          .border(1.dp, if (selected) Nm.Accent else Nm.Line, RoundedCornerShape(19.dp))
          .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
      contentAlignment = Alignment.Center,
  ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
private fun RoutePage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  Card("Strade a pagamento") {
    Pills { for (p in TollPolicy.entries) Pill(p.label, s.tollPolicy == p) { set { it.copy(tollPolicy = p) } } }
    if (s.tollPolicy == TollPolicy.ASK) {
      Spacer(Modifier.height(6.dp))
      Stepper("Minuti in più che accetti per evitare il pedaggio", s.tollMaxExtraMin.toDouble(), "min", 1.0, 0.0, 30.0, 0) { v ->
        set { it.copy(tollMaxExtraMin = v.toInt()) }
      }
    }
  }
  Card("Svincoli") {
    ToggleRow("Chiedimi prima degli svincoli stretti", "Ti propone l'uscita successiva se la curva è troppo stretta per il mezzo", s.askTightRamps) { v ->
      set { it.copy(askTightRamps = v) }
    }
  }
}

@Composable
private fun DrivingPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  Card("Voce") {
    Caption("Quanto parla la voce nelle indicazioni di guida", size = 14)
    Spacer(Modifier.height(6.dp))
    Pills { for (l in VoiceLevel.entries) Pill(l.label, s.voiceLevel == l) { set { it.copy(voiceLevel = l) } } }
    Caption(s.voiceLevel.detail, Modifier.padding(top = 6.dp), size = 13)
    ToggleRow("Avvisi a voce", "Limiti, criticità, caselli e pause (una volta sola)", s.voiceWarnings) { v -> set { it.copy(voiceWarnings = v) } }
  }
  Card("Incroci e svincoli") {
    ToggleRow("Vista dello svincolo", "Agli svincoli e alle uscite: la strada con le corsie da prendere e i cartelli con le direzioni",
        s.junctionView) { v -> set { it.copy(junctionView = v) } }
  }
  Card("Tempi di guida") {
    ToggleRow("Promemoria delle pause", "Pausa dopo 4 h 30 di guida (reg. CE 561/2006) con i parcheggi adatti", s.driveTimeReminder) { v ->
      set { it.copy(driveTimeReminder = v) }
    }
  }
  Card("Velocità") {
    Stepper("Avviso quando superi il limite di", s.speedWarningKmh.toDouble(), "km/h", 1.0, 0.0, 20.0, 0) { v ->
      set { it.copy(speedWarningKmh = v.toInt()) }
    }
  }
}

@Composable
private fun MapPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  Card("Vista in guida") {
    Pills { for (d in DriveView.entries) Pill(d.label, s.driveView == d) { set { it.copy(driveView = d) } } }
    Caption("Si cambia anche in guida con il pulsante 2D/3D a destra.", Modifier.padding(top = 6.dp), size = 13)
    if (s.driveView == DriveView.VIEW_3D) {
      Spacer(Modifier.height(6.dp))
      Stepper("Inclinazione", s.tiltDeg.toDouble(), "°", 5.0, 20.0, 60.0, 0) { v -> set { it.copy(tiltDeg = v.toInt()) } }
    }
  }
  Card("Colori") {
    Pills { for (n in NightMode.entries) Pill(n.label, s.nightMode == n) { set { it.copy(nightMode = n) } } }
  }
}

@Composable
private fun PoiPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val heavy = AppGraph.profiles.garage.value.active.type.heavy
  Card("Pannello in guida") {
    Caption("Un pannello stretto al bordo dello schermo: si apre da solo quando arrivano nuovi punti e poi si richiude " +
        "lasciando una linguetta da toccare.", size = 13)
    Stepper("Quanti punti mostrare (0 = mai)", s.poiRailCount.toDouble(), "", 1.0, 0.0, 6.0, 0) { v -> set { it.copy(poiRailCount = v.toInt()) } }
    if (s.poiRailCount > 0) {
      Caption("Per quanto resta aperto", size = 14)
      Spacer(Modifier.height(4.dp))
      Pills {
        for (sec in listOf(5, 8, 12, 20, 30, 60, 0)) {
          Pill(if (sec == 0) "Sempre" else "$sec s", s.poiRailSeconds == sec) { set { it.copy(poiRailSeconds = sec) } }
        }
      }
      Stepper("Opacità", s.poiRailOpacity.toDouble(), "%", 10.0, 20.0, 100.0, 0) { v -> set { it.copy(poiRailOpacity = v.toInt()) } }
      PoiRailPreview(s.poiRailOpacity, Modifier.fillMaxWidth().padding(vertical = 6.dp))
      Caption("Lato dello schermo", size = 14)
      Spacer(Modifier.height(4.dp))
      Pills { for (side in PoiSide.entries) Pill(side.label, s.poiRailSide == side) { set { it.copy(poiRailSide = side) } } }
    }
  }

  Text("COSA MOSTRARE", color = Nm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
      modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
  Caption("Tocca un gruppo per aprirlo. Dentro ogni categoria puoi scegliere solo alcuni tipi (nessuna scelta = tutti).", size = 13)
  // the camper places first for a camper, last for a lorry
  val groups = PoiCategories.groups.sortedBy { if (it.id == "camper") (if (heavy) 1 else -1) else 0 }
  for (g in groups) PoiGroupCard(g, s, set)

  Card("Filtri") {
    if (heavy) ToggleRow("Solo distributori con pompa camion", "Pompe alte e piazzale per mezzi pesanti", s.poiOnlyTruckFriendly) { v ->
      set { it.copy(poiOnlyTruckFriendly = v) }
    }
    Stepper("Distanza massima dalla strada", s.poiMaxDetourM.toDouble(), "m", 100.0, 100.0, 5000.0, 0) { v ->
      set { it.copy(poiMaxDetourM = v.toInt()) }
    }
  }
}

/** One group of places, folded: the header says how many are on and switches all of them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoiGroupCard(g: PoiGroup, s: Settings, set: ((Settings) -> Settings) -> Unit) {
  // the first group opens by itself, to show how the finer choices work
  var open by rememberSaveable(g.id) { mutableStateOf(g.id == "rest") }
  val ids = g.categories.map { it.id }
  val on = ids.count { it in s.poiCategories }
  Column(
      Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(Nm.Raised)
          .border(1.dp, if (on > 0) Nm.Accent.copy(alpha = 0.6f) else Nm.Line, RoundedCornerShape(18.dp)),
  ) {
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(g.icon, fontSize = 22.sp)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(g.label, color = Nm.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(if (on == 0) "Nessuna" else if (on == ids.size) "Tutte (${ids.size})" else "$on di ${ids.size}", color = Nm.Muted, fontSize = 13.sp)
      }
      Switch(on > 0, { v -> set { it.copy(poiCategories = if (v) it.poiCategories + ids else it.poiCategories - ids.toSet()) } },
          colors = SwitchDefaults.colors(checkedTrackColor = Nm.Accent, checkedThumbColor = Color.White))
      Spacer(Modifier.width(6.dp))
      Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Chiudi" else "Apri", tint = Nm.Muted)
    }
    if (open) {
      Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
        for (c in g.categories) PoiCategoryRow(c, s, set)
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoiCategoryRow(c: PoiCategory, s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val on = c.id in s.poiCategories
  Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(14.dp)).background(Nm.PanelSolid.copy(alpha = 0.6f))) {
    Row(
        Modifier.fillMaxWidth().clickable { set { it.copy(poiCategories = if (on) it.poiCategories - c.id else it.poiCategories + c.id) } }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(c.icon, fontSize = 18.sp)
      Spacer(Modifier.width(10.dp))
      Text(c.label, color = Nm.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Switch(on, { v -> set { it.copy(poiCategories = if (v) it.poiCategories + c.id else it.poiCategories - c.id) } },
          colors = SwitchDefaults.colors(checkedTrackColor = Nm.Accent, checkedThumbColor = Color.White))
    }
    if (on && c.subs.isNotEmpty()) {
      val chosen = c.subs.filter { "${c.id}:${it.flag}" in s.poiSubs }
      FlowRow(
          Modifier.padding(start = 40.dp, end = 12.dp, bottom = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SmallPill("Tutti", chosen.isEmpty()) { set { it.copy(poiSubs = it.poiSubs.filterNot { k -> k.startsWith("${c.id}:") }.toSet()) } }
        for (sub in c.subs) {
          val k = "${c.id}:${sub.flag}"
          SmallPill(sub.label, k in s.poiSubs) { set { it.copy(poiSubs = if (k in it.poiSubs) it.poiSubs - k else it.poiSubs + k) } }
        }
      }
    }
  }
}

@Composable
private fun OfflinePage(s: Settings, set: ((Settings) -> Settings) -> Unit, onRegions: () -> Unit) {
  Card("Scaricamento") {
    ToggleRow("Scarica solo con Wi-Fi", null, s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
    BigButton("Gestisci i Paesi scaricati", Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), style = BtnStyle.SECONDARY, onClick = onRegions)
  }
  Card("Percorsi") {
    ToggleRow("Percorsi tra Paesi (grafo Europa)", "Usa il grafo unico europeo quando disponibile, per calcolare oltre i confini", s.useEuropeGraph) { v ->
      set { it.copy(useEuropeGraph = v) }
    }
  }
}

@Composable
private fun PhotosPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  Card("Fonti") {
    Caption("Panoramax e KartaView funzionano senza registrazione. Con un token gratuito di Mapillary " +
        "(mapillary.com/dashboard/developers) si aggiungono anche le sue foto.", size = 13)
    OutlinedTextField(
        s.mapillaryToken, { v -> set { it.copy(mapillaryToken = v.trim()) } }, label = { Text("Token Mapillary (facoltativo)") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent),
    )
  }
}

@Composable
private fun AboutPage() {
  Card("Fonti dei dati") {
    Caption("Mappe e dati © OpenStreetMap contributors (ODbL). Immagini satellitari © Esri (uso personale).", size = 14, lines = 4)
  }
  Card("Avvertenze") {
    Caption("I divieti nazionali di circolazione sono indicativi: verifica sempre il calendario ufficiale. " +
        "Le criticità (curve, strade strette, limiti) vengono dai dati OpenStreetMap e vanno confermate dalla segnaletica.", size = 14, lines = 6)
  }
}
