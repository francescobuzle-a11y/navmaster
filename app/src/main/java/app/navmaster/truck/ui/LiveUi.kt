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
import app.navmaster.truck.live.LiveKind
import app.navmaster.truck.live.LiveRules
import app.navmaster.truck.live.RouteLiveEvent

private fun ago(min: Long): String = when {
  min < 1 -> "ora"
  min < 60 -> "$min min fa"
  else -> "${min / 60} h fa"
}

/** The next traffic event or driver's report on the route, next to the other banners. */
@Composable
fun LiveBanner(ev: RouteLiveEvent, distanceM: Double, country: String?, modifier: Modifier = Modifier) {
  val e = ev.e
  Row(
      modifier.shadow(6.dp, RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)).background(Nm.Panel).padding(6.dp)
          .widthIn(max = 360.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(e.kind.color)), contentAlignment = Alignment.Center) {
      Text(e.kind.icon, fontSize = 22.sp)
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.padding(end = 12.dp)) {
      Text(LiveRules.label(e.kind, country) + (if (e.delayS >= 60) " · +${e.delayS / 60} min" else ""),
          color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(if (distanceM < 30) "qui" else "tra " + Fmt.distanceText(distanceM), color = Color(0xFFFFD54F), fontSize = 20.sp,
          fontWeight = FontWeight.Bold)
      Text(
          if (e.official) e.source else "segnalato ${ago(e.ageMin)}" + (if (e.confirms > 0) " · ${e.confirms} conferme" else ""),
          color = Nm.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/** A driver's report just passed: is it still there? Two big answers, gone by itself. */
@Composable
fun LiveAskCard(ev: RouteLiveEvent, country: String?, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
  Column(
      modifier.shadow(10.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Nm.PanelSolid)
          .border(1.dp, Color(ev.e.kind.color), RoundedCornerShape(22.dp)).padding(14.dp).widthIn(max = 380.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(ev.e.kind.icon, fontSize = 26.sp)
      Spacer(Modifier.width(10.dp))
      Text("${LiveRules.label(ev.e.kind, country)}: c'è ancora?", color = Nm.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      BigButton("Sì", Modifier.weight(1f)) { onAnswer(true) }
      BigButton("Non più", Modifier.weight(1f), style = BtnStyle.SECONDARY) { onAnswer(false) }
    }
  }
}

/**
 * What the driver can report, in big tiles (one touch, with gloves). The police checks are not
 * offered where announcing them is forbidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportPicker(country: String?, onPick: (LiveKind) -> Unit, onClose: () -> Unit) {
  Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
    Panel(Modifier.widthIn(max = 640.dp).padding(18.dp).clickable(enabled = false) {}, padding = 18.dp) {
      Title("Segnala", size = 22)
      Caption("La segnalazione va agli altri autisti NavMaster sulla stessa strada, senza il tuo nome.", size = 13)
      Spacer(Modifier.height(10.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (k in LiveKind.entries.filter { LiveRules.allowed(it, country) }) {
          Column(
              Modifier.width(132.dp).heightIn(min = 96.dp).clip(RoundedCornerShape(18.dp)).background(Nm.Raised)
                  .border(2.dp, Color(k.color), RoundedCornerShape(18.dp)).clickable { onPick(k) }.padding(8.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
          ) {
            Text(k.icon, fontSize = 30.sp)
            Spacer(Modifier.height(4.dp))
            Text(LiveRules.label(k, country), color = Nm.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2)
          }
        }
      }
      Spacer(Modifier.height(12.dp))
      BigButton("Annulla", Modifier.fillMaxWidth(), style = BtnStyle.GHOST, onClick = onClose)
    }
  }
}
