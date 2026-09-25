package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.CountryInfo
import app.navmaster.truck.data.DownloadState
import app.navmaster.truck.search.TextNorm

private fun gb(bytes: Long): String =
    if (bytes >= 1_000_000_000) String.format(java.util.Locale.ITALY, "%.1f GB", bytes / 1e9) else "${(bytes / 1e6).toInt().coerceAtLeast(1)} MB"

/** The country the driver is in (position, else mobile network), to suggest its map. */
@Composable
fun rememberCurrentCountry(lat: Double?, lon: Double?): CountryInfo? {
  val catalog by AppGraph.catalog.catalog.collectAsState()
  return remember(catalog, lat?.let { (it * 20).toInt() }, lon?.let { (it * 20).toInt() }) {
    (if (lat != null && lon != null) AppGraph.catalog.countryAt(lat, lon) else null)
        ?: AppGraph.catalog.networkCountryIso()?.let { AppGraph.catalog.countryByIso(it) }
  }
}

@Composable
fun RegionsScreen(here: CountryInfo?, onClose: () -> Unit) {
  val catalog by AppGraph.catalog.catalog.collectAsState()
  val installed by AppGraph.regions.installed.collectAsState()
  val states by AppGraph.regions.states.collectAsState()
  val settings by AppGraph.settings.settings.collectAsState()
  var query by remember { mutableStateOf("") }
  LaunchedEffect(Unit) { AppGraph.catalog.refresh() }

  AdaptiveSheet("Mappe d'Europa", onClose, wide = true) {
    Caption("Ogni Paese contiene mappa, calcolo del percorso per mezzi pesanti, limiti, indirizzi, punti di interesse e criticità: " +
        "tutto funziona senza rete. Libero sul tablet: ${gb(AppGraph.regions.freeBytes())}.")
    if (here != null) {
      SectionHeader("Ti trovi qui")
      CountryRow(here, installed.any { it.id == here.id }, states[here.id], settings.useEuropeGraph, settings.wifiOnly, highlight = true)
    }
    val inst = installed.filter { r -> r.id != here?.id }
    if (inst.isNotEmpty()) {
      SectionHeader("Scaricati")
      for (r in inst) {
        val c = catalog?.countries?.firstOrNull { it.id == r.id }
        if (c != null) CountryRow(c, true, states[c.id], settings.useEuropeGraph, settings.wifiOnly)
        else InstalledRow(r.label, r.sizeBytes, r.manifest.built) { AppGraph.regions.delete(r.id); AppGraph.engine.reset() }
      }
    }
    SectionHeader("Tutti i Paesi")
    OutlinedTextField(query, { query = it }, placeholder = { Text("Cerca un Paese") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent))
    Spacer(Modifier.height(8.dp))
    val list = catalog?.countries.orEmpty().filter { TextNorm.norm(it.name).contains(TextNorm.norm(query)) }.sortedBy { it.name }
    if (catalog == null) Caption("Collegati a internet per vedere l'elenco dei Paesi.", color = Nm.Amber)
    for (c in list) {
      if (c.id == here?.id) continue
      CountryRow(c, installed.any { it.id == c.id }, states[c.id], settings.useEuropeGraph, settings.wifiOnly)
    }
    catalog?.europeGraph?.let {
      Spacer(Modifier.height(10.dp))
      Caption(if (it.available) "Grafo Europa disponibile (${it.built}): i percorsi attraversano i confini tra i Paesi scaricati."
          else "Grafo Europa in preparazione: per ora il calcolo resta dentro il Paese di partenza.")
    }
    Spacer(Modifier.height(20.dp))
  }
}

@Composable
private fun InstalledRow(label: String, size: Long, built: String, onDelete: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(label, color = Nm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      Caption("${gb(size)} · dati del $built")
    }
    RoundAction(Icons.Rounded.DeleteOutline, "Elimina", size = 52.dp, container = Nm.Raised, onClick = onDelete)
  }
}

