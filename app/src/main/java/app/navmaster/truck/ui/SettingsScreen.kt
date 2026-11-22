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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
  LIVE("live", "Traffico e segnalazioni", "🚦"),
  MAP("map", "Mappa e vista", "🗺"),
  POI("poi", "Punti di interesse", "📍"),
  CAMPER("camper", "Camper", "🚐"),
  OFFLINE("offline", "Mappe offline", "📥"),
  PHOTOS("photos", "Foto stradali", "📷"),
  ABOUT("about", "Dati e licenze", "📄"),
  KEYS("keys", "Chiavi dei servizi", "🔐"),
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
          SettingsPage.LIVE -> LivePage(s, ::set)
          SettingsPage.MAP -> MapPage(s, ::set)
          SettingsPage.POI -> PoiPage(s, ::set)
          SettingsPage.CAMPER -> CamperPage(s, ::set)
          SettingsPage.OFFLINE -> OfflinePage(s, ::set, onRegions)
          SettingsPage.PHOTOS -> PhotosPage(s, ::set)
          SettingsPage.ABOUT -> AboutPage()
          SettingsPage.KEYS -> KeysPage(s, ::set)
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

/** What is set now, in a few words, under each entry of the list. */
private fun summary(p: SettingsPage, s: Settings): String =
    when (p) {
      SettingsPage.ROUTE -> s.routeKind.label + " · " + s.tollPolicy.label + if (s.tollPolicy == TollPolicy.ASK) " · fino a ${s.tollMaxExtraMin} min in più" else ""
      SettingsPage.DRIVING -> listOf(
          "voce " + s.voiceLevel.label.lowercase(),
          if (s.junctionView) "vista svincoli" else null,
          if (s.askTightRamps) "domanda sugli svincoli stretti" else null,
          if (s.driveTimeReminder) "pause di guida" else null,
      ).filterNotNull().joinToString(" · ").replaceFirstChar { it.uppercase() }
      SettingsPage.LIVE -> listOfNotNull(
          if (s.liveReports) "segnalazioni degli autisti" else null,
          if (s.liveTraffic) "traffico" else null,
          if (s.liveTraffic && s.personalFeed) "Waze" else null,
      ).joinToString(" · ").ifBlank { "Spento" }.replaceFirstChar { it.uppercase() }
      SettingsPage.MAP -> (if (s.driveView == DriveView.VIEW_3D) "3D, inclinata di ${s.driveTilt}°" else "2D dall'alto") + " · " + s.nightMode.label +
          (if (s.alertsOnMap) " · allerte" else "")
      SettingsPage.POI -> if (s.poiRailCount == 0) "Pannello spento" else
        "${s.poiRailCount} punti · ${s.poiCategories.size} categorie · " + (if (s.poiRailSeconds == 0) "sempre aperto" else "${s.poiRailSeconds} s")
      SettingsPage.CAMPER -> "${PoiCategories.camper.count { it in s.poiCategories }} di ${PoiCategories.camper.size} tipi di sosta" +
          (if (s.camperEveningStops) " · sosta notturna dalle ${s.camperEveningFromHour}" else "")
      SettingsPage.OFFLINE -> (if (s.downloadWifiOnly) "Scarica solo con Wi-Fi" else "Scarica anche con i dati mobili")
      SettingsPage.PHOTOS -> if (app.navmaster.truck.live.ApiKeys.mapillary(s).isBlank()) "Panoramax e KartaView" else "Panoramax, KartaView e Mapillary"
      SettingsPage.ABOUT -> "OpenStreetMap, Esri, divieti di circolazione"
      SettingsPage.KEYS -> listOf(
          if (s.tomtomKey.isNotBlank()) "TomTom ✓" else "TomTom: manca la chiave",
          if (s.hereKey.isNotBlank()) "HERE ✓" else "HERE: manca la chiave").joinToString(" · ")
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
  Card("Percorso preferito") {
    Pills { for (k in app.navmaster.truck.vehicle.RouteKind.entries) Pill(k.label, s.routeKind == k) { set { it.copy(routeKind = k) } } }
    Spacer(Modifier.height(6.dp))
    Caption(when (s.routeKind) {
      app.navmaster.truck.vehicle.RouteKind.FASTEST -> "Il più rapido, con le regole e le velocità di openrouteservice per camion o auto."
      app.navmaster.truck.vehicle.RouteKind.RECOMMENDED -> "Il «consigliato» di openrouteservice: per i camion preferisce autostrade e strade principali ed evita le strade piccole; per camper e auto è il più rapido."
      app.navmaster.truck.vehicle.RouteKind.SHORTEST -> "Meno chilometri, sempre su strade adatte al mezzo (il «più corto» di openrouteservice)."
    } + " Gli altri due tipi compaiono comunque come schede accanto.", lines = 5)
  }
  Card("Se lasci il percorso") {
    Caption("Il percorso si ricalcola subito dalla strada che hai preso, nel tuo senso di marcia e verso la destinazione: " +
        "non ti fa tornare indietro e non chiede niente.", lines = 4)
  }
  Card("Calcolo del percorso") {
    Caption("Il percorso lo calcola il motore NavMaster sul tablet, con la logica di openrouteservice e le misure del mezzo: " +
        "servono le mappe dei Paesi del viaggio («Mappe d'Europa»).", color = Nm.Text, lines = 4)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(s.engineServer, { v -> set { it.copy(engineServer = v.trim()) } }, label = { Text("Server NavMaster (es. https://nav.esempio.it)") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent))
    Caption("Con internet, i viaggi che attraversano un confine o passano per un Paese non scaricato vengono calcolati dal server " +
        "(lo stesso motore, sul grafo di tutta l'Europa). La chiave, se il server la chiede, è quella di openrouteservice in «Chiavi dei servizi».", size = 13, lines = 4)
    Spacer(Modifier.height(6.dp))
    ToggleRow("Mappa online dove non è scaricata", "OpenFreeMap, gratuita: si vede la mappa anche nei Paesi non scaricati (usa dati)", s.onlineMap) { v ->
      set { it.copy(onlineMap = v) }
    }
  }
  Card("Strade a pagamento") {
    Pills { for (p in TollPolicy.entries) Pill(p.label, s.tollPolicy == p) { set { it.copy(tollPolicy = p) } } }
    if (s.tollPolicy == TollPolicy.AVOID) Caption("Nessuna strada a pedaggio né casello: solo se non c'è un'altra strada (per esempio sei già in autostrada) e lo vedi sulla scheda del percorso. Le autostrade gratuite restano ammesse.", Modifier.padding(top = 6.dp), size = 13)
    if (s.tollPolicy == TollPolicy.ASK) {
      Spacer(Modifier.height(6.dp))
      Stepper("Minuti in più che accetti per evitare il pedaggio", s.tollMaxExtraMin.toDouble(), "min", 1.0, 0.0, 30.0, 0) { v ->
        set { it.copy(tollMaxExtraMin = v.toInt()) }
      }
    }
  }
  Card("Autostrade") {
    ToggleRow("Senza autostrade", "Il percorso passa da statali e provinciali; l'autostrada solo se non c'è un'altra strada (se sei già in autostrada, fino alla prima uscita). Le superstrade restano ammesse",
        s.avoidMotorways) { v -> set { it.copy(avoidMotorways = v) } }
  }
  Card("Strade strette e curve") {
    ToggleRow("Avvisi su strade strette e curve", "Spento: il percorso rispetta già le misure del mezzo, senza avvisi su corsie, " +
        "curve, svincoli e svolte strette stimati dalla mappa", s.roadDifficulties) { v ->
      set { it.copy(roadDifficulties = v) }
    }
    if (s.roadDifficulties) {
      ToggleRow("Chiedimi prima degli svincoli stretti", "Ti propone l'uscita successiva se la curva è troppo stretta per il mezzo", s.askTightRamps) { v ->
        set { it.copy(askTightRamps = v) }
      }
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
    if (s.junctionView) {
      Caption("Si apre in autostrada da", Modifier.padding(top = 6.dp), size = 14)
      Pills { for (m in listOf(600, 1000, 1500, 2000)) Pill("$m m", s.junctionViewMotorwayM == m) { set { it.copy(junctionViewMotorwayM = m) } } }
      Caption("Sulle altre strade da", Modifier.padding(top = 6.dp), size = 14)
      Pills { for (m in listOf(150, 220, 350, 500)) Pill("$m m", s.junctionViewRoadM == m) { set { it.copy(junctionViewRoadM = m) } } }
      Caption("E comunque almeno", Modifier.padding(top = 6.dp), size = 14)
      Pills { for (t in listOf(15, 25, 40)) Pill("$t s prima", s.junctionViewSeconds == t) { set { it.copy(junctionViewSeconds = t) } } }
    }
  }
  Card("Schermo di guida") {
    ToggleRow("Barra della manovra compatta", "Lontano dalla manovra solo il suo segno e la distanza, in alto a sinistra; si apre intera " +
        "quando la manovra è vicina (o con un tocco)", s.maneuverCompact) { v -> set { it.copy(maneuverCompact = v) } }
    if (s.maneuverCompact) {
      Caption("Si apre intera", Modifier.padding(top = 6.dp), size = 14)
      Pills { for (t in listOf(10, 15, 20, 30, 45)) Pill("$t s prima", s.maneuverOpenS == t) { set { it.copy(maneuverOpenS = t) } } }
    }
    ToggleRow("Limite di velocità sempre in vista", "Il cartello del limite a sinistra della mappa (quello del mezzo quando è più basso)",
        s.speedLimitSide) { v -> set { it.copy(speedLimitSide = v) } }
    ToggleRow("Località in basso", "Il paese o la frazione in cui sei, sopra l'ora di arrivo e le altre informazioni", s.showLocality) { v ->
      set { it.copy(showLocality = v) }
    }
  }
  MapGapsCard()
  Card("Tempi di guida") {
    ToggleRow("Promemoria delle pause", "Pausa dopo 4 h 30 di guida (reg. CE 561/2006) con i parcheggi adatti", s.driveTimeReminder) { v ->
      set { it.copy(driveTimeReminder = v) }
    }
  }
  Card("Autovelox") {
    // with the owner's Radarbot pack on the tablet, the pack is the only source (AlertPack)
    val packVersion by app.navmaster.truck.limits.AlertPack.version.collectAsState()
    val packOn = remember(packVersion) { runCatching { app.navmaster.truck.limits.AlertPack.db() != null }.getOrDefault(false) }
    ToggleRow("Avviso autovelox fissi", (if (packOn) "Solo dal pacchetto delle allerte (Impostazioni › Mappa e vista › Allerte sulla mappa), " +
        "con le risposte degli altri autisti. " else "Dai dati OpenStreetMap confermati (elenco ufficiale, censimento del ministero in Italia, relazione, " +
        "velocità o nome), da quelli visti dagli altri autisti e dal tuo elenco. ") +
        (if (s.enforcementEverywhere) "In tutti i Paesi (scelta in Traffico e segnalazioni)." else "In Germania e Svizzera non compare, in Francia solo «zona di controllo»."),
        s.speedCameras) { v -> set { it.copy(speedCameras = v) } }
    if (s.speedCameras && !packOn) {
      ToggleRow("Anche gli autovelox non confermati", "Punti di OpenStreetMap senza velocità, nome né direzione su autostrade e superstrade " +
          "(spesso vecchi errori, colonnine SOS o portali) e, in Italia, quelli dei comuni che non hanno nulla nel censimento del ministero " +
          "o che lo segna spento. Spento: non vengono annunciati, salvo se altri autisti li hanno visti.", s.speedCamerasUnverified) { v ->
        set { it.copy(speedCamerasUnverified = v) }
      }
    }
    if (s.speedCameras) {
      ToggleRow("Chiedi «c'era?» dopo un autovelox", "Una domanda con un tocco (o a voce) dopo l'autovelox, al massimo ogni 10 minuti. " +
          "La risposta va agli altri autisti NavMaster senza il tuo nome: un autovelox che due autisti non hanno visto non viene più annunciato.",
          s.cameraQuestions) { v -> set { it.copy(cameraQuestions = v) } }
      if (!packOn) CameraListRow(s, set)
    }
  }
  Card("Velocità") {
    ToggleRow("Velocità in miglia orarie (mph)", "Tachimetro, cartelli dei limiti, autovelox, Tutor e voce in mph. Spento: sempre in km/h, " +
        "anche dove i dati sono in miglia (Regno Unito, Stati Uniti): vengono convertiti.", s.speedMph) { v ->
      set { it.copy(speedMph = v) }
    }
    Stepper("Avviso quando superi il limite di", s.speedWarningKmh.toDouble(), "km/h", 1.0, 0.0, 20.0, 0) { v ->
      set { it.copy(speedWarningKmh = v.toInt()) }
    }
  }
}

/**
 * The owner's own list of speed cameras (CameraImport): the file of a speed-camera database
 * provider (GPX or CSV, as sold for every navigator) chosen from the tablet, what was imported,
 * and whether it is the only source.
 */
@Composable
private fun CameraListRow(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  var info by remember { mutableStateOf(app.navmaster.truck.limits.CameraImport.info()) }
  var message by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val pick = androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
      val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
          val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
          if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
      }.getOrNull() ?: (uri.lastPathSegment ?: "elenco")
      val n = runCatching { context.contentResolver.openInputStream(uri)?.use { app.navmaster.truck.limits.CameraImport.import(it, name) } ?: 0 }
          .onFailure { android.util.Log.w("NavMasterCameras", "import: $it") }.getOrDefault(0)
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        busy = false
        info = app.navmaster.truck.limits.CameraImport.info()
        message = if (n > 0) "Importati $n autovelox da $name." else "Nessun autovelox letto da $name: serve un GPX (waypoint) o un CSV con longitudine, latitudine e nome."
      }
    }
  }
  Spacer(Modifier.height(8.dp))
  Caption("Il tuo elenco: il file di un fornitore di autovelox (per esempio SCDB, in GPX o CSV per navigatori) o un CSV tuo. " +
      "Gli autovelox del file vengono annunciati come quelli dei dati scaricati.", lines = 3)
  Spacer(Modifier.height(6.dp))
  info?.let { i ->
    Caption("Elenco importato: ${i.count} autovelox da «${i.file}» (${i.date}).", color = Nm.Text, lines = 2)
    Spacer(Modifier.height(6.dp))
    ToggleRow("Usa solo il mio elenco", "Gli autovelox di OpenStreetMap non vengono annunciati (restano Tutor e tratti dalle relazioni).",
        s.speedCamerasImportedOnly) { v -> set { it.copy(speedCamerasImportedOnly = v) } }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    BigButton(if (info == null) "Importa un elenco (GPX/CSV)" else "Sostituisci l'elenco", Modifier.weight(1f), enabled = !busy) {
      pick.launch(arrayOf("*/*"))
    }
    if (info != null) BigButton("Elimina", Modifier.weight(0.6f), style = BtnStyle.DANGER, enabled = !busy) {
      app.navmaster.truck.limits.CameraImport.clear()
      info = null
      message = "Elenco eliminato."
    }
  }
  message?.let { Spacer(Modifier.height(4.dp)); Caption(it, color = Nm.Text, lines = 3) }
}

