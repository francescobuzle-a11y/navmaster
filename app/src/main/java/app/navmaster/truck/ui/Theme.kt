package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** NavMaster design: dark glass panels, one green accent, big targets usable with gloves. */
object Nm {
  val Bg = Color(0xFF0E1216)
  val Panel = Color(0xF0171C22)
  val PanelSolid = Color(0xFF171C22)
  val Raised = Color(0xFF222932)
  val Line = Color(0x1FFFFFFF)
  val Text = Color(0xFFF2F5F7)
  val Muted = Color(0xFF9AA5B1)
  val Accent = Color(0xFF2EB85C)
  val AccentDark = Color(0xFF1B8B47)
  val Route = Color(0xFFC2189A)
  val Blue = Color(0xFF3D8BFD)
  val Amber = Color(0xFFFFB300)
  val Red = Color(0xFFE03131)
  val RedDark = Color(0xFFB71C1C)
  val Radius = 22.dp
}

@Composable
fun NmTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme = darkColorScheme(
          primary = Nm.Accent, onPrimary = Color.White, secondary = Nm.Accent, onSecondary = Color.White,
          background = Nm.Bg, surface = Nm.PanelSolid, onSurface = Nm.Text, surfaceVariant = Nm.Raised,
          onSurfaceVariant = Nm.Muted, error = Nm.Red,
      ),
      content = content,
  )
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
  Column(
      modifier.shadow(10.dp, RoundedCornerShape(Nm.Radius)).clip(RoundedCornerShape(Nm.Radius)).background(Nm.Panel)
          .border(1.dp, Nm.Line, RoundedCornerShape(Nm.Radius)).padding(padding),
      content = content,
  )
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier, size: Int = 22) {
  Text(text, color = Nm.Text, fontSize = size.sp, fontWeight = FontWeight.Bold, modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Nm.Muted, size: Int = 14, lines: Int = 3) {
  Text(text, color = color, fontSize = size.sp, modifier = modifier, maxLines = lines, overflow = TextOverflow.Ellipsis, lineHeight = (size + 5).sp)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(text.uppercase(), color = Nm.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
      modifier = modifier.padding(top = 14.dp, bottom = 6.dp))
}

enum class BtnStyle { PRIMARY, SECONDARY, DANGER, GHOST }

@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: BtnStyle = BtnStyle.PRIMARY,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  val bg = when (style) {
    BtnStyle.PRIMARY -> Brush.verticalGradient(listOf(Nm.Accent, Nm.AccentDark))
    BtnStyle.DANGER -> Brush.verticalGradient(listOf(Nm.Red, Nm.RedDark))
    BtnStyle.SECONDARY -> Brush.verticalGradient(listOf(Nm.Raised, Nm.Raised))
    BtnStyle.GHOST -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
  }
  Row(
      modifier.heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp)).background(bg)
          .then(if (style == BtnStyle.GHOST) Modifier.border(1.dp, Nm.Line, RoundedCornerShape(18.dp)) else Modifier)
          .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
  ) {
    if (icon != null) {
      Icon(icon, null, tint = if (enabled) Color.White else Nm.Muted, modifier = Modifier.size(26.dp))
      Spacer(Modifier.width(10.dp))
    }
    Text(text, color = if (enabled) Color.White else Nm.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

/** Round map button (64 dp: easy with gloves). */
@Composable
fun RoundAction(icon: ImageVector, description: String, modifier: Modifier = Modifier, container: Color = Nm.Panel,
                tint: Color = Nm.Text, size: Dp = 64.dp, onClick: () -> Unit) {
  Box(
      modifier.size(size).shadow(8.dp, CircleShape).clip(CircleShape).background(container).border(1.dp, Nm.Line, CircleShape)
          .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) { Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.45f)) }
}

/** Value with big - and + buttons; holding a button repeats. */
@Composable
fun Stepper(label: String, value: Double, unit: String, step: Double, min: Double, max: Double, decimals: Int = 2,
            modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
  val current by rememberUpdatedState(value)
  Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Caption(label, size = 14)
      Text(fmtNum(value, decimals) + " " + unit, color = Nm.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    RepeatButton(Icons.Rounded.Remove) { onChange((current - step).coerceIn(min, max).let { round(it, decimals) }) }
    Spacer(Modifier.width(10.dp))
    RepeatButton(Icons.Rounded.Add) { onChange((current + step).coerceIn(min, max).let { round(it, decimals) }) }
  }
}

