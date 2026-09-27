package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** What a field of the bottom bar can show (as on the dedicated navigators, the driver chooses). */
enum class TripField(val label: String) {
  SPEED("Velocità"),
  ARRIVAL("Arrivo"),
  DIST_LEFT("Distanza"),
  TIME_LEFT("Tempo"),
  NEXT_STOP("Alla tappa"),
  NEXT_STOP_ARRIVAL("Arrivo tappa"),
  CLOCK("Ora"),
  DRIVE_LEFT("Guida rimasta"),
  HEADING("Direzione"),
  ROAD("Strada");

  companion object {
    fun of(name: String): TripField? = entries.firstOrNull { it.name == name }
  }
}

/** The facts the bar can show. */
data class TripData(
    val speedKmh: Int?,
    val limitKmh: Int?,
    val toleranceKmh: Int,
    val remainingM: Double?,
    val remainingS: Double?,
    /** Distance and time to the next stop on the way, null without stops (then: the destination). */
    val nextStopM: Double?,
    val nextStopS: Double?,
    val drivenS: Long,
    val headingDeg: Double?,
    val road: String?,
)

private fun hm(s: Double): String {
  val min = (s / 60.0).roundToInt().coerceAtLeast(0)
  return if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(min % 60)}"
}

private fun compass(deg: Double): String {
  val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
  return names[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
}

/** Value and unit of a field (the label is the field's own, or says what the value refers to). */
private fun valueOf(f: TripField, d: TripData): Triple<String, String, String> {
  val none = Triple("–", "", f.label)
  return when (f) {
    TripField.SPEED -> d.speedKmh?.let { Triple("$it", "km/h", f.label) } ?: Triple("0", "km/h", f.label)
    TripField.ARRIVAL -> d.remainingS?.let { Triple(Fmt.eta(it), "", f.label) } ?: none
    TripField.DIST_LEFT -> d.remainingM?.let { Fmt.distance(it).let { x -> Triple(x.value, x.unit, f.label) } } ?: none
    TripField.TIME_LEFT -> d.remainingS?.let { Triple(hm(it), "", f.label) } ?: none
    TripField.NEXT_STOP -> {
      val m = d.nextStopM ?: d.remainingM
      m?.let { Fmt.distance(it).let { x -> Triple(x.value, x.unit, if (d.nextStopM != null) "Alla tappa" else "All'arrivo") } } ?: none
    }
    TripField.NEXT_STOP_ARRIVAL -> {
      val s = d.nextStopS ?: d.remainingS
      s?.let { Triple(Fmt.eta(it), "", if (d.nextStopS != null) "Arrivo tappa" else "Arrivo") } ?: none
    }
    TripField.CLOCK -> Triple(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), "", f.label)
    TripField.DRIVE_LEFT -> Triple(hm((4.5 * 3600 - d.drivenS).coerceAtLeast(0.0)), "", "Guida rimasta")
    TripField.HEADING -> d.headingDeg?.let { Triple(compass(it), "${it.roundToInt()}°", f.label) } ?: none
    TripField.ROAD -> Triple(d.road?.takeIf { it.isNotBlank() } ?: "–", "", f.label)
  }
}

/**
 * The bottom bar while driving: three or four data fields, chosen by the driver (touch one to
 * change it), with the name of the road above them.
 */
@Composable
fun TripBar(fields: List<TripField>, data: TripData, onChange: (Int, TripField) -> Unit, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier) {
    val count = if (maxWidth < 380.dp) 3 else 4
    val shown = fields.take(count)
    Column(
        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(NmPanel)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
      val road = data.road?.takeIf { it.isNotBlank() && TripField.ROAD !in shown }
      if (road != null) {
        Text(road, color = Color(0xCCFFFFFF), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      }
      Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { i, f ->
          if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(Color(0x33FFFFFF)))
          Field(f, data, Modifier.weight(1f).fillMaxHeight()) { onChange(i, it) }
        }
      }
    }
  }
}

@Composable
private fun Field(f: TripField, d: TripData, modifier: Modifier, onPick: (TripField) -> Unit) {
  var open by remember { mutableStateOf(false) }
  val (value, unit, label) = valueOf(f, d)
  val over = f == TripField.SPEED && d.speedKmh != null && d.limitKmh != null && d.speedKmh > d.limitKmh + d.toleranceKmh
  Box(
      modifier.clip(RoundedCornerShape(12.dp)).background(if (over) NmRed else Color.Transparent).clickable { open = true }
          .padding(horizontal = 4.dp),
      contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = Color.White, fontSize = if (f == TripField.ROAD) 15.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            softWrap = false, overflow = TextOverflow.Ellipsis)
        if (unit.isNotEmpty()) Text(" $unit", color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
      }
      Text(label, color = Color(0x99FFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      for (option in TripField.entries) {
        DropdownMenuItem(
            text = { Text((if (option == f) "✓ " else "") + option.label, fontWeight = if (option == f) FontWeight.Bold else FontWeight.Normal) },
            onClick = {
              open = false
              onPick(option)
            },
        )
      }
    }
  }
}
