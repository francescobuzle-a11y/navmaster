package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.data.DownloadState
import app.navmaster.truck.data.InstalledRegion
import app.navmaster.truck.nav.PlannedRoute
import app.navmaster.truck.vehicle.Garage
import app.navmaster.truck.vehicle.VehicleProfile
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate

private val CardBg = Color(0xF7182028)
private val Muted = Color(0xFFAAB4BE)

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Column(modifier.clip(RoundedCornerShape(20.dp)).background(CardBg).padding(16.dp)) { content() }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, onClick: () -> Unit) {
  if (primary) {
    Button(
        onClick,
        modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NmGreenTop, contentColor = Color.White),
    ) { Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White) }
  } else {
    OutlinedButton(onClick, modifier.heightIn(min = 60.dp), shape = RoundedCornerShape(16.dp)) {
      Text(text, fontSize = 18.sp, color = Color.White)
    }
  }
}

/** The vehicle and load of this trip, always visible before leaving (tap to change). */
@Composable
fun VehicleChip(garage: Garage, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val v = garage.active
  Row(
      modifier.clip(RoundedCornerShape(16.dp)).background(CardBg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
        when (v.type.name) {
          "AUTOBUS" -> "🚌"
          "CAMPER" -> "🚐"
          else -> "🚚"
        },
        fontSize = 26.sp,
    )
    Spacer(Modifier.width(10.dp))
    Column {
      Text(v.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      Text(
          "${Fmt.metres(v.heightM)} · ${Fmt.tonnes(v.tripWeightT(garage.loadT))} · ${Fmt.metres(v.lengthM)}",
          color = Muted,
          fontSize = 13.sp,
      )
    }
  }
}

@Composable
fun VehicleSheet(garage: Garage, onSelect: (String) -> Unit, onLoad: (Double) -> Unit, onSave: (VehicleProfile) -> Unit, onClose: () -> Unit) {
  Card(Modifier.fillMaxWidth().padding(12.dp)) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      Text("Mezzo e carico del viaggio", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(8.dp))
      for (p in garage.profiles.sortedBy { it.type.ordinal }) {
        Row(Modifier.fillMaxWidth().clickable { onSelect(p.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          RadioButton(selected = p.id == garage.activeId, onClick = { onSelect(p.id) })
          Column {
            Text(p.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("${p.type.label} · ${Fmt.metres(p.heightM)} alto · ${Fmt.metres(p.lengthM)} lungo", color = Muted, fontSize = 13.sp)
          }
        }
      }
      val v = garage.active
      Spacer(Modifier.height(12.dp))
      Text(
          "Carico di questo viaggio: ${Fmt.tonnes(garage.loadT)} → peso ${Fmt.tonnes(v.tripWeightT(garage.loadT))}" +
              if (v.tripWeightT(garage.loadT) > v.maxWeightT) "  (oltre il massimo ${Fmt.tonnes(v.maxWeightT)}!)" else "",
          color = if (v.tripWeightT(garage.loadT) > v.maxWeightT) NmAmber else Color.White,
          fontSize = 16.sp,
      )
      Slider(
          value = garage.loadT.toFloat(),
          onValueChange = { onLoad((it * 2).toInt() / 2.0) },
          valueRange = 0f..(v.maxWeightT - v.tareT).coerceAtLeast(1.0).toFloat() + 5f,
      )
      Spacer(Modifier.height(8.dp))
      Text("Misure di ${v.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      ProfileEditor(v, onSave)
      Spacer(Modifier.height(12.dp))
      BigButton("Fatto", Modifier.fillMaxWidth(), onClick = onClose)
    }
  }
}

@Composable
private fun ProfileEditor(v: VehicleProfile, onSave: (VehicleProfile) -> Unit) {
  @Composable
  fun num(label: String, value: Double, onValue: (Double) -> Unit) {
    var text by remember(v.id, label) { mutableStateOf(String.format(Locale.ITALY, "%.2f", value).trimEnd('0').trimEnd(',')) }
    OutlinedTextField(
        value = text,
        onValueChange = {
          text = it
          it.replace(',', '.').toDoubleOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
  }
  @Composable
  fun flag(label: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Switch(checked = value, onCheckedChange = onValue)
    }
  }
  num("Altezza (m)", v.heightM) { onSave(v.copy(heightM = it)) }
  num("Larghezza (m)", v.widthM) { onSave(v.copy(widthM = it)) }
  num("Lunghezza (m)", v.lengthM) { onSave(v.copy(lengthM = it)) }
  num("Peso a vuoto (t)", v.tareT) { onSave(v.copy(tareT = it)) }
  num("Peso massimo ammesso (t)", v.maxWeightT) { onSave(v.copy(maxWeightT = it)) }
  num("Peso per asse (t)", v.axleLoadT) { onSave(v.copy(axleLoadT = it)) }
  num("Numero di assi", v.axleCount.toDouble()) { onSave(v.copy(axleCount = it.toInt().coerceIn(2, 10))) }
  num("Velocità massima (km/h)", v.topSpeedKmh.toDouble()) { onSave(v.copy(topSpeedKmh = it.toInt().coerceIn(30, 130))) }
  flag("Merci pericolose (ADR)", v.hazmat) { onSave(v.copy(hazmat = it)) }
  flag("Evita pedaggi", v.avoidTolls) { onSave(v.copy(avoidTolls = it)) }
  flag("Evita traghetti", v.avoidFerries) { onSave(v.copy(avoidFerries = it)) }
  flag("Evita strade sterrate", v.avoidUnpaved) { onSave(v.copy(avoidUnpaved = it)) }
}

/** The computed route before leaving: length, time and every limit met on the way. */
@Composable
fun PlannedRouteCard(planned: PlannedRoute, label: String?, onStart: () -> Unit, onSimulate: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
  Card(modifier) {
    Text(label ?: "Destinazione", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        "${Fmt.distanceText(planned.route.distance)} · ${Fmt.duration(planned.durationS)} · arrivo ${Fmt.eta(planned.durationS)}",
        color = Color.White,
        fontSize = 17.sp,
    )
    Text("Calcolato per ${planned.vehicleName}, ${Fmt.tonnes(planned.weightT)}, senza rete", color = Muted, fontSize = 13.sp)
    val blocking = planned.limits.filter { it.blocking }
    Spacer(Modifier.height(6.dp))
    if (planned.limits.isEmpty()) {
      Text("Nessun limite di altezza, peso o divieto sul percorso", color = Color(0xFF81C784), fontSize = 15.sp)
    } else {
      Text(
          if (blocking.isEmpty()) "${planned.limits.size} limiti sul percorso, tutti compatibili con il mezzo"
          else "ATTENZIONE: ${blocking.size} limiti non compatibili — verificare la segnaletica",
          color = if (blocking.isEmpty()) Color(0xFF81C784) else Color(0xFFFF8A80),
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
      )
      for (l in planned.limits.take(4)) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          LimitSign(l, Modifier.size(34.dp))
          Spacer(Modifier.width(8.dp))
          Text(
              "${l.label} a ${Fmt.distanceText(l.alongM)}" + (l.name?.let { " · $it" } ?: ""),
              color = if (l.blocking) Color(0xFFFF8A80) else Color.White,
              fontSize = 14.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      BigButton("Avvia", Modifier.weight(1.4f), onClick = onStart)
      BigButton("Simula", Modifier.weight(1f), primary = false, onClick = onSimulate)
      BigButton("Annulla", Modifier.weight(1f), primary = false, onClick = onCancel)
    }
  }
}

/** Offline packages: what is on the tablet, and the download of Italy. */
@Composable
fun RegionsCard(installed: List<InstalledRegion>, state: DownloadState, onDownload: (String, String, Boolean) -> Unit, onDelete: (String) -> Unit, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
  var wifiOnly by remember { mutableStateOf(true) }
  Card(modifier) {
    Text("Mappe offline", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Mappa, percorsi per mezzi pesanti e limiti funzionano senza rete dopo lo scaricamento.", color = Muted, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    for (r in installed) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(r.manifest.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
          Text("Dati del ${r.manifest.built} · ${r.manifest.files.sumOf { it.size } / 1_000_000} MB", color = Muted, fontSize = 13.sp)
        }
        TextButton(onClick = { onDelete(r.id) }) { Text("Elimina", color = Color(0xFFFF8A80)) }
      }
    }
    when (state) {
      is DownloadState.Running -> {
        Spacer(Modifier.height(8.dp))
        Text("${state.label}: ${state.step}", color = Color.White, fontSize = 15.sp)
        if (state.totalBytes > 0) {
          LinearProgressIndicator(progress = { state.doneBytes.toFloat() / state.totalBytes }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
          Text("${state.doneBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB", color = Muted, fontSize = 13.sp)
        } else {
          CircularProgressIndicator()
        }
      }
      is DownloadState.Failed -> Text(state.message, color = Color(0xFFFF8A80), fontSize = 14.sp)
      is DownloadState.Done -> Text("${state.label}: pronta", color = Color(0xFF81C784), fontSize = 15.sp)
      DownloadState.Idle -> Unit
    }
    if (installed.none { it.id == "italia" } && state !is DownloadState.Running) {
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Solo con Wi-Fi", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
      }
      BigButton("Scarica Italia (circa 4 GB)", Modifier.fillMaxWidth()) { onDownload("italia", "Italia", wifiOnly) }
    }
    if (onClose != null) {
      Spacer(Modifier.height(6.dp))
      BigButton("Chiudi", Modifier.fillMaxWidth(), primary = false, onClick = onClose)
    }
  }
}

/** Search box with results as you type (online) and coordinates (offline). */
@Composable
fun SearchPanel(near: GeographicCoordinate?, onPick: (Place) -> Unit, modifier: Modifier = Modifier) {
  var query by remember { mutableStateOf("") }
  var results by remember { mutableStateOf<List<Place>>(emptyList()) }
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  var job by remember { mutableStateOf<Job?>(null) }
  Column(modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = { q ->
          query = q
          job?.cancel()
          if (q.length < 3) {
            results = emptyList()
          } else {
            job = scope.launch {
              delay(350)
              busy = true
              results = Geocoder.search(q, near)
              busy = false
            }
          }
        },
        placeholder = { Text("Cerca indirizzo, città o coordinate") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg),
        trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
    )
    if (results.isNotEmpty()) {
      LazyColumn(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).heightIn(max = 360.dp)) {
        items(results) { p ->
          Column(Modifier.fillMaxWidth().clickable {
            onPick(p)
            query = ""
            results = emptyList()
          }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(p.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(p.detail, color = Muted, fontSize = 13.sp)
          }
        }
      }
    }
  }
}

/** Nothing on the tablet yet: explain and offer the download. */
@Composable
fun WelcomeCard(content: @Composable () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text("NavMaster", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    Text("Navigatore per camion, autobus e camper", color = Muted, fontSize = 16.sp)
    Spacer(Modifier.height(16.dp))
    content()
  }
}
