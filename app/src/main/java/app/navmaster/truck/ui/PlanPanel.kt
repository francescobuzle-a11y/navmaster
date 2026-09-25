package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.nav.PlanState
import app.navmaster.truck.nav.RouteVariant
import app.navmaster.truck.routing.Criticality
import app.navmaster.truck.routing.Severity

fun severityColor(s: Severity): Color = when (s) {
  Severity.CRITICAL -> Nm.Red
  Severity.WARN -> Nm.Amber
  Severity.INFO -> Nm.Blue
}

/** Route choice before departure: the variants, the advice on tolls, the difficulties of the route. */
@Composable
fun PlanPanel(
    plan: PlanState,
    vehicleName: String,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
    onCrit: (Criticality) -> Unit,
    onUnavoid: (Criticality) -> Unit,
    onAddStop: () -> Unit,
    onRemoveStop: (Int) -> Unit,
    onRemoveArea: (Int) -> Unit = {},
    onStart: () -> Unit,
    onSimulate: () -> Unit,
    onCancel: () -> Unit,
) {
  var showAll by remember { mutableStateOf(false) }
  Panel(modifier, padding = 14.dp) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      // stops
      for ((i, s) in plan.stops.withIndex()) {
        val last = i == plan.stops.size - 1
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
          Box(Modifier.size(28.dp).clip(CircleShape).background(if (last) Nm.Red else if (s.via) Nm.Accent else Nm.Blue),
              contentAlignment = Alignment.Center) {
            Text(if (last) "🏁" else if (s.via) "↪" else "${i + 1}", fontSize = 13.sp, color = Color.White)
          }
          Spacer(Modifier.width(10.dp))
          Column(Modifier.weight(1f)) {
            Text(s.label, color = Nm.Text, fontSize = if (last) 20.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (s.via && !last) Caption("Passaggio, senza fermata", size = 12)
          }
          if (plan.stops.size > 1) {
            Icon(Icons.Rounded.Close, "Togli", tint = Nm.Muted, modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onRemoveStop(i) }.padding(8.dp))
          }
        }
      }
      Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onAddStop).padding(vertical = 6.dp, horizontal = 2.dp),
          verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AddLocationAlt, null, tint = if (plan.addingStop) Nm.Amber else Nm.Accent)
        Spacer(Modifier.width(6.dp))
        Caption(if (plan.addingStop) "Tieni premuto sulla mappa dove vuoi passare" else "Aggiungi una tappa · oppure tieni premuto sulla mappa",
            color = if (plan.addingStop) Nm.Amber else Nm.Accent)
      }

      if (plan.computing) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
          CircularProgressIndicator(color = Nm.Accent, modifier = Modifier.size(28.dp))
          Spacer(Modifier.width(12.dp))
          Caption("Percorsi per $vehicleName: controllo limiti, pedaggi, svincoli e strade strette…", color = Nm.Text)
        }
      }
      plan.error?.let { Caption(it, color = Nm.Red, size = 15) }

      if (plan.variants.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          plan.variants.forEachIndexed { i, v -> VariantCard(v, i == plan.selected) { onSelect(i) } }
        }
        plan.advice?.let {
          Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x332EB85C)).padding(10.dp),
              verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, null, tint = Nm.Accent)
            Spacer(Modifier.width(8.dp))
            Caption(it, color = Nm.Text, size = 15, lines = 4)
          }
        }
        val v = plan.current
        if (v != null) {
          val list = v.criticalities.filter { it.severity != Severity.INFO || it.kind.name == "BAN" }
          SectionHeader(if (list.isEmpty()) "Nessuna criticità per il mezzo" else "Criticità del percorso (${list.size})")
          for (c in if (showAll) list else list.take(5)) CritRow(c) { onCrit(c) }
          if (list.size > 5) Caption(if (showAll) "Mostra meno" else "Mostra tutte (${list.size})", color = Nm.Accent,
              modifier = Modifier.clickable { showAll = !showAll }.padding(8.dp))
          if (plan.avoidAreas.isNotEmpty()) {
            SectionHeader("Zone che eviti")
            for ((i, z) in plan.avoidAreas.withIndex()) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("⛔ Zona segnata sulla mappa (${"%.4f".format(java.util.Locale.ROOT, z.lat)}, ${"%.4f".format(java.util.Locale.ROOT, z.lng)})",
                    Modifier.weight(1f), color = Nm.Text)
                Caption("Togli", color = Nm.Accent, modifier = Modifier.clickable { onRemoveArea(i) }.padding(8.dp))
              }
            }
          }
          if (plan.avoided.isNotEmpty()) {
            SectionHeader("Punti che eviti")
            for (a in plan.avoided) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("⛔ ${a.title}", Modifier.weight(1f), color = Nm.Text)
                Caption("Ripristina", color = Nm.Accent, modifier = Modifier.clickable { onUnavoid(a) }.padding(8.dp))
              }
            }
          }
        }
      }
      Spacer(Modifier.height(10.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("Avvia", Modifier.weight(1.5f), Icons.Rounded.Navigation, enabled = plan.current != null && !plan.computing, onClick = onStart)
        BigButton("Simula", Modifier.weight(1f), Icons.Rounded.PlayCircleOutline, BtnStyle.SECONDARY, enabled = plan.current != null && !plan.computing,
            onClick = onSimulate)
        BigButton("Annulla", Modifier.weight(1f), style = BtnStyle.GHOST, onClick = onCancel)
      }
    }
  }
}

@Composable
private fun VariantCard(v: RouteVariant, selected: Boolean, onClick: () -> Unit) {
  Column(
      Modifier.widthIn(min = 170.dp).clip(RoundedCornerShape(18.dp)).background(if (selected) Nm.Raised else Color(0x14FFFFFF))
          .border(2.dp, if (selected) Nm.Accent else Color.Transparent, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Caption(v.title, color = if (selected) Nm.Accent else Nm.Muted, size = 13)
      if (v.recommended) {
        Spacer(Modifier.width(6.dp))
        Text("CONSIGLIATO", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Nm.Accent).padding(horizontal = 5.dp, vertical = 1.dp))
      }
    }
    Text(Fmt.duration(v.durationS), color = Nm.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Caption("${Fmt.distanceText(v.distanceM)} · arrivo ${Fmt.eta(v.durationS)}", size = 13)
    Caption(if (v.analysis.tollKm > 0.5) "💶 ${v.analysis.tollKm.toInt()} km a pedaggio" else "✓ senza pedaggi", size = 13,
        color = if (v.analysis.tollKm > 0.5) Nm.Amber else Nm.Accent)
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      if (v.critical > 0) Badge("${v.critical} critiche", Nm.Red)
      if (v.warnings > 0) Badge("${v.warnings} attenzione", Nm.Amber)
      if (v.critical == 0 && v.warnings == 0) Badge("nessuna criticità", Nm.Accent)
    }
  }
}

@Composable
fun Badge(text: String, color: Color) {
  Text(text, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
fun CritRow(c: Criticality, onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(severityColor(c.severity).copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
      Text(c.kind.icon, fontSize = 18.sp)
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(c.title, color = Nm.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Caption("a ${Fmt.distanceText(c.startM)} · ${c.kind.label}", size = 13, lines = 1)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(severityColor(c.severity)))
  }
}