@Composable
private fun LivePage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  Card("Segnalazioni degli autisti") {
    ToggleRow("Segnalazioni condivise", "Polizia, code, incidenti, pericoli, strade chiuse, controlli ai mezzi pesanti segnalati dagli " +
        "altri autisti NavMaster sulla tua strada, e il pulsante ⚠ per segnalare. Senza account e senza il tuo nome.", s.liveReports) { v ->
      set { it.copy(liveReports = v) }
    }
    if (s.liveReports) {
      Caption("Tocca ⚠ per scegliere cosa segnalare; tieni premuto ⚠ per segnalare subito la polizia (dove si può " +
          "avvisare, altrimenti un pericolo): parte dopo 6 secondi, con «Annulla» per ripensarci.",
          Modifier.padding(start = 4.dp, bottom = 6.dp), size = 13, lines = 4)
      val mic = androidx.activity.compose.rememberLauncherForActivityResult(
          androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) set { it.copy(voiceAnswers = true) } }
      ToggleRow("Rispondi a voce", "Passata una segnalazione, il navigatore chiede «c'è ancora?» e ascolta «sì» o «no» per qualche " +
          "secondo: niente mani sullo schermo. Usa il microfono solo in quel momento.", s.voiceAnswers) { v ->
        if (v && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) mic.launch(android.Manifest.permission.RECORD_AUDIO)
        else set { it.copy(voiceAnswers = v) }
      }
    }
    ToggleRow("Polizia e autovelox in tutti i Paesi", "Acceso (scelta del proprietario, sotto la sua responsabilità): autovelox, Tutor, " +
        "polizia e segnalazioni mostrati e detti ovunque, anche in Germania e Svizzera (dove la legge vieta l'avviso al conducente) " +
        "e in Francia (dove è ammessa solo la «zona di controllo»). Spento: si seguono le regole di ogni Paese.",
        s.enforcementEverywhere) { v -> set { it.copy(enforcementEverywhere = v) } }
  }
  Card("Traffico") {
    ToggleRow("Informazioni sul traffico", "Code, incidenti, lavori, chiusure e pericoli sul percorso, detti a voce in tempo; se la " +
        "strada è chiusa ti propone un'alternativa. Tutto già pronto: nessuna chiave o registrazione da fare.",
        s.liveTraffic) { v -> set { it.copy(liveTraffic = v) } }
    if (s.liveTraffic) {
      ToggleRow("Da Waze", "Polizia, incidenti, pericoli, veicoli fermi, lavori, chiusure e code dalla mappa pubblica di Waze, " +
          "solo sul tratto di percorso davanti, ogni 2 minuti e mai da fermo.", s.personalFeed) { v ->
        set { it.copy(personalFeed = v, personalFeedDirect = true) }
      }
      if (app.navmaster.truck.live.PersonalFeed.refused) {
        Caption("Waze ora protegge i dati della mappa pubblica (controllo anti-robot) e non li dà all'app: per ora le " +
            "segnalazioni di Waze non arrivano.",
            Modifier.padding(start = 4.dp, bottom = 6.dp), color = Nm.Amber, size = 13, lines = 5)
      }
      ToggleRow("Centrali del traffico dei Paesi", "Dati ufficiali di Spagna, Paesi Bassi, Belgio, Lussemburgo, Francia, Germania, " +
          "Finlandia, Polonia, Lituania e Svezia, letti solo per i Paesi che attraversi.", s.nationalTraffic) { v ->
        set { it.copy(nationalTraffic = v) }
      }
      val tt = app.navmaster.truck.live.ApiKeys.tomtom(s).isNotBlank()
      val hr = app.navmaster.truck.live.ApiKeys.here(s).isNotBlank()
      Caption(if (!tt && !hr) "TomTom e HERE non attivi: scrivi le chiavi dei tuoi account gratuiti in Impostazioni › Chiavi dei servizi."
          else "Incidenti, code e chiusure di tutta Europa: prima TomTom, poi HERE quando TomTom ha finito la parte " +
              "di oggi. L'app usa le quote gratuite dei tuoi account, mai oltre il 90%.\n" +
              (if (tt) app.navmaster.truck.live.TomTomGuard.summary() else "TomTom: chiave mancante") + "\n" +
              (if (hr) app.navmaster.truck.live.HereGuard.summary() else "HERE: chiave mancante"),
          Modifier.padding(start = 4.dp, bottom = 6.dp), size = 13, lines = 6)
      if (tt) {
        ToggleRow("Colori del traffico sulla mappa", "Giallo, arancio e rosso sulle strade rallentate (al massimo un quarto delle richieste TomTom del giorno)", s.trafficOnMap) { v ->
          set { it.copy(trafficOnMap = v) }
        }
      }
    }
  }
}

