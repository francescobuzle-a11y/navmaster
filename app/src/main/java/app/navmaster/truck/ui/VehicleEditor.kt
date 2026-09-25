package app.navmaster.truck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.navmaster.truck.AppGraph
import app.navmaster.truck.vehicle.AdrTunnel
import app.navmaster.truck.vehicle.VehicleProfile
import app.navmaster.truck.vehicle.VehicleType

/** The vehicles of the tablet: choose one for the trip, set its load, edit everything. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleEditor(onClose: () -> Unit) {
  val garage by AppGraph.profiles.garage.collectAsState()
  var editingId by remember { mutableStateOf(garage.activeId) }
  var advanced by remember { mutableStateOf(false) }
  val v = garage.profiles.firstOrNull { it.id == editingId } ?: garage.active
  fun save(p: VehicleProfile) = AppGraph.profiles.save(p)

  AdaptiveSheet("Il tuo mezzo", onClose, wide = true) {
    // saved vehicles
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      for (p in garage.profiles) {
        val sel = p.id == editingId
        val active = p.id == garage.activeId
        Column(
            Modifier.widthIn(min = 150.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) Nm.Raised else Color.Transparent)
                .border(2.dp, if (active) Nm.Accent else Nm.Line, RoundedCornerShape(18.dp)).clickable { editingId = p.id }.padding(12.dp),
        ) {
          Text(p.type.icon + "  " + p.name, color = Nm.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          Caption("${fmtNum(p.heightM, 2)} m · ${fmtNum(p.lengthM, 2)} m · ${fmtNum(p.maxWeightT, 1)} t", size = 13)
          if (active) Caption("In uso", color = Nm.Accent, size = 12)
        }
      }
      Column(
          Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, Nm.Line, RoundedCornerShape(18.dp)).clickable {
            val n = VehicleProfile.defaults().first().copy(id = AppGraph.profiles.newId(), name = "Nuovo mezzo")
            save(n)
            editingId = n.id
          }.padding(horizontal = 18.dp, vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        androidx.compose.material3.Icon(Icons.Rounded.Add, null, tint = Nm.Accent)
        Caption("Nuovo", color = Nm.Text)
      }
    }

    Spacer(Modifier.height(12.dp))
    VehicleDrawing(v, Modifier.fillMaxWidth().height(170.dp))

    if (v.id != garage.activeId) {
      BigButton("Usa questo mezzo", Modifier.fillMaxWidth().padding(top = 8.dp), Icons.Rounded.Check) { AppGraph.profiles.select(v.id) }
    }

    SectionHeader("Carico di questo viaggio")
    Stepper("Merce a bordo", garage.loadT, "t", 0.5, 0.0, (v.maxWeightT - v.tareT).coerceAtLeast(0.5), 1) { AppGraph.profiles.setLoad(it) }
    KeyValue("Peso totale usato per il calcolo", Fmt.tonnes(v.tripWeightT(garage.loadT)))

    SectionHeader("Tipo")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (t in VehicleType.entries) {
        Pill(t.icon + " " + t.label, v.type == t) { save(VehicleProfile.withTypicalGeometry(v.copy(type = t))) }
      }
    }
    OutlinedTextField(
        v.name, { save(v.copy(name = it.take(40))) }, label = { Text("Nome") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Nm.Text, unfocusedTextColor = Nm.Text, focusedBorderColor = Nm.Accent),
    )

    SectionHeader("Misure")
    Stepper("Altezza", v.heightM, "m", 0.05, 1.8, 4.9) { save(v.copy(heightM = it)) }
    Stepper("Larghezza", v.widthM, "m", 0.05, 1.6, 3.5) { save(v.copy(widthM = it)) }
    Stepper("Lunghezza", v.lengthM, "m", 0.1, 4.0, 25.25, 1) { save(VehicleProfile.withTypicalGeometry(v.copy(lengthM = it))) }

    SectionHeader("Pesi e assi")
    Stepper("Tara (a vuoto)", v.tareT, "t", 0.5, 1.5, 40.0, 1) { save(v.copy(tareT = it)) }
    Stepper("Massa complessiva a pieno carico", v.maxWeightT, "t", 0.5, 2.0, 60.0, 1) { save(v.copy(maxWeightT = it)) }
    Stepper("Peso massimo per asse", v.axleLoadT, "t", 0.5, 1.0, 13.0, 1) { save(v.copy(axleLoadT = it)) }
    Stepper("Numero di assi", v.axleCount.toDouble(), "", 1.0, 2.0, 9.0, 0) { save(v.copy(axleCount = it.toInt())) }

    SectionHeader("Merci pericolose (ADR)")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      for (a in AdrTunnel.entries) Pill(a.label, v.adr == a) { save(v.copy(adr = a)) }
    }
    ToggleRow("Merci inquinanti per le acque", "Evita le strade vietate a queste merci", v.hazmatWater) { save(v.copy(hazmatWater = it)) }

    SectionHeader("Preferenze di percorso")
    Stepper("Velocità massima del mezzo", v.topSpeedKmh.toDouble(), "km/h", 5.0, 40.0, 130.0, 0) { save(v.copy(topSpeedKmh = it.toInt())) }
    ToggleRow("Preferisci le strade per mezzi pesanti", null, v.preferTruckRoutes) { save(v.copy(preferTruckRoutes = it)) }
    ToggleRow("Evita i traghetti", null, v.avoidFerries) { save(v.copy(avoidFerries = it)) }
    ToggleRow("Evita le strade sterrate", null, v.avoidUnpaved) { save(v.copy(avoidUnpaved = it)) }

    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).clickable { advanced = !advanced }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
      androidx.compose.material3.Icon(Icons.Rounded.Tune, null, tint = Nm.Muted)
      Spacer(Modifier.width(8.dp))
      Text("Geometria di sterzata (per curve e svincoli stretti)", color = Nm.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
      Caption(if (advanced) "Chiudi" else "Apri", color = Nm.Accent)
    }
    if (advanced) {
      Caption("Servono a calcolare quanto il rimorchio «taglia» le curve. Se non li conosci lascia i valori tipici.")
      Stepper("Passo (asse anteriore → asse motore)", v.wheelbaseM, "m", 0.1, 2.0, 8.0, 1) { save(v.copy(wheelbaseM = it)) }
      if (v.type == VehicleType.AUTOARTICOLATO || v.type == VehicleType.AUTOTRENO) {
        Stepper(if (v.type == VehicleType.AUTOARTICOLATO) "Ralla → centro assi semirimorchio" else "Gancio → assi rimorchio",
            v.trailerWheelbaseM, "m", 0.1, 3.0, 12.0, 1) { save(v.copy(trailerWheelbaseM = it)) }
        Stepper(if (v.type == VehicleType.AUTOARTICOLATO) "Ralla davanti all'asse motore" else "Gancio dietro l'asse motore",
            if (v.type == VehicleType.AUTOARTICOLATO) -v.couplingOffsetM else v.couplingOffsetM, "m", 0.05, 0.0, 4.0, 2) {
          save(v.copy(couplingOffsetM = if (v.type == VehicleType.AUTOARTICOLATO) -it else it))
        }
      }
      Stepper("Sbalzo anteriore", v.frontOverhangM, "m", 0.1, 0.5, 3.5, 1) { save(v.copy(frontOverhangM = it)) }
      Stepper("Raggio di sterzata esterno", v.turnRadiusM, "m", 0.25, 5.0, 15.0, 2) { save(v.copy(turnRadiusM = it)) }
      BigButton("Valori tipici per questo mezzo", Modifier.fillMaxWidth().padding(top = 6.dp), style = BtnStyle.GHOST) {
        save(VehicleProfile.withTypicalGeometry(v))
      }
    }
    if (garage.profiles.size > 1) {
      BigButton("Elimina questo mezzo", Modifier.fillMaxWidth().padding(top = 16.dp), Icons.Rounded.DeleteOutline, BtnStyle.GHOST) {
        AppGraph.profiles.delete(v.id)
        editingId = AppGraph.profiles.garage.value.activeId
      }
    }
    Spacer(Modifier.height(20.dp))
  }
}

/** Side view of the vehicle with its measures, drawn to scale from the profile. */
@Composable
fun VehicleDrawing(v: VehicleProfile, modifier: Modifier = Modifier) {
  Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(Nm.Raised)) {
    val padL = 24f
    val padR = 90f
    val ground = size.height - 42f
    val scale = minOf((size.width - padL - padR) / v.lengthM.toFloat(), (ground - 18f) / 4.9f)
    fun x(m: Double) = padL + (m * scale).toFloat()
    fun y(m: Double) = ground - (m * scale).toFloat()
    val body = Color(0xFFDDE3E8)
    val cab = Nm.Accent
    val wheel = Color(0xFF0B0E11)
    val h = v.heightM
    val len = v.lengthM
    val wheelR = (0.5 * scale).toFloat()
    val axles = mutableListOf<Double>()
    when (v.type) {
      VehicleType.AUTOARTICOLATO -> {
        val kp = v.frontOverhangM + v.wheelbaseM + v.couplingOffsetM
        // semi-trailer box from 1.6 m before the kingpin to the rear
        box(x(kp - 1.6), y(h), x(len), y(1.15), body)
        cabShape(x(0.0), y(minOf(3.4, h - 0.2)), x(2.4), y(0.6), cab)
        chassis(x(0.2), x(kp + 0.8), y(0.95))
        axles += v.frontOverhangM; axles += v.frontOverhangM + v.wheelbaseM
        val t = kp + v.trailerWheelbaseM
        axles += t - 1.31; axles += t; axles += t + 1.31
      }
      VehicleType.AUTOTRENO -> {
        val truckLen = v.frontOverhangM + v.wheelbaseM + v.couplingOffsetM + 0.3
        box(x(2.5), y(h), x(truckLen), y(1.15), body)
        cabShape(x(0.0), y(minOf(3.4, h - 0.2)), x(2.4), y(0.6), cab)
        val tStart = truckLen + 1.2
        box(x(tStart), y(h), x(len), y(1.15), body)
        axles += v.frontOverhangM; axles += v.frontOverhangM + v.wheelbaseM
        axles += tStart + 1.5; axles += len - 1.5
      }
      VehicleType.MOTRICE -> {
        box(x(2.5), y(h), x(len), y(1.15), body)
        cabShape(x(0.0), y(minOf(3.4, h - 0.2)), x(2.4), y(0.6), cab)
        axles += v.frontOverhangM; axles += v.frontOverhangM + v.wheelbaseM
        if (v.axleCount >= 3) axles += v.frontOverhangM + v.wheelbaseM + 1.35
      }
      VehicleType.AUTOBUS -> {
        box(x(0.0), y(h), x(len), y(0.45), cab)
        var wx = 1.2
        while (wx < len - 1.0) {
          drawRoundRect(Color(0xFF12324A), Offset(x(wx), y(h - 0.5)), Size((1.1 * scale).toFloat(), (1.0 * scale).toFloat()), CornerRadius(6f))
          wx += 1.45
        }
        axles += v.frontOverhangM; axles += v.frontOverhangM + v.wheelbaseM
      }
      VehicleType.CAMPER, VehicleType.FURGONE -> {
        box(x(0.9), y(h), x(len), y(0.5), body)
        cabShape(x(0.0), y(minOf(2.3, h)), x(1.9), y(0.5), cab)
        axles += v.frontOverhangM; axles += v.frontOverhangM + v.wheelbaseM
      }
    }
    for (a in axles) drawCircle(wheel, wheelR, Offset(x(a), ground - wheelR))
    drawLine(Color(0x66FFFFFF), Offset(0f, ground), Offset(size.width, ground), 2f)
    // measures
    val dim = Nm.Amber
    drawLine(dim, Offset(x(0.0), ground + 20f), Offset(x(len), ground + 20f), 3f)
    drawLine(dim, Offset(x(len) + 18f, y(0.0)), Offset(x(len) + 18f, y(h)), 3f)
    text("${fmtNum(len, 2)} m", (x(0.0) + x(len)) / 2 - 40f, ground + 40f, dim)
    text("${fmtNum(h, 2)} m", x(len) + 26f, (y(0.0) + y(h)) / 2, dim)
    text("↔ ${fmtNum(v.widthM, 2)} m", x(len) + 26f, (y(0.0) + y(h)) / 2 + 36f, Nm.Muted)
  }
}

private fun DrawScope.box(l: Float, t: Float, r: Float, b: Float, c: Color) {
  drawRoundRect(c, Offset(l, t), Size(r - l, b - t), CornerRadius(10f))
  drawRoundRect(Color(0x33000000), Offset(l, t), Size(r - l, b - t), CornerRadius(10f), style = Stroke(2f))
}

private fun DrawScope.cabShape(l: Float, t: Float, r: Float, b: Float, c: Color) {
  drawRoundRect(c, Offset(l, t), Size(r - l, b - t), CornerRadius(18f))
  drawRoundRect(Color(0xFF12324A), Offset(l + (r - l) * 0.12f, t + (b - t) * 0.12f), Size((r - l) * 0.5f, (b - t) * 0.32f), CornerRadius(8f))
}

private fun DrawScope.chassis(l: Float, r: Float, y: Float) {
  drawLine(Color(0xFF2A3139), Offset(l, y), Offset(r, y), 10f)
}

private fun DrawScope.text(s: String, x: Float, y: Float, c: Color) {
  val p = android.graphics.Paint().apply {
    color = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    textSize = 30f
    isAntiAlias = true
    isFakeBoldText = true
  }
  drawContext.canvas.nativeCanvas.drawText(s, x, y, p)
}
