package app.navmaster.truck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import app.navmaster.truck.AppGraph
import app.navmaster.truck.R
import kotlinx.coroutines.delay

/**
 * The start screen: over the map for the few seconds the engine's graphs take to warm up
 * (AppGraph.startup), with the bar and the country being prepared. Gone when the warm-up is over,
 * or after [MAX_S] seconds anyway (the warm-up goes on behind). Shown once per start
 * of the app, not when the activity is made again (the state is already done).
 */
@Composable
fun StartupScreen() {
  val state by AppGraph.startup.collectAsState()
  var skipped by remember { mutableStateOf(false) }
  val s = state ?: return
  if (s.done || skipped) return
  LaunchedEffect(Unit) { delay(MAX_S * 1000L); skipped = true }
  Box(
      Modifier.fillMaxSize().background(Nm.Bg),
      contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(360.dp)) {
      Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(132.dp).clip(RoundedCornerShape(28.dp)))
      Spacer(Modifier.height(8.dp))
      Text("NavMaster", color = Nm.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(28.dp))
      LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
          color = Nm.Accent, trackColor = Nm.Raised)
      Spacer(Modifier.height(14.dp))
      Text(s.label, color = Nm.Text, fontSize = 18.sp)
      Spacer(Modifier.height(6.dp))
      Text("Carico mappe e motore di calcolo", color = Nm.Muted, fontSize = 13.sp)
    }
  }
}

private const val MAX_S = 20
