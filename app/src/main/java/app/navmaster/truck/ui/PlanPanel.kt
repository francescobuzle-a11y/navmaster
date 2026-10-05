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
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.ExpandLess
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
import app.navmaster.truck.nav.MoreRoutes
import app.navmaster.truck.nav.PlanState
import app.navmaster.truck.nav.RouteVariant
import app.navmaster.truck.nav.TurnItem
import app.navmaster.truck.nav.TurnList
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
    collapsed: Boolean = false,
    onExpand: () -> Unit = {},
    onPickStart: () -> Unit = {},
    onClearStart: () -> Unit = {},
    /** "Altri percorsi": Valhalla's routes added to GraphHopper's. */
    onMore: () -> Unit = {},
) {
  // a departure chosen by the driver: the trip can only be simulated
  val startLabel = if (plan.start != null) "Simula" else "Avvia"
  val startIcon = if (plan.start != null) Icons.Rounded.PlayCircleOutline else Icons.Rounded.Navigation
  var showAll by remember { mutableStateOf(false) }
  if (collapsed && plan.variants.isNotEmpty()) {
    // the map is being looked at: only the routes (time, type, difficulties) and the buttons, the
    // rest of the screen stays free to move and zoom the map
    Panel(modifier, padding = 10.dp) {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.variants.forEachIndexed { i, v -> VariantChip(v, i == plan.selected) { onSelect(i) } }
        if (!plan.computing) MoreRoutesCard(plan.more, compact = true, onClick = onMore)
      }
      Spacer(Modifier.height(8.dp))
      androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // on a phone the three words do not fit: "Dettagli" becomes its arrow alone (never cut)
        val narrow = maxWidth < 430.dp
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          if (narrow) BigButton("", Modifier.width(64.dp), Icons.Rounded.ExpandLess, BtnStyle.SECONDARY, onClick = onExpand)
          else BigButton("Dettagli", Modifier.weight(1f), Icons.Rounded.ExpandLess, BtnStyle.SECONDARY, onClick = onExpand)
          BigButton(if (plan.addingStop) (if (narrow) "Premi…" else "Tieni premuto…") else "Tappa", Modifier.weight(1f),
              Icons.Rounded.AddLocationAlt, BtnStyle.SECONDARY, onClick = onAddStop)
          BigButton(startLabel, Modifier.weight(1.2f), startIcon, enabled = plan.current != null && !plan.computing, onClick = onStart)
        }
      }
    }
    return
  }
  Panel(modifier, padding = 14.dp) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
      // the departure: where the vehicle is, or a point chosen to try the trip in simulation
      Row(verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onPickStart).padding(vertical = 2.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Nm.Accent), contentAlignment = Alignment.Center) {
          Text("▶", fontSize = 12.sp, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(plan.start?.label ?: "La tua posizione", color = Nm.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
              overflow = TextOverflow.Ellipsis)
          Caption(if (plan.start != null) "Partenza scelta: solo simulazione" else "Partenza · tocca per sceglierne un'altra (simulazione)", size = 12)
        }
        if (plan.start != null) {
          Icon(Icons.Rounded.Close, "Parti dalla tua posizione", tint = Nm.Muted,
              modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClearStart).padding(8.dp))
        }
      }
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
          if (!plan.computing) MoreRoutesCard(plan.more, compact = false, onClick = onMore)
        }
        plan.routeNote?.let {
          Caption("VH = calcolato con Valhalla, non con GraphHopper: $it", color = Nm.Amber, size = 13, lines = 4,
              modifier = Modifier.padding(top = 6.dp))
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
        if (v != null) RouteTurns(v)
        if (v != null) {
          val list = v.criticalities.filter { it.severity != Severity.INFO || it.kind.name == "BAN" }
          SectionHeader(if (list.isEmpty()) "Nessuna criticità per il mezzo" else "Criticità del percorso (${list.size})")
          // grouped by kind: one pill per kind with its count, the list shows the chosen kind in route order
          val kinds = list.groupBy { it.kind }.toList().sortedWith(
              compareByDescending<Pair<app.navmaster.truck.routing.CritKind, List<Criticality>>> { p -> p.second.count { it.severity == Severity.CRITICAL } }
                  .thenByDescending { it.second.size })
          var kindSel by remember(v) { mutableStateOf(0) }
          if (kinds.size > 1) {
            TabPills(listOf("Tutte ${list.size}") + kinds.map { (k, l) -> "${k.icon} ${k.label} ${l.size}" }, kindSel.coerceAtMost(kinds.size)) { kindSel = it }
          }
          val shown = if (kindSel == 0 || kindSel > kinds.size) list else kinds[kindSel - 1].second
          val sorted = shown.sortedBy { it.startM }
          for (c in if (showAll) sorted else sorted.take(5)) CritRow(c) { onCrit(c) }
          if (sorted.size > 5) Caption(if (showAll) "Mostra meno" else "Mostra tutte (${sorted.size})", color = Nm.Accent,
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
        if (plan.start == null) {
          BigButton("Avvia", Modifier.weight(1.5f), Icons.Rounded.Navigation, enabled = plan.current != null && !plan.computing, onClick = onStart)
        }
        BigButton("Simula", Modifier.weight(if (plan.start == null) 1f else 1.5f), Icons.Rounded.PlayCircleOutline,
            if (plan.start == null) BtnStyle.SECONDARY else BtnStyle.PRIMARY, enabled = plan.current != null && !plan.computing,
            onClick = onSimulate)
        BigButton("Annulla", Modifier.weight(1f), style = BtnStyle.GHOST, onClick = onCancel)
      }
    }
  }
}

