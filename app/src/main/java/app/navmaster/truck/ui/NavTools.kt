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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.nav.Stop

/**
 * The stops of the trip while driving: each stop or pass-through point still ahead can be removed
 * (the route is computed again from where the vehicle is), or all of them at once.
 */
@Composable
fun StopsPanel(stops: List<Stop>, onRemove: (Int) -> Unit, onRemoveAll: () -> Unit, onClose: () -> Unit) {
  Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
    Panel(Modifier.widthIn(max = 560.dp).padding(18.dp).clickable(enabled = false) {}, padding = 18.dp) {
      Title("Tappe del viaggio", size = 22)
      Caption("Tocca «Elimina» sulla tappa o sul punto da togliere: il percorso si ricalcola da dove sei.", size = 13)
      Spacer(Modifier.height(10.dp))
      for ((i, s) in stops.withIndex()) {
        val last = i == stops.size - 1
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(Nm.Raised)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(if (last) "🏁" else if (s.via) "↪" else "📍", fontSize = 22.sp, modifier = Modifier.width(34.dp))
          Column(Modifier.weight(1f)) {
            Text(s.label, color = Nm.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Caption(if (last) "Destinazione" else if (s.via) "Punto di passaggio" else "Tappa ${i + 1}", size = 13, lines = 1)
          }
          if (!last) {
            Text("Elimina", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Nm.Red).clickable { onRemove(i) }
                    .padding(horizontal = 14.dp, vertical = 10.dp))
          }
        }
      }
      Spacer(Modifier.height(10.dp))
      if (stops.size > 2) {
        BigButton("Elimina tutte le tappe", Modifier.fillMaxWidth(), style = BtnStyle.DANGER, onClick = onRemoveAll)
        Spacer(Modifier.height(8.dp))
      }
      BigButton("Chiudi", Modifier.fillMaxWidth(), style = BtnStyle.GHOST, onClick = onClose)
    }
  }
}

/**
 * Only in simulation: previous / next manoeuvre, a bit back / ahead, slower / faster.
 */
@Composable
fun SimControls(
    speed: Int,
    modifier: Modifier = Modifier,
    onPrevManeuver: () -> Unit,
    onBack: () -> Unit,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    onAhead: () -> Unit,
    onNextManeuver: () -> Unit,
) {
  Row(
      modifier.shadow(8.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(Color(0xF0231F10))
          .border(1.dp, Nm.Amber, RoundedCornerShape(28.dp)).padding(horizontal = 6.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    SimButton("⏮", "manovra\nprima", onPrevManeuver)
    SimButton("−500", "metri", onBack)
    SimButton("🐢", "più lento", onSlower)
    Column(Modifier.widthIn(min = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("$speed×", color = Nm.Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      Text("velocità", color = Nm.Muted, fontSize = 10.sp)
    }
    SimButton("🐇", "più veloce", onFaster)
    SimButton("+500", "metri", onAhead)
    SimButton("⏭", "manovra\ndopo", onNextManeuver)
  }
}

@Composable
private fun SimButton(icon: String, label: String, onClick: () -> Unit) {
  Column(
      Modifier.size(width = 58.dp, height = 52.dp).clip(RoundedCornerShape(16.dp)).background(Nm.Raised).clickable(onClick = onClick),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text(icon, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    Text(label, color = Nm.Muted, fontSize = 9.sp, lineHeight = 10.sp, textAlign = TextAlign.Center, maxLines = 2)
  }
}

/** Round button with a count, for the stops of the trip. */
@Composable
fun StopsButton(count: Int, onClick: () -> Unit) {
  Box(
      Modifier.size(56.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Nm.Panel).border(1.dp, Nm.Line, CircleShape)
          .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Text("📍", fontSize = 22.sp)
    Text("$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).clip(CircleShape).background(Nm.Red)
            .padding(horizontal = 5.dp, vertical = 1.dp))
  }
}
