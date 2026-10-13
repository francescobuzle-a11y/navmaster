package app.navmaster.truck.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import app.navmaster.truck.poi.Poi
import app.navmaster.truck.poi.RoutePoi
import app.navmaster.truck.settings.Settings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The right side of the driving screen (speedometer, places' column with the magnifier's panels,
 * round buttons, bar of the controls) on tablets and phones, lying down and upright, in every
 * mode of the panel: a picture of each in build/shots, and checks that nothing overlaps nor goes
 * out of the screen. The map, the manoeuvre bar and the bottom bar are plain stand-ins of their
 * size (they are drawn by MapLibre and the overlay, not by these composables).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class DriveScreenShots {
  @get:Rule val compose = createEmptyComposeRule()

  private class Device(val name: String, val qualifiers: String, val landscape: Boolean)

  private val devices = listOf(
      Device("tablet-orizz", "w1280dp-h800dp-land-mdpi", true),
      Device("tablet7-orizz", "w1024dp-h600dp-land-mdpi", true),
      Device("tablet-vert", "w800dp-h1280dp-port-mdpi", false),
      Device("telefono-vert", "w411dp-h891dp-port-mdpi", false),
      Device("telefono-orizz", "w891dp-h411dp-land-mdpi", true),
  )

  /** The modes of the panel, and what is done to reach them. */
  private enum class Case(val start: String?, val click: String?, val bar: Boolean) {
    LISTA(null, null, true),
    LISTA_SENZA_BARRA(null, null, false),
    SCEGLI("CHOOSE", null, false),
    CERCA("SEARCH", null, false),
    GASOLIO("CHOOSE", "Gasolio", false),
    POSTI_TIR("CHOOSE", "Posti TIR", false),
  }

  private fun poi(id: Int, cat: String, name: String, along: Double, off: Double, flags: Set<String> = emptySet()) =
      RoutePoi(Poi("it:$id", cat, name, null, 45.5 + id * 0.01, 10.2, flags, null, null, null), along, off)

  private val ahead = listOf(
      poi(1, "truck_parking", "Area TIR Brescia Est", 3_200.0, 40.0, setOf("hgv", "h24")),
      poi(2, "fuel", "Q8 Easy", 5_900.0, 20.0, setOf("hgv", "adblue")),
      poi(3, "services", "Area di servizio Valtrompia", 12_400.0, 60.0),
      poi(4, "food", "Trattoria del Camionista", 18_000.0, 300.0),
      poi(5, "shower", "Docce Autoporto", 26_000.0, 120.0),
  )

  /** The places the magnifier finds (as the databases would, without them). */
  private val source = object : PoiSource {
    override suspend fun quick(q: QuickPoi, traveledM: Double): PoiFound = when (q) {
      QuickPoi.FUEL -> PoiFound((0 until 6).map { poi(10 + it, "fuel", "Distributore ${it + 1}", 2_000.0 + it * 3_500, 30.0 * it, if (it % 2 == 0) setOf("hgv") else emptySet()) })
      QuickPoi.PARKING -> PoiFound(listOf(poi(30, "services", "Area servizio Ospitaletto", 3_500.0, 80.0), poi(31, "truck_parking", "Parcheggio TIR", 9_000.0, 200.0)))
      else -> PoiFound(listOf(poi(40, "toilets", "WC", 1_000.0, 900.0, emptySet())), "Nessuno lungo il percorso: i più vicini a te")
    }

    override suspend fun search(text: String, scope: SearchScope, traveledM: Double) = PoiFound(emptyList())
  }

  private val settings = Settings().copy(poiRailSeconds = 0, poiRailCount = 4)

  @Composable
  private fun Screen(d: Device, case: Case, pins: MutableList<PoiPin>) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF3B4650)).padding(10.dp)) {
      val shortSide = minOf(maxWidth, maxHeight)
      val arcFull = (shortSide * 0.36f).coerceIn(150.dp, 230.dp)
      var open by remember { mutableStateOf(false) }
      val arcW by animateDpAsState(if (open) (arcFull * 0.72f).coerceAtLeast(130.dp) else arcFull, label = "arc")
      val arc: @Composable () -> Unit = {
        Box(Modifier.testTag("arc")) { SpeedArc(87, 90, 90, 3, null, null, arcW, muted = true) }
      }
      val maneuver: @Composable (Modifier) -> Unit = { m ->
        Box(m.height(96.dp).background(Nm.PanelSolid).testTag("manovra")) {
          Text("tra 400 m a destra · SS45bis", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(16.dp))
        }
      }
      val railSize = if (open) 46.dp else 56.dp
      Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
          if (d.landscape) {
            Row(Modifier.fillMaxWidth()) {
              maneuver(Modifier.weight(1f))
              Spacer(Modifier.width(10.dp))
              arc()
            }
          } else {
            maneuver(Modifier.fillMaxWidth())
          }
          Spacer(Modifier.height(8.dp))
          Box(Modifier.weight(1f).fillMaxWidth()) {
            DriveSide(
                landscape = d.landscape,
                arcInside = !d.landscape,
                arcHeight = arcW * 0.733f,
                arcWidth = arcW,
                showPoi = true,
                railCount = 3 + (if (!case.bar) 1 else 0),
                railSize = railSize,
                barVisible = case.bar,
                bar = listOf(
                    BarButton("Vista", null, "2D", Nm.Raised) {},
                    BarButton("Satellite", Icons.Rounded.Layers, null, Nm.Raised) {},
                    BarButton("Auto", Icons.Rounded.BrightnessAuto, null, Nm.Raised) {},
                    BarButton("Voce", Icons.Rounded.VolumeUp, null, Nm.Raised) {},
                    BarButton("Termina", Icons.Rounded.Close, null, Nm.Red) {},
                ),
                barBottom = 6.dp,
                arc = arc,
                poi = { w, h ->
                  Box(Modifier.testTag("poi")) {
                    PoiColumn(
                        pois = ahead, traveledM = 0.0, source = source, settings = settings, hasHere = true, hasDestination = true,
                        secondsPerMetre = 0.045, canType = true, width = w, maxHeight = h, startMode = case.start,
                        onPoi = {}, onAll = {}, onPins = { pins.clear(); pins.addAll(it) }, onOpen = { open = it },
                    )
                  }
                },
                rail = { size ->
                  RoundBtn(size, Nm.Amber, "Segnala", onLongClick = {}, onClick = {}) {
                    Text("⚠", fontSize = (size.value * 0.46f).sp, color = Color.Black)
                  }
                  RoundBtn(size, Nm.PanelSolid, "Impostazioni", onClick = {}) {
                    Icon(Icons.Rounded.Settings, null, tint = Nm.Text, modifier = Modifier.size(size * 0.48f))
                  }
                  RoundBtn(size, Nm.PanelSolid, "Centra", onClick = {}) {
                    Icon(Icons.Rounded.MyLocation, null, tint = Nm.Text, modifier = Modifier.size(size * 0.48f))
                  }
                  if (!case.bar) {
                    RoundBtn(maxOf(size * 0.86f, 44.dp), Nm.Red, "Voce disattivata", onClick = {}) {
                      Icon(Icons.Rounded.VolumeOff, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
                    }
                  }
                },
            )
          }
        }
        Box(Modifier.fillMaxWidth().height(64.dp).background(Nm.PanelSolid).testTag("barra-sotto"))
      }
    }
  }

  private fun bounds(tag: String? = null, description: String? = null): DpRect? = runCatching {
    when {
      tag != null -> compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
      else -> compose.onNodeWithContentDescription(description!!).getUnclippedBoundsInRoot()
    }
  }.getOrNull()

  private fun DpRect.overlaps(o: DpRect, slack: Dp = 1.dp) =
      left < o.right - slack && o.left < right - slack && top < o.bottom - slack && o.top < bottom - slack

  @Test
  fun everyScreenAndMode() {
    val out = File("build/shots").apply { mkdirs() }
    val problems = mutableListOf<String>()
    val report = StringBuilder()
    for (d in devices) for (case in Case.entries) {
      val name = "${d.name}_${case.name.lowercase()}"
      RuntimeEnvironment.setQualifiers(d.qualifiers)
      val pins = mutableListOf<PoiPin>()
      val scenario = ActivityScenario.launch(ComponentActivity::class.java)
      try {
        scenario.onActivity { it.setContent { Screen(d, case, pins) } }
        compose.waitForIdle()
        case.click?.let { compose.onNodeWithText(it).performScrollTo().performClick() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // the picture
        scenario.onActivity { a ->
          val v = a.window.decorView
          val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
          v.draw(Canvas(b))
          FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }

        // nothing overlaps, nothing out of the screen
        val screen = bounds("barra-sotto")
        val parts = linkedMapOf(
            "tachimetro" to bounds("arc"),
            "manovra" to bounds("manovra"),
            "pannello" to bounds("poi"),
            "segnala" to bounds(description = "Segnala"),
            "impostazioni" to bounds(description = "Impostazioni"),
            "centra" to bounds(description = "Centra"),
            "muto" to bounds(description = "Voce disattivata"),
        )
        if (case.bar) for (l in listOf("Vista", "Satellite", "Auto", "Voce", "Termina")) parts["barra:$l"] = bounds(description = l)
        val shown = parts.filterValues { it != null }.mapValues { it.value!! }
        val keys = shown.keys.toList()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
          val a = keys[i]
          val b = keys[j]
          if (a.startsWith("barra:") && b.startsWith("barra:")) continue
          if (shown.getValue(a).overlaps(shown.getValue(b))) problems += "$name: $a si sovrappone a $b (${shown[a]} / ${shown[b]})"
        }
        if (screen != null) {
          for ((k, r) in shown) {
            if (k == "manovra") continue
            if (r.bottom > screen.top + 1.dp) problems += "$name: $k scende sotto la barra in basso (${r.bottom} > ${screen.top})"
            if (r.right > screen.right + 1.dp || r.left < screen.left - 1.dp) problems += "$name: $k esce dallo schermo ai lati ($r)"
          }
        }
        if (shown["pannello"] == null) problems += "$name: il pannello dei posti non c'è"
        // the buttons are big enough to be touched (the volume button was squeezed before)
        for ((k, r) in shown) if (k != "tachimetro" && k != "manovra" && k != "pannello" && (r.right - r.left < 40.dp || r.bottom - r.top < 40.dp)) {
          problems += "$name: $k troppo piccolo (${r.right - r.left} × ${r.bottom - r.top})"
        }
        // the quick choices find places and put them on the map
        if (case.click != null) {
          if (pins.isEmpty()) problems += "$name: nessun segnaposto sulla mappa dopo «${case.click}»"
          runCatching { compose.onNodeWithText("Cerco…").assertDoesNotExist() }.onFailure { problems += "$name: risultati ancora in caricamento" }
        }
        report.append(name).append(": ").append(shown.entries.joinToString("; ") { "${it.key} ${it.value}" }).append('\n')
      } catch (e: Throwable) {
        problems += "$name: errore ${e::class.simpleName}: ${e.message?.take(300)}"
      } finally {
        scenario.close()
      }
    }
    File(out, "prove-grafiche.txt").writeText(
        (if (problems.isEmpty()) "TUTTO OK (${devices.size * Case.entries.size} combinazioni)\n" else "PROBLEMI:\n" + problems.joinToString("\n") + "\n") +
            "\nPOSIZIONI (dp)\n" + report)
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }

  /** What a search text means ("gasolio" → fuel stations…): the words the drivers use. */
  @Test
  fun searchWords() {
    val cases = mapOf(
        "gasolio" to "fuel", "Distributore" to "fuel", "diesel" to "fuel", "parcheggio camion" to "truck_parking",
        "area di sosta" to "rest_area", "lavaggio" to "truck_wash", "gommista" to "tyres", "officina" to "truck_repair",
        "docce" to "shower", "ristorante" to "food", "wc" to "toilets", "camper" to "camper_site",
    )
    val wrong = cases.filter { (t, c) -> c !in categoriesFor(t) }.map { (t, c) -> "«$t» non trova $c (${categoriesFor(t)})" }
    assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
  }

  /** The quick choices look in the right kinds of place. */
  @Test
  fun quickChoices() {
    assertTrue("fuel" in QuickPoi.FUEL.cats && QuickPoi.FUEL.subs.isEmpty())
    assertTrue(QuickPoi.PARKING.cats.containsAll(listOf("truck_parking", "services", "rest_area")))
    // the motorway service areas are kept all: only the rest areas need the "lorries" mark
    assertTrue(QuickPoi.PARKING.subs.none { it.startsWith("services:") || it.startsWith("truck_parking:") })
  }
}