/** A route in one line: its name, how long, and a dot for how difficult (red, amber, green). */
@Composable
private fun VariantChip(v: RouteVariant, selected: Boolean, onClick: () -> Unit) {
  Row(
      Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Nm.Raised else Color(0x14FFFFFF))
          .border(2.dp, if (selected) Nm.Accent else Color.Transparent, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
          .padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (v.critical > 0) Nm.Red else if (v.warnings > 0) Nm.Amber else Nm.Accent))
    Spacer(Modifier.width(8.dp))
    Column {
      Text(Fmt.duration(v.durationS), color = Nm.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      Caption("${v.title} · ${Fmt.distanceText(v.distanceM)}" + (if (v.analysis.tollKm > 0.5) " · 💶" else "") +
          (v.source?.let { " · $it" } ?: ""), size = 12, lines = 1)
    }
  }
}

/**
 * After the routes: "Altri percorsi" asks Valhalla for its own routes (other roads, still with the
 * vehicle's measures); a spinner while it looks; a note when it finds nothing new; gone once added.
 */
@Composable
private fun MoreRoutesCard(state: MoreRoutes, compact: Boolean, onClick: () -> Unit) {
  if (state == MoreRoutes.ADDED) return
  val enabled = state == MoreRoutes.NONE
  Column(
      Modifier.widthIn(min = if (compact) 120.dp else 150.dp).heightIn(min = if (compact) 56.dp else 120.dp)
          .clip(RoundedCornerShape(if (compact) 14.dp else 18.dp)).background(Color(0x14FFFFFF))
          .border(2.dp, Nm.Accent.copy(alpha = if (enabled) 0.6f else 0.2f), RoundedCornerShape(if (compact) 14.dp else 18.dp))
          .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    when (state) {
      MoreRoutes.LOADING -> {
        CircularProgressIndicator(color = Nm.Accent, modifier = Modifier.size(24.dp))
        Caption("Cerco altre strade…", size = 13, color = Nm.Text)
      }
      MoreRoutes.NONE_FOUND -> Caption("Nessun altro percorso", size = 13, color = Nm.Muted, lines = 2)
      else -> {
        Icon(Icons.Rounded.AltRoute, null, tint = Nm.Accent, modifier = Modifier.size(if (compact) 22.dp else 30.dp))
        Text("Altri percorsi", color = Nm.Text, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Bold)
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
      v.source?.let { SourceTag(it) }
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
      // the icon turns the way the road does
      val left = c.title.contains("sinistra", ignoreCase = true)
      Text(when {
        left && c.kind.name == "JUNCTION" -> "↰"
        left && c.kind.name == "CURVE" -> "↩"
        else -> c.kind.icon
      }, fontSize = 18.sp)
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(c.title, color = Nm.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Caption("a ${Fmt.distanceText(c.startM)} · ${c.kind.label}", size = 13, lines = 1)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(severityColor(c.severity)))
  }
}

/** Who computed the route: GH (GraphHopper, with the vehicle's measures) or VH (Valhalla). */
@Composable
private fun SourceTag(source: String) {
  Spacer(Modifier.width(6.dp))
  Text(source, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
      modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (source == "GH") Nm.Blue else Color(0xFF6B7682))
          .padding(horizontal = 5.dp, vertical = 1.dp))
}

/**
 * The route before departure: the main roads in order, then its junctions and exits (all the turns
 * on request), so the driver knows at once where it goes.
 */
@Composable
private fun RouteTurns(v: RouteVariant) {
  val turns = remember(v.route) { TurnList.of(v.route) }
  val roads = remember(v.route) { TurnList.roads(v.analysis) }
  if (turns.isEmpty() && roads.isEmpty()) return
  val junctions = turns.filter { it.junction }
  var all by remember(v.route) { mutableStateOf(junctions.size < 3) }
  var expanded by remember(v.route) { mutableStateOf(false) }
  SectionHeader("Il percorso")
  if (roads.isNotEmpty()) {
    Caption("Passa da: " + roads.joinToString(" → "), color = Nm.Text, size = 15, lines = 3)
  }
  if (junctions.isNotEmpty() && junctions.size < turns.size) {
    TabPills(listOf("Svincoli e uscite ${junctions.size}", "Tutte le svolte ${turns.size}"), if (all) 1 else 0) { all = it == 1 }
  }
  val shown = if (all) turns else junctions
  for (t in if (expanded) shown else shown.take(6)) TurnRow(t)
  if (shown.size > 6) {
    Caption(if (expanded) "Mostra meno" else "Mostra tutte (${shown.size})", color = Nm.Accent,
        modifier = Modifier.clickable { expanded = !expanded }.padding(8.dp))
  }
}

@Composable
private fun TurnRow(t: TurnItem) {
  Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 3.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(if (t.junction) Color(0xFF1B8B47) else Nm.Raised), contentAlignment = Alignment.Center) {
      ManeuverGlyph(t.type, t.modifier, Color.White, Modifier.size(24.dp))
    }
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(t.text, color = Nm.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Caption("a ${Fmt.distanceText(t.atM)}" + (t.exit?.let { " · uscita $it" } ?: ""), size = 12, lines = 1)
    }
  }
}