@Composable
private fun MapPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  Card("Vista in guida") {
    Pills { for (d in DriveView.entries) Pill(d.label, s.driveView == d) { set { it.copy(driveView = d) } } }
    Caption("Si cambia anche in guida con il pulsante 2D/3D a destra.", Modifier.padding(top = 6.dp), size = 13)
    ToggleRow("Vista da guida anche senza percorso", "Con l'app aperta la mappa segue il mezzo come in guida: la direzione di marcia in alto, " +
        "inclinata e vicina, col tachimetro. Spostando la mappa la vista si ferma; «Centra» la riporta",
        s.idleDriveView) { v -> set { it.copy(idleDriveView = v) } }
    if (s.driveView == DriveView.VIEW_3D) {
      Spacer(Modifier.height(6.dp))
      Stepper("Inclinazione", s.driveTilt.toDouble(), "°", 1.0, 30.0, DriveCamera.MAX_SETTING_TILT.toDouble(), 0) { v -> set { it.copy(driveTilt = v.toInt()) } }
      Stepper("Vicinanza (zoom)", s.driveZoom, "", 0.1, 15.5, 19.5, 1) { v -> set { it.copy(driveZoom = v) } }
      Caption("Valori consigliati: 78° e 18,6, con l'orizzonte a un quinto dall'alto; la stessa vista a ogni velocità. " +
          "Oltre 60° solo se la mappa del tablet lo permette, altrimenti un po' meno vicina.",
          Modifier.padding(top = 2.dp), size = 13, lines = 5)
      if (s.driveTilt != DriveCamera.DEFAULT_TILT || s.driveZoom != DriveCamera.DEFAULT_ZOOM) {
        Spacer(Modifier.height(6.dp))
        Pills { Pill("Valori consigliati", false) { set { it.copy(driveTilt = DriveCamera.DEFAULT_TILT, driveZoom = DriveCamera.DEFAULT_ZOOM) } } }
      }
    }
    ToggleRow("Mezzo sulla corsia di marcia", "La freccia e la linea del percorso sulla corsia di destra (di sinistra dove si guida " +
        "a sinistra), invece che in mezzo alla strada", s.driveLaneSide) { v -> set { it.copy(driveLaneSide = v) } }
    Caption("Il percorso già fatto", Modifier.padding(top = 6.dp), size = 14)
    Pills {
      Pill("Più chiaro", s.doneRoute == 1) { set { it.copy(doneRoute = 1) } }
      Pill("Nascosto", s.doneRoute == 2) { set { it.copy(doneRoute = 2) } }
      Pill("Come il resto", s.doneRoute == 0) { set { it.copy(doneRoute = 0) } }
    }
    Stepper("Posizione del mezzo, tablet in verticale", s.vehicleUprightPct.toDouble(), "% dall'alto", 1.0, 50.0, 90.0, 0) { v ->
      set { it.copy(vehicleUprightPct = v.toInt()) }
    }
    Stepper("Posizione del mezzo, tablet in orizzontale", s.vehicleAcrossPct.toDouble(), "% dall'alto", 1.0, 50.0, 90.0, 0) { v ->
      set { it.copy(vehicleAcrossPct = v.toInt()) }
    }
    Caption("Il mezzo sta al centro dello schermo, a questa altezza; quando si apre la colonna dei posti a destra (o la vista dello " +
        "svincolo in orizzontale) la vista scivola di lato per lasciarle spazio, finché resta aperta (Punti di interesse › dopo quanti " +
        "secondi si richiude). Zoom e inclinazione si cambiano anche in guida: tocca la mappa, poi Zoom +/− e Più/Meno incl.",
        Modifier.padding(top = 2.dp), size = 13, lines = 5)
  }
  Card("Zoom automatico") {
    ToggleRow("Rotonde", "La vista si alza sopra la rotonda e la mappa resta ferma mentre giri, poi torna com'era",
        s.roundaboutView) { v -> set { it.copy(roundaboutView = v) } }
    if (s.roundaboutView) {
      Stepper("Da quanti metri prima", s.roundaboutViewFromM.toDouble(), "m", 10.0, 40.0, 300.0, 0) { v -> set { it.copy(roundaboutViewFromM = v.toInt()) } }
      if (s.driveView == DriveView.VIEW_3D) {
        Stepper("Inclinazione nella rotonda", s.roundaboutTilt.toDouble(), "°", 1.0, 0.0, 70.0, 0) { v -> set { it.copy(roundaboutTilt = v.toInt()) } }
      }
      Stepper("Vicinanza nella rotonda", s.roundaboutZoom, "", 0.1, 15.0, 19.5, 1) { v -> set { it.copy(roundaboutZoom = v) } }
    }
    ToggleRow("Svincoli", "Alle uscite, ai bivi e alle rampe delle autostrade e superstrade la vista si alza per mostrare lo svincolo, " +
        "seguendo il mezzo, poi torna com'era", s.junctionZoomView) { v -> set { it.copy(junctionZoomView = v) } }
    ToggleRow("Anche agli incroci in città", "Le svolte sulle altre strade, da più vicino", s.junctionZoomInTown) { v ->
      set { it.copy(junctionZoomInTown = v) }
    }
    if (s.junctionZoomView || s.junctionZoomInTown) {
      if (s.junctionZoomView) {
        Stepper("Svincoli: da quanti metri prima", s.junctionZoomFromM.toDouble(), "m", 50.0, 100.0, 1000.0, 0) { v -> set { it.copy(junctionZoomFromM = v.toInt()) } }
      }
      if (s.junctionZoomInTown) {
        Stepper("Incroci: da quanti metri prima", s.junctionZoomTownFromM.toDouble(), "m", 10.0, 50.0, 300.0, 0) { v -> set { it.copy(junctionZoomTownFromM = v.toInt()) } }
      }
      if (s.driveView == DriveView.VIEW_3D) {
        Stepper("Inclinazione allo svincolo", s.junctionTilt.toDouble(), "°", 1.0, 0.0, DriveCamera.MAX_SETTING_TILT.toDouble(), 0) { v -> set { it.copy(junctionTilt = v.toInt()) } }
      }
      Stepper("Vicinanza allo svincolo", s.junctionZoom, "", 0.1, 15.0, 19.5, 1) { v -> set { it.copy(junctionZoom = v) } }
    }
    if (s.roundaboutView || s.junctionZoomView || s.junctionZoomInTown) {
      Spacer(Modifier.height(6.dp))
      Pills {
        Pill("Valori di partenza", false) {
          set {
            it.copy(roundaboutViewFromM = 120, roundaboutTilt = 43, roundaboutZoom = 17.6, junctionZoomFromM = 300, junctionTilt = 50,
                junctionZoom = 17.9, junctionZoomTownFromM = 120)
          }
        }
      }
    }
  }
  Card("Colori") {
    Pills { for (n in NightMode.entries) Pill(n.label, s.nightMode == n) { set { it.copy(nightMode = n) } } }
  }
  Card("Allerte sulla mappa") {
    ToggleRow("Mostra le allerte", "Autovelox, Tutor, semafori, varchi ZTL, zone ambientali, controlli camion, dossi e pericoli " +
        "sempre sulla mappa, anche senza navigazione: da lontano gli autovelox sono raggruppati con il loro numero, da vicino " +
        "ognuno ha la sua icona. Tocca un'icona per i dettagli.", s.alertsOnMap) { v -> set { it.copy(alertsOnMap = v) } }
    if (s.alertsOnMap) {
      Caption("Quali mostrare", Modifier.padding(top = 6.dp, bottom = 6.dp), size = 14)
      Pills {
        for (g in app.navmaster.truck.limits.AlertGroup.entries) {
          SmallPill(g.label, g.id in s.alertGroups) {
            set { st -> st.copy(alertGroups = if (g.id in st.alertGroups) st.alertGroups - g.id else st.alertGroups + g.id) }
          }
        }
      }
    }
    AlertPackRow(s, set)
  }
}

