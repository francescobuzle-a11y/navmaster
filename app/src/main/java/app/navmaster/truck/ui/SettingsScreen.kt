package app.navmaster.truck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.settings.NightMode
import app.navmaster.truck.settings.PoiCategories
import app.navmaster.truck.settings.TollPolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onClose: () -> Unit, onRegions: () -> Unit) {
  val s by AppGraph.settings.settings.collectAsState()
  fun set(block: (app.navmaster.truck.settings.Settings) -> app.navmaster.truck.settings.Settings) = AppGraph.settings.update(block)

  AdaptiveSheet("Impostazioni", onClose, wide = true) {
    SectionHeader("Pedaggi")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (p in TollPolicy.entries) Pill(p.label, s.tollPolicy == p) { set { it.copy(tollPolicy = p) } }
    }
    Stepper("Minuti in più che accetti per evitare il pedaggio", s.tollMaxExtraMin.toDouble(), "min", 1.0, 0.0, 30.0, 0) { v ->
      set { it.copy(tollMaxExtraMin = v.toInt()) }
    }

    SectionHeader("Guida")
    ToggleRow("Chiedimi prima degli svincoli stretti", "Ti propone l'uscita successiva se la curva è troppo stretta per il mezzo", s.askTightRamps) { v ->
      set { it.copy(askTightRamps = v) }
    }
    ToggleRow("Avvisi a voce", "Limiti, criticità, pedaggi e pause lette dalla voce", s.voiceWarnings) { v -> set { it.copy(voiceWarnings = v) } }
    ToggleRow("Promemoria tempi di guida", "Pausa dopo 4 h 30 di guida (reg. CE 561/2006) con i parcheggi adatti", s.driveTimeReminder) { v ->
      set { it.copy(driveTimeReminder = v) }
    }
    Stepper("Inclinazione della vista in guida", s.tiltDeg.toDouble(), "°", 5.0, 0.0, 60.0, 0) { v -> set { it.copy(tiltDeg = v.toInt()) } }
    SectionHeader("Mappa")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (n in NightMode.entries) Pill(n.label, s.nightMode == n) { set { it.copy(nightMode = n) } }
    }

    SectionHeader("Punti di interesse lungo il percorso")
    Caption("In guida compaiono in un pannello stretto al bordo dello schermo, che si apre da solo quando arrivano " +
        "nuovi punti e poi si richiude: resta una linguetta da toccare per riaprirlo.")
    Stepper("Quanti punti mostrare (0 = mai)", s.poiRailCount.toDouble(), "", 1.0, 0.0, 6.0, 0) { v -> set { it.copy(poiRailCount = v.toInt()) } }
    Caption("Per quanti secondi resta aperto", size = 14)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (sec in listOf(5, 8, 12, 20, 30, 60, 0)) {
        Pill(if (sec == 0) "Sempre aperto" else "$sec s", s.poiRailSeconds == sec) { set { it.copy(poiRailSeconds = sec) } }
      }
    }
    Stepper("Opacità del pannello", s.poiRailOpacity.toDouble(), "%", 10.0, 20.0, 100.0, 0) { v -> set { it.copy(poiRailOpacity = v.toInt()) } }
    PoiRailPreview(s.poiRailOpacity, Modifier.fillMaxWidth().padding(vertical = 6.dp))
    Caption("Lato dello schermo", size = 14)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (side in app.navmaster.truck.settings.PoiSide.entries) Pill(side.label, s.poiRailSide == side) { set { it.copy(poiRailSide = side) } }
    }
    Spacer(Modifier.height(10.dp))
    Caption("Categorie da mostrare", size = 14)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (c in PoiCategories.all) {
        val on = c.id in s.poiCategories
        Pill("${c.icon} ${c.label}", on) { set { it.copy(poiCategories = if (on) it.poiCategories - c.id else it.poiCategories + c.id) } }
      }
    }
    ToggleRow("Solo distributori con pompa camion", null, s.poiOnlyTruckFriendly) { v -> set { it.copy(poiOnlyTruckFriendly = v) } }
    Stepper("Distanza massima dalla strada", s.poiMaxDetourM.toDouble(), "m", 100.0, 100.0, 5000.0, 0) { v ->
      set { it.copy(poiMaxDetourM = v.toInt()) }
    }

    SectionHeader("Mappe offline")
    ToggleRow("Scarica solo con Wi-Fi", null, s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
    ToggleRow("Percorsi tra Paesi (grafo Europa)", "Usa il grafo unico europeo quando disponibile, per calcolare oltre i confini", s.useEuropeGraph) { v ->
      set { it.copy(useEuropeGraph = v) }
    }
    BigButton("Gestisci i Paesi scaricati", Modifier.fillMaxWidth().padding(top = 6.dp), style = BtnStyle.SECONDARY, onClick = onRegions)

    SectionHeader("Foto stradali")
    Caption("Panoramax e KartaView funzionano senza registrazione. Con un token gratuito di Mapillary (mapillary.com/dashboard/developers) si aggiungono anche le sue foto.")
    OutlinedTextField(
        s.mapillaryToken, { v -> set { it.copy(mapillaryToken = v.trim()) } }, label = { Text("Token Mapillary (facoltativo)") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent),
    )
    SectionHeader("Dati")
    Caption("Mappe e dati © OpenStreetMap contributors (ODbL). Immagini satellitari © Esri (uso personale). " +
        "I divieti nazionali di circolazione sono indicativi: verifica sempre il calendario ufficiale.")
    Spacer(Modifier.height(24.dp))
  }
}
