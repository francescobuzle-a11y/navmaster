package app.navmaster.truck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.routing.RouteProgress
import kotlinx.coroutines.delay

/**
 * The bar of the search of the routes: what is being done (the engine's phases), how far it has gone
 * (RouteProgress: the phases done and the time expected) and the seconds so far.
 */
@Composable
fun SearchProgress(vehicleName: String, modifier: Modifier = Modifier, compact: Boolean = false) {
  val st by RouteProgress.state.collectAsState()
  var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
  LaunchedEffect(st?.startedAt, st?.done) {
    while (st?.done == false) {
      now = android.os.SystemClock.elapsedRealtime()
      delay(100)
    }
    now = android.os.SystemClock.elapsedRealtime()
  }
  val s = st
  val f = s?.fraction(now) ?: 0f
  val seconds = s?.let { ((now - it.startedAt) / 1000).coerceAtLeast(0) } ?: 0
  Column(modifier.padding(vertical = if (compact) 2.dp else 10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("${(f * 100).toInt()} %", color = Nm.Accent, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
      Caption(s?.phase ?: "Calcolo il percorso…", Modifier.weight(1f), color = Nm.Text, size = if (compact) 12 else 15, lines = 2)
      Spacer(Modifier.width(8.dp))
      Caption("$seconds s", size = if (compact) 11 else 13)
    }
    Spacer(Modifier.height(if (compact) 5.dp else 8.dp))
    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(if (compact) 6.dp else 8.dp).clip(RoundedCornerShape(4.dp)),
        color = Nm.Accent, trackColor = Nm.Line)
    if (!compact) Caption("Percorsi per $vehicleName: limiti, pedaggi, svincoli e strade strette del mezzo", Modifier.padding(top = 6.dp), size = 13)
  }
}
