package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.BuildConfig
import app.navmaster.truck.live.KeyCheck
import app.navmaster.truck.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keys of the online services. TomTom and HERE are the driver's own free accounts: every driver
 * types his keys here, with the steps and a button that opens the page of the account, no password
 * needed. The other keys (openrouteservice, Trafikverket, Mapillary) stay the owner's: they open
 * with the owner's password (kept here as its SHA-256, never in clear). Every key can be tried on
 * its service at once («Verifica»). A key typed here replaces the one built into the app, when any.
 */
object KeysGate {
  /** SHA-256 of the owner's password. */
  private const val HASH = "f34d41900c55dc80e13d7cf346b769004c125170de1064398ac248fa1da0e6f2"

  /** Unlocked for this run of the app (locked again when the app is closed). */
  var unlocked by mutableStateOf(false)

  fun tryPassword(p: String): Boolean {
    unlocked = KeyCheck.sha256(p.trim()) == HASH
    return unlocked
  }
}

@Composable
fun KeysPage(s: Settings, set: ((Settings) -> Settings) -> Unit) {
  val scope = rememberCoroutineScope()
  Caption("Il traffico e la ricerca delle aziende usano i TUOI account gratuiti TomTom e HERE: creali una volta, " +
      "copia qui le due chiavi e l'app non va mai oltre il 90% delle loro quote gratuite, così non paghi niente. " +
      "Le chiavi restano solo su questo tablet. «Verifica e salva» prova la chiave sul servizio e, se funziona, la salva.", size = 13, lines = 6)
  Spacer(Modifier.height(8.dp))
  KeyRow(KeyCheck.Service.TOMTOM, s.tomtomKey, BuildConfig.TOMTOM_KEY.isNotBlank(), scope) { v -> set { it.copy(tomtomKey = v) } }
  KeyRow(KeyCheck.Service.HERE, s.hereKey, BuildConfig.HERE_KEY.isNotBlank(), scope) { v -> set { it.copy(hereKey = v) } }
  Spacer(Modifier.height(16.dp))
  // the owner's services
  if (!KeysGate.unlocked) {
    PasswordCard()
    return
  }
  Title("Altri servizi (proprietario)", size = 18)
  KeyRow(KeyCheck.Service.ORS, s.orsKey, BuildConfig.ORS_KEY.isNotBlank(), scope) { v -> set { it.copy(orsKey = v) } }
  KeyRow(KeyCheck.Service.TRAFIKVERKET, s.trafikverketKey, BuildConfig.TRAFIKVERKET_KEY.isNotBlank(), scope) { v -> set { it.copy(trafikverketKey = v) } }
  KeyRow(KeyCheck.Service.MAPILLARY, s.mapillaryToken, BuildConfig.MAPILLARY_TOKEN.isNotBlank(), scope) { v -> set { it.copy(mapillaryToken = v) } }
  Spacer(Modifier.height(12.dp))
  BigButton("Blocca di nuovo", Modifier.fillMaxWidth(), style = BtnStyle.SECONDARY) { KeysGate.unlocked = false }
}

@Composable
private fun PasswordCard() {
  var pw by remember { mutableStateOf("") }
  var wrong by remember { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Nm.Raised).border(1.dp, Nm.Line, RoundedCornerShape(18.dp)).padding(14.dp)) {
    Title("🔐 Altri servizi (proprietario)", size = 18)
    Caption("Le chiavi di openrouteservice, Trafikverket e Mapillary si vedono e si cambiano solo " +
        "con la password del proprietario.", Modifier.padding(top = 4.dp), size = 13, lines = 3)
    OutlinedTextField(
        pw, { pw = it; wrong = false }, label = { Text("Password") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = wrong,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent),
    )
    if (wrong) Caption("Password sbagliata.", color = Nm.Red, size = 13)
    BigButton("Apri", Modifier.fillMaxWidth()) { if (!KeysGate.tryPassword(pw)) wrong = true }
  }
}

@Composable
private fun KeyRow(service: KeyCheck.Service, value: String, builtIn: Boolean, scope: kotlinx.coroutines.CoroutineScope, onChange: (String) -> Unit) {
  var text by remember(service) { mutableStateOf(value) }
  var result by remember(service) { mutableStateOf<KeyCheck.Result?>(null) }
  var busy by remember(service) { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Nm.Raised)
      .border(1.dp, Nm.Line, RoundedCornerShape(16.dp)).padding(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(service.label, color = Nm.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      Caption(when {
        value.isNotBlank() -> "chiave del tablet"
        builtIn -> "chiave compilata nell'app"
        else -> "nessuna chiave"
      }, size = 12, lines = 1)
    }
    Caption("Account gratuito su ${service.where}", size = 12, lines = 1)
    service.steps?.let { steps ->
      Caption(steps, Modifier.padding(top = 6.dp), color = Nm.Text, size = 13, lines = 8)
    }
    service.signUp?.let { url ->
      val uri = LocalUriHandler.current
      BigButton("Apri ${service.where}", Modifier.fillMaxWidth().padding(top = 6.dp), style = BtnStyle.SECONDARY) {
        runCatching { uri.openUri(url) }
      }
    }
    OutlinedTextField(
        text, { text = it; result = null }, label = { Text("Chiave") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent),
    )
    Row {
      // one button: the key is tried on its service and, when it works, saved at once (drivers
      // pressed «Verifica», saw the tick and closed the page without saving)
      BigButton(if (busy) "Verifico…" else "Verifica e salva", Modifier.weight(1f), enabled = !busy && text.isNotBlank()) {
        busy = true
        scope.launch {
          val r = withContext(Dispatchers.IO) { KeyCheck.check(service, text) }
          if (r.ok && text.trim() != value) onChange(text.trim())
          result = if (r.ok) r.copy(text = r.text + " · salvata") else r
          busy = false
        }
      }
      Spacer(Modifier.width(8.dp))
      // a key that cannot be tried now (no network): saved as it is
      BigButton("Salva senza verificare", Modifier.weight(1f), style = BtnStyle.GHOST, enabled = text.trim() != value) { onChange(text.trim()) }
      if (value.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        BigButton("Togli", style = BtnStyle.GHOST) { text = ""; onChange("") }
      }
    }
    result?.let { r ->
      Caption((if (r.ok) "✓ " else "✗ ") + r.text, Modifier.padding(top = 6.dp), color = if (r.ok) Color(0xFFB9F6CA) else Nm.Red, size = 13, lines = 3)
    }
    if (service == KeyCheck.Service.TOMTOM || service == KeyCheck.Service.HERE) {
      Caption(if (service == KeyCheck.Service.TOMTOM) app.navmaster.truck.live.TomTomGuard.summary() else app.navmaster.truck.live.HereGuard.summary(),
          Modifier.padding(top = 4.dp), size = 12, lines = 2)
    }
  }
}