/**
 * The owner's pack of verified alerts (AlertPack): what is on the tablet, a file to import (made on
 * the computer by data/allerte.py from the folder «allerts»), whether it is announced while driving.
 */
@Composable
private fun AlertPackRow(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val version by app.navmaster.truck.limits.AlertPack.version.collectAsState()
  var info by remember { mutableStateOf<app.navmaster.truck.limits.AlertPack.Info?>(null) }
  LaunchedEffect(version) {
    info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.navmaster.truck.limits.AlertPack.info() }.getOrNull() }
  }
  var message by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  val pick = androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
      val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
          val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
          if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
      }.getOrNull() ?: (uri.lastPathSegment ?: "allerte")
      val n = runCatching { context.contentResolver.openInputStream(uri)?.use { app.navmaster.truck.limits.AlertPack.import(it, name) } ?: 0 }
          .onFailure { android.util.Log.w("NavMasterAlerts", "import: $it") }.getOrDefault(0)
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        busy = false
        message = if (n > 0) "Caricate $n allerte da $name." else "$name non è un pacchetto di allerte NavMaster (il file .sqlite preparato sul computer)."
      }
    }
  }
  Spacer(Modifier.height(10.dp))
  val i = info
  if (i != null) {
    val it = i.countries["IT"]
    Caption("Pacchetto allerte: ${i.count} allerte (${i.countries.size} Paesi" + (if (it != null) ", Italia $it" else "") +
        "), del ${i.built}. Uso personale: non viene condiviso.", color = Nm.Text, lines = 3)
    Caption("Con il pacchetto caricato autovelox e allerte vengono solo da qui, sulla mappa e in guida: gli altri elenchi non sono usati.",
        Modifier.padding(top = 4.dp), size = 13, lines = 3)
  } else {
    Caption("Pacchetto allerte: nessuno. Si carica qui il file allerte-navmaster.sqlite fatto sul computer, oppure lo si copia via cavo " +
        "in Android/data/app.navmaster.truck/files/allerte/ (viene preso al prossimo avvio).", lines = 4)
  }
  Spacer(Modifier.height(6.dp))
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    BigButton(if (i == null) "Carica il pacchetto" else "Sostituisci", Modifier.weight(1f), enabled = !busy) { pick.launch(arrayOf("*/*")) }
    if (i != null) BigButton("Elimina", Modifier.weight(0.6f), style = BtnStyle.DANGER, enabled = !busy) {
      app.navmaster.truck.limits.AlertPack.clear()
      message = "Pacchetto eliminato."
    }
  }
  message?.let { Spacer(Modifier.height(4.dp)); Caption(it, color = Nm.Text, lines = 3) }
}