@Composable
fun CountryRow(c: CountryInfo, installed: Boolean, state: DownloadState?, useEurope: Boolean, wifiOnly: Boolean, highlight: Boolean = false) {
  val size = c.downloadSize(useEurope && c.europeTiles != null)
  Column(
      Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp))
          .background(if (highlight) Nm.Raised else Color.Transparent)
          .border(1.dp, if (highlight) Nm.Accent else Nm.Line, RoundedCornerShape(18.dp)).padding(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(c.flag, fontSize = 30.sp)
      Spacer(Modifier.width(12.dp))
      Column(Modifier.weight(1f)) {
        Text(c.name, color = Nm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Caption(
            when {
              installed -> "Scaricato · dati del ${c.built ?: "?"}"
              c.available -> "${gb(size)} · dati del ${c.built}" + if (c.europeTiles != null && useEurope) " · con percorsi europei" else ""
              else -> "In preparazione"
            },
            color = if (installed) Nm.Accent else Nm.Muted,
        )
      }
      when {
        installed -> RoundAction(Icons.Rounded.DeleteOutline, "Elimina", size = 52.dp, container = Nm.Raised) {
          AppGraph.regions.delete(c.id)
          AppGraph.engine.reset()
        }
        state is DownloadState.Running || state is DownloadState.Queued -> {}
        c.available -> RoundAction(Icons.Rounded.CloudDownload, "Scarica", size = 52.dp, container = Nm.Accent) {
          AppGraph.regions.download(c.id, c.name, wifiOnly, useEurope)
        }
        else -> {}
      }
    }
    when (state) {
      is DownloadState.Queued -> Caption("In coda…", color = Nm.Amber)
      is DownloadState.Running -> {
        Spacer(Modifier.height(8.dp))
        val f = if (state.totalBytes > 0) (state.doneBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Nm.Accent, trackColor = Nm.Line)
        Caption("${state.step} · ${gb(state.doneBytes)} di ${gb(state.totalBytes)}", size = 13)
      }
      is DownloadState.Failed -> Caption(state.message, color = Nm.Red)
      else -> {}
    }
  }
}

/** First start without maps: suggest the country where the driver is. */
@Composable
fun WelcomeScreen(here: CountryInfo?, onChooseOther: () -> Unit) {
  val states by AppGraph.regions.states.collectAsState()
  val settings by AppGraph.settings.settings.collectAsState()
  LaunchedEffect(Unit) { AppGraph.catalog.refresh() }
  Box(Modifier.fillMaxSize().background(Color(0xF00E1216)).statusBarsPadding(), contentAlignment = Alignment.Center) {
    Panel(Modifier.widthIn(max = 560.dp).padding(20.dp), padding = 24.dp) {
      Text("NavMaster", color = Nm.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
      Caption("Navigatore per camion, autobus e camper", size = 16)
      Spacer(Modifier.height(18.dp))
      if (here != null) {
        Title("${here.flag}  Ti trovi in ${here.name}")
        Caption("Scarica la mappa per guidare anche senza rete: percorsi per mezzi pesanti, limiti, indirizzi e punti di interesse.")
        Spacer(Modifier.height(12.dp))
        val st = states[here.id]
        if (st != null) CountryRow(here, false, st, settings.useEuropeGraph, settings.wifiOnly, highlight = true)
        else if (here.available) BigButton("Scarica ${here.name} (${gb(here.downloadSize(settings.useEuropeGraph && here.europeTiles != null))})",
            Modifier.fillMaxWidth(), Icons.Rounded.CloudDownload) {
          AppGraph.regions.download(here.id, here.name, settings.wifiOnly, settings.useEuropeGraph)
        }
        else Caption("La mappa di ${here.name} è in preparazione: riprova tra poco o scegline un'altra.", color = Nm.Amber)
      } else {
        Title("Scegli il Paese")
        Caption("Collegati a internet: l'app individua il Paese in cui ti trovi e ti propone la sua mappa.")
      }
      Spacer(Modifier.height(10.dp))
      ToggleRow("Solo con Wi-Fi", null, settings.wifiOnly) { v -> AppGraph.settings.update { it.copy(wifiOnly = v) } }
      BigButton("Tutti i Paesi d'Europa", Modifier.fillMaxWidth().padding(top = 6.dp), Icons.Rounded.Public, BtnStyle.SECONDARY, onClick = onChooseOther)
    }
  }
}
