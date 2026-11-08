package app.navmaster.truck.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.navmaster.truck.ui.JvCases.edge
import app.navmaster.truck.ui.JvCases.line
import app.navmaster.truck.ui.JvCases.road
import app.navmaster.truck.ui.JvCases.route
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
 * The junction view on four made-up junctions (a motorway exit with its signs, a motorway fork
 * known from the lane counts with signs over the lanes, a right turn and a slight right turn in
 * town with painted arrows and no sign on the map), by day and by night, wide and tall: a picture
 * of each in build/shots, and checks that the picture has its sign and its lit lanes on the side
 * of the manoeuvre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class JunctionViewShots {
  @get:Rule val compose = createEmptyComposeRule()

  /** How many pixels of the lit lanes' blue are in the left and in the right half of the picture's road (below the sign). */
  private fun litSides(img: ImageBitmap, w: Int, h: Int): Pair<Int, Int> {
    val bmp = img.asAndroidBitmap()
    var left = 0; var right = 0
    for (y in (h * 0.4).toInt() until h step 3) for (x in 0 until w step 3) {
      val c = bmp.getPixel(x, y)
      val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
      if (b > 180 && r < 120 && g in 90..190) { if (x < w / 2) left++ else right++ }
    }
    return left to right
  }

  private fun save(img: ImageBitmap, name: String, out: File) {
    FileOutputStream(File(out, "$name.png")).use { img.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test
  fun junctionPictures() {
    val out = File("build/shots").apply { mkdirs() }
    val problems = mutableListOf<String>()
    val report = StringBuilder()
    val density = Density(2f)
    for (c in JvCases.all()) {
      jvRoadsNear = { _, _, _ -> c.roads }
      for (night in listOf(false, true)) for ((sizeName, wh) in listOf("vert" to (1600 to 600), "orizz" to (1400 to 1000))) {
        val name = "svincolo_${c.name}_${if (night) "notte" else "giorno"}_$sizeName"
        try {
          val img = ImageBitmap(wh.first, wh.second)
          CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(img), Size(wh.first.toFloat(), wh.second.toFloat())) {
            drawJunction(c.scene, night)
            drawVehicleLane(c.scene)
          }
          save(img, name, out)
          val sign = signOf(c.scene)
          if (sign.panels.isEmpty()) problems += "$name: manca il cartello"
          else if (sign.fromMap != c.signFromMap) problems += "$name: cartello ${if (sign.fromMap) "della mappa" else "ricostruito"} invece di ${if (c.signFromMap) "della mappa" else "ricostruito"}"
          else if (!sign.fromMap && (sign.panels.none { "SP12" in it.refs } || sign.panels.none { "Via Roma" in it.towns })) problems += "$name: cartello ricostruito senza numero e nome della strada: ${sign.panels.map { it.lines }}"
          // the lanes to take lit, on the side of the manoeuvre (all the junctions here go right)
          val (left, right) = litSides(img, wh.first, wh.second)
          if (left + right < 200) problems += "$name: corsie da prendere non evidenziate ($left + $right pixel)"
          else if (right <= left) problems += "$name: corsie accese a sinistra ($left) più che a destra ($right) in una manovra a destra"
          report.append("$name: cartello ${sign.panels.size} pannelli (${if (sign.fromMap) "mappa" else "ricostruito"}), corsie accese $left a sinistra, $right a destra\n")
        } catch (e: Throwable) {
          problems += "$name: errore ${e::class.simpleName}: ${e.message?.take(300)} ${e.stackTrace.take(4).joinToString(" < ")}"
        }
      }
    }
    // the whole view as on the screen (picture, distance, lane strip) for the exit
    val c = JvCases.exitCase()
    jvRoadsNear = { _, _, _ -> c.roads }
    for ((q, name) in listOf("w800dp-h1280dp-port-mdpi" to "vista_svincolo_tablet_vert", "w1280dp-h800dp-land-mdpi" to "vista_svincolo_tablet_orizz")) {
      RuntimeEnvironment.setQualifiers(q)
      val scenario = ActivityScenario.launch(ComponentActivity::class.java)
      try {
        val along = mutableStateOf(c.vehAlong)
        scenario.onActivity {
          it.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF3B4650)).padding(10.dp)) {
              if (q.contains("port")) JunctionView(c.scene, false, Modifier.fillMaxWidth(), height = 300.dp, vehAlong = along)
              else JunctionView(c.scene, false, Modifier.fillMaxSize(), fill = true, vehAlong = along)
            }
          }
        }
        compose.waitUntil(20_000) { runCatching { compose.onAllNodesWithTagCompat("jv-picture") }.getOrDefault(false) }
        compose.waitForIdle()
        scenario.onActivity { a ->
          val v = a.window.decorView
          val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
          v.draw(android.graphics.Canvas(b))
          FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
      } catch (e: Throwable) {
        problems += "$name: errore ${e::class.simpleName}: ${e.message?.take(300)}"
      } finally {
        scenario.close()
      }
    }
    File(out, "vista-svincolo.txt").writeText((if (problems.isEmpty()) "TUTTO OK\n" else "PROBLEMI:\n" + problems.joinToString("\n") + "\n") + "\n" + report)
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }

  /**
   * A roundabout of radius 18 m with five roads: in from the south, out to the north (the third
   * exit going round counter-clockwise: east, north-east, north), west the fifth road.
   */
  @Test
  fun roundaboutGlyph() {
    val out = File("build/shots").apply { mkdirs() }
    val rr = 18.0
    fun ring(b: Double) = Math.toRadians(b).let { rr * kotlin.math.sin(it) to rr * kotlin.math.cos(it) }
    val half1 = (0..18).map { ring(180.0 - it * 10.0) }          // south → east → north
    val half2 = (0..18).map { ring(360.0 - it * 10.0) }          // north → west → south
    fun armTo(b: Double, far: Double) = line(ring(b), Math.toRadians(b).let { far * kotlin.math.sin(it) to far * kotlin.math.cos(it) })
    val roads = listOf(
        road(31, "tertiary", 1, 1, line(*half1.toTypedArray())).copy(roundabout = true),
        road(32, "tertiary", 1, 1, line(*half2.toTypedArray())).copy(roundabout = true),
        road(33, "tertiary", 0, 2, armTo(180.0, 150.0)),
        road(34, "secondary", 0, 2, armTo(90.0, 150.0)),
        road(35, "residential", 0, 2, armTo(40.0, 120.0)),
        road(36, "tertiary", 0, 2, armTo(0.0, 160.0)),
        road(37, "unclassified", 0, 2, armTo(270.0, 150.0)),
    )
    val pts = line(0.0 to -150.0, *half1.toTypedArray(), 0.0 to 160.0)
    val r0 = route(pts, emptyList())
    val ringLen = Math.PI * rr
    val inLen = 150.0 - rr
    val edges = listOf(
        edge(0.0, inLen, "tertiary", "", 2, 33),
        edge(inLen, inLen + ringLen, "tertiary", "", 1, 31).copy(roundabout = true),
        edge(inLen + ringLen, r0.length, "tertiary", "", 2, 36),
    )
    val rt = route(pts, edges)
    val shape = RoundaboutShape.of(rt, inLen) { _, _, _ -> roads }
    val problems = mutableListOf<String>()
    fun near(a: Double, b: Double, tol: Double) = kotlin.math.abs(((a - b + 540.0) % 360.0) - 180.0) <= tol
    if (shape == null) problems += "nessuna rotonda"
    else {
      if (!near(shape.entryAt, 180.0, 8.0)) problems += "ingresso a ${shape.entryAt}° invece di 180°"
      if (!near(shape.exitAt, 0.0, 8.0)) problems += "uscita a ${shape.exitAt}° invece di 0°"
      if (!shape.ccw) problems += "senso di marcia orario invece di antiorario"
      if (kotlin.math.abs(shape.sweep - 180.0) > 12) problems += "giro di ${shape.sweep}° invece di 180°"
      val arms = shape.arms.map { it.first }.sorted()
      if (arms.size != 3 || !near(arms[0], 40.0, 8.0) || !near(arms[1], 90.0, 8.0) || !near(arms[2], 270.0, 8.0)) problems += "rami ${shape.arms}"
      for ((night, name) in listOf(false to "rotonda_barra_verde", true to "rotonda_barra_verde_notte")) {
        val img = ImageBitmap(240, 240)
        CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(img), Size(240f, 240f)) {
          drawRect(if (night) Color(0xFF0E5A36) else Color(0xFF1B8A4E))
          drawRoundabout(shape, Color.White)
        }
        save(img, name, out)
      }
    }
    // the same roundabout without the graph's roads: entry and exit from the route's turn only
    val bare = RoundaboutShape.of(rt, inLen) { _, _, _ -> emptyList() }
    if (bare == null || !bare.ccw || kotlin.math.abs(bare.sweep - 180.0) > 25) problems += "senza strade: $bare"
    File(out, "rotonda.txt").writeText((if (problems.isEmpty()) "TUTTO OK\n" else "PROBLEMI:\n" + problems.joinToString("\n") + "\n") + "\n$shape\n$bare\n")
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }

  private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCompat(tag: String): Boolean =
      onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