private fun round(v: Double, d: Int): Double {
  var k = 1.0
  repeat(d) { k *= 10 }
  return Math.round(v * k) / k
}

fun fmtNum(v: Double, decimals: Int): String =
    String.format(java.util.Locale.ITALY, "%.${decimals}f", v).let { if (decimals > 0) it.trimEnd('0').trimEnd(',') else it }

@Composable
private fun RepeatButton(icon: ImageVector, onStep: () -> Unit) {
  var pressed by remember { mutableStateOf(false) }
  val step by rememberUpdatedState(onStep)
  LaunchedEffect(pressed) {
    if (!pressed) return@LaunchedEffect
    step()
    delay(450)
    while (pressed) {
      step()
      delay(90)
    }
  }
  Box(
      Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(Nm.Raised).border(1.dp, Nm.Line, RoundedCornerShape(16.dp))
          .pointerInput(Unit) {
            detectTapGestures(onPress = {
              pressed = true
              tryAwaitRelease()
              pressed = false
            })
          },
      contentAlignment = Alignment.Center,
  ) { Icon(icon, null, tint = Nm.Text, modifier = Modifier.size(28.dp)) }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 10.dp, horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, color = Nm.Text, fontSize = 17.sp)
      if (subtitle != null) Caption(subtitle, size = 13)
    }
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Nm.Accent, checkedThumbColor = Color.White))
  }
}

/** Selectable pill. */
@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Box(
      modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
          .background(if (selected) Nm.Accent else Nm.Raised)
          .border(1.dp, if (selected) Nm.Accent else Nm.Line, RoundedCornerShape(24.dp))
          .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
      contentAlignment = Alignment.Center,
  ) { Text(text, color = Color.White, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

/**
 * A sheet over the map: on the side in landscape (the map stays visible), from the bottom in
 * portrait. Tapping outside closes it.
 */
@Composable
fun AdaptiveSheet(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, wide: Boolean = false,
                  scroll: Boolean = true, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {},
                  content: @Composable ColumnScope.() -> Unit) {
  val cfg = LocalConfiguration.current
  val landscape = cfg.screenWidthDp > cfg.screenHeightDp
  Box(
      Modifier.fillMaxSize().background(Color(0x66000000))
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
  ) {
    val shape = if (landscape) RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp) else RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val align = if (landscape) Alignment.CenterEnd else Alignment.BottomCenter
    val sizeMod = if (landscape) Modifier.fillMaxHeight().widthIn(max = if (wide) 720.dp else 560.dp).fillMaxWidth(if (wide) 0.62f else 0.5f)
    else Modifier.fillMaxWidth().heightIn(max = (cfg.screenHeightDp * 0.9f).dp)
    Column(
        Modifier.align(align).then(sizeMod).clip(shape).background(Nm.PanelSolid)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            // the keyboard pushes the panel up instead of covering it (edge to edge: no automatic resize)
            .statusBarsPadding().navigationBarsPadding().imePadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
          RoundAction(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro", size = 52.dp, container = Nm.Raised, onClick = onBack)
          Spacer(Modifier.size(12.dp))
        }
        Title(title, Modifier.weight(1f))
        actions()
        RoundAction(Icons.Rounded.Close, "Chiudi", size = 52.dp, container = Nm.Raised, onClick = onClose)
      }
      Spacer(Modifier.size(8.dp))
      // scroll = false: the caller keeps some parts fixed (a search field) and scrolls the rest itself
      if (scroll) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
      else Column(Modifier.weight(1f, fill = false), content = content)
    }
  }
}

@Composable
fun BoxScope.Toast(text: String?, modifier: Modifier = Modifier) {
  if (text == null) return
  Text(text, color = Color.White, fontSize = 15.sp,
      modifier = modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6000000))
          .padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
fun KeyValue(key: String, value: String, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
    Caption(key, Modifier.weight(1f))
    Text(value, color = Nm.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
  }
}