@Composable
private fun PoiPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val heavy = AppGraph.profiles.garage.value.active.type.heavy
  Card("Pannello in guida") {
    Caption("La colonna dei posti a destra in guida: si apre da sola quando arrivano nuovi punti e, se non la tocchi, " +
        "dopo qualche secondo si richiude in una linguetta.", size = 13)
    Stepper("Quanti punti mostrare (0 = mai)", s.poiRailCount.toDouble(), "", 1.0, 0.0, 6.0, 0) { v -> set { it.copy(poiRailCount = v.toInt()) } }
    if (s.poiRailCount > 0) {
      Caption("Dopo quanti secondi si richiude", size = 14)
      Spacer(Modifier.height(4.dp))
      Pills {
        for (sec in listOf(3, 5, 8, 12, 20, 30, 60, 0)) {
          Pill(if (sec == 0) "Sempre" else "$sec s", s.poiRailSeconds == sec) { set { it.copy(poiRailSeconds = sec) } }
        }
      }
      ToggleRow("Nome sui segnaposti della mappa", "Accanto al simbolo del posto, con la distanza", s.poiPinNames) { v -> set { it.copy(poiPinNames = v) } }
      ToggleRow("Tocco sul segnaposto", "Apre la scheda del posto, da cui aggiungerlo come tappa", s.poiPinTap) { v -> set { it.copy(poiPinTap = v) } }
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

/**
 * The camper's own page: campsites, camper stops, service points and shops along the route (the
 * same places as in «Punti di interesse», gathered here), the place for the night.
 */
@Composable
private fun CamperPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val isCamper = AppGraph.profiles.garage.value.active.type == app.navmaster.truck.vehicle.VehicleType.CAMPER
  Card("Soste per il camper") {
    Caption("Campeggi, aree camper, parcheggi camper, punti di carico e scarico acque, negozi e officine camper di tutta " +
        "Europa, da OpenStreetMap e Overture: lungo il percorso, intorno a te o vicino alla meta (pulsante Punti di interesse). " +
        "Dentro ogni tipo puoi tenere solo le soste con corrente, acqua, scarico, docce o cani ammessi.", size = 13, lines = 6)
    if (!isCamper) Caption("Il mezzo in uso non è un camper: queste soste si vedono comunque se le accendi qui.",
        Modifier.padding(top = 6.dp), color = Nm.Amber, size = 13)
  }
  val camper = PoiCategories.groups.first { it.id == "camper" }
  PoiGroupCard(camper, s, set, openAtStart = true)
  Card("Sosta per la notte") {
    ToggleRow("Campeggi e aree camper la sera", "Dal tardo pomeriggio il pannello lungo il percorso mostra anche campeggi e aree " +
        "camper, qualunque cosa tu abbia scelto per il giorno (solo con un camper in uso).", s.camperEveningStops) { v ->
      set { it.copy(camperEveningStops = v) }
    }
    if (s.camperEveningStops) Stepper("Dalle ore", s.camperEveningFromHour.toDouble(), "", 1.0, 12.0, 22.0, 0) { v ->
      set { it.copy(camperEveningFromHour = v.toInt()) }
    }
  }
  Card("Misure del camper") {
    Caption("Altezza, larghezza, lunghezza e peso del camper si impostano nel mezzo (pulsante del veicolo): il percorso evita " +
        "sottopassi, strade strette e divieti ai camper (OpenStreetMap motorhome=no) e la voce avvisa dei limiti sulla strada.",
        size = 13, lines = 5)
  }
}

/** One group of places, folded: the header says how many are on and switches all of them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PoiGroupCard(g: PoiGroup, s: Settings, set: ((Settings) -> Settings) -> Unit, openAtStart: Boolean = g.id == "rest") {
  // the first group opens by itself, to show how the finer choices work
  var open by rememberSaveable(g.id) { mutableStateOf(openAtStart) }
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
    ToggleRow("Scarica solo con Wi-Fi", "Spento: le mappe si scaricano anche con i dati mobili", s.downloadWifiOnly) { v -> set { it.copy(downloadWifiOnly = v) } }
    BigButton("Gestisci i Paesi scaricati", Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), style = BtnStyle.SECONDARY, onClick = onRegions)
  }
  Card("Percorsi") {
    Text("Il percorso si calcola sui grafi dei Paesi scaricati, anche attraverso i confini: il valico si sceglie fra tutti " +
        "i passaggi del confine, col peso del viaggio intero. Serve il grafo di ogni Paese attraversato.", color = Nm.Muted, fontSize = 13.sp)
  }
}

@Composable
private fun PhotosPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  Card("Fonti") {
    Caption("Foto delle strade da Panoramax e KartaView" +
        (if (app.navmaster.truck.live.ApiKeys.mapillary(s).isNotBlank()) " e Mapillary" else "") +
        ": pronte, senza registrazione.", size = 13)
  }
}

@Composable
private fun AboutPage() {
  Card("Fonti dei dati") {
    Caption("Mappe, limiti, divieti, autovelox e punti di interesse © OpenStreetMap contributors (ODbL). " +
        "Punti di interesse aggiuntivi © Overture Maps Foundation (CDLA Permissive 2.0). " +
        "Foto stradali: Panoramax, KartaView, Mapillary. Immagini satellitari © Esri (uso personale). " +
        "Pendenze stimate dal terreno: Terrain Tiles su AWS (SRTM, NASA). " +
        "Pendenze del camion nel calcolo dei percorsi: produced using Copernicus WorldDEM-90 © DLR e.V. 2010-2014 and " +
        "© Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved " +
        "(in riserva Tilezen Terrain Tiles: EU-DEM © Copernicus, SRTM © NASA). " +
        "Traffico: Autobahn GmbH (dati aperti), Waze (mappa pubblica), TomTom e HERE. " +
        "Dati ufficiali: DGT (CC BY), Servei Català de Trànsit, Open Data Euskadi, NDW, Vlaams Verkeerscentrum, CITA (CC0), " +
        "Bison Futé (Licence Ouverte 2.0), Fintraffic / digitraffic.fi (CC BY 4.0), GDDKiA, Via Lietuva, Trafikverket. " +
        "Segnalazioni: autisti NavMaster via ntfy. " +
        "Waze: segnalazioni dalla mappa pubblica, solo se attivate.", size = 14, lines = 14)
  }
  Card("Avvertenze") {
    Caption("I divieti nazionali di circolazione sono indicativi: verifica sempre il calendario ufficiale. " +
        "Le criticità (curve, strade strette, limiti) vengono dai dati OpenStreetMap e vanno confermate dalla segnaletica.", size = 14, lines = 6)
  }
}

/**
 * The exits and forks of the routes driven where OpenStreetMap has no sign or no lanes (nav.MapGaps):
 * a tap opens one in the map's editor, the whole list can be sent. Added on the map, they show in
 * the junction view at the next update of the maps.
 */
@Composable
private fun MapGapsCard() {
  val gaps by app.navmaster.truck.nav.MapGaps.gaps.collectAsState()
  val context = androidx.compose.ui.platform.LocalContext.current
  var all by remember { mutableStateOf(false) }
  Card("Mappa da completare") {
    if (gaps.isEmpty()) {
      Caption("Nessun incrocio dei tuoi percorsi senza cartelli o corsie su OpenStreetMap, per ora. " +
          "Si riempie da solo a ogni viaggio: uscite e bivi dove la mappa non ha il cartello o le corsie.", size = 13, lines = 4)
      return@Card
    }
    Caption("${gaps.size} uscite e bivi dei tuoi percorsi senza cartello o senza corsie su OpenStreetMap: la vista svincolo li disegna " +
        "con il solo numero della strada o senza linee. Aggiunti sulla mappa (tocca un incrocio per aprirlo nell'editor), " +
        "compaiono al prossimo aggiornamento delle mappe.", size = 13, lines = 6)
    Spacer(Modifier.height(6.dp))
    for (g in (if (all) gaps else gaps.take(8))) {
      Column(
          Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(Nm.PanelSolid)
              .clickable {
                runCatching {
                  context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(g.editUrl))
                      .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
              }.padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Text("${g.kind} ${g.road}", color = Nm.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Manca: ${g.missing} · ${if (g.times > 1) "${g.times} viaggi" else "1 viaggio"}, l'ultimo il ${g.lastSeen}", color = Nm.Muted, fontSize = 13.sp)
      }
    }
    Pills {
      if (gaps.size > 8) Pill(if (all) "Meno" else "Tutti (${gaps.size})", false) { all = !all }
      Pill("Condividi l'elenco", false) {
        runCatching {
          val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
              .putExtra(android.content.Intent.EXTRA_SUBJECT, "Incroci da completare su OpenStreetMap")
              .putExtra(android.content.Intent.EXTRA_TEXT, app.navmaster.truck.nav.MapGaps.text(gaps))
          context.startActivity(android.content.Intent.createChooser(send, "Condividi l'elenco").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
    }
  }
}
