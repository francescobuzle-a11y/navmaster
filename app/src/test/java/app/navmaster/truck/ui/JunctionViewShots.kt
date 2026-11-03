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
import app.navmaster.truck.data.RoadsIndex
import app.navmaster.truck.nav.LaneModel
import app.navmaster.truck.nav.LanePlan
import app.navmaster.truck.routing.EdgeInfo
import app.navmaster.truck.routing.EdgeSign
import app.navmaster.truck.routing.LaneDest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.ferrostar.GeographicCoordinate
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The junction view on three made-up junctions (a motorway exit with its signs, a motorway fork
 * known from the lane counts with signs over the lanes, a junction in town with painted arrows),
 * by day and by night, upright and lying down: a picture of each in build/shots, and checks that
 * the picture has its roads, its lit lanes and its signs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class JunctionViewShots {
  @get:Rule val compose = createEmptyComposeRule()

  private val lat0 = 45.5
  private val lon0 = 10.2
  private val kx = 111_320.0 * cos(Math.toRadians(lat0))

  /** A point [e] metres east and [n] metres north of the origin. */
  private fun at(e: Double, n: Double) = GeographicCoordinate(lat0 + n / 110_540.0, lon0 + e / kx)

  private fun line(vararg en: Pair<Double, Double>) = en.map { at(it.first, it.second) }

  private fun road(osm: Long, hw: String, oneway: Int, lanes: Int?, pts: List<GeographicCoordinate>, lanesF: Int? = null, lanesB: Int? = null, bridge: Boolean = false) =
      RoadsIndex.Road(osm, hw, null, null, null, null, oneway, lanes, lanesF, lanesB, null, null, null, false, false, false, bridge, null, null, pts)

  private fun edge(start: Double, end: Double, cls: String, use: String, lanes: Int, way: Long) =
      EdgeInfo(start, end, cls, use, false, "paved", lanes, way, null, "IT", false, false, null, null)

  /** The route along [pts] (metres along it from its first point). */
  private fun route(pts: List<GeographicCoordinate>, edges: List<EdgeInfo>): JvRoute {
    val xy = pts.map { doubleArrayOf((it.lng - lon0) * kx, (it.lat - lat0) * 110_540.0) }
    val cum = DoubleArray(pts.size)
    for (i in 1 until pts.size) cum[i] = cum[i - 1] + sqrt((xy[i][0] - xy[i - 1][0]).let { it * it } + (xy[i][1] - xy[i - 1][1]).let { it * it })
    val length = cum.last()
    val pointAt = { m: Double ->
      val d = m.coerceIn(0.0, length)
      var i = 1
      while (i < cum.size - 1 && cum[i] < d) i++
      val t = if (cum[i] > cum[i - 1]) (d - cum[i - 1]) / (cum[i] - cum[i - 1]) else 0.0
      GeographicCoordinate(pts[i - 1].lat + (pts[i].lat - pts[i - 1].lat) * t, pts[i - 1].lng + (pts[i].lng - pts[i - 1].lng) * t)
    }
    return JvRoute(Any(), length, pointAt, { m -> edges.firstOrNull { m >= it.startM && m <= it.endM } }, edges)
  }

  private class Case(val name: String, val scene: JunctionScene, val roads: List<RoadsIndex.Road>, val vehAlong: Double, val expectGantry: Boolean)

  /** Motorway A4 eastbound, 3 lanes, exit 5 on the right (the right lane, "through;slight_right"). */
  private fun exitCase(): Case {
    val ramp = listOf(0.0 to 0.0, 25.0 to -2.0, 50.0 to -6.0, 75.0 to -12.0, 100.0 to -20.0, 130.0 to -32.0, 160.0 to -46.0,
        200.0 to -67.0, 250.0 to -95.0, 300.0 to -122.0, 360.0 to -150.0)
    val pts = line(-900.0 to 0.0, *ramp.toTypedArray())
    val r = route(pts, emptyList()).let { r0 ->
      route(pts, listOf(edge(0.0, 900.0, "motorway", "", 3, 1), edge(900.0, r0.length, "motorway", "ramp", 1, 2)))
    }
    val taken = EdgeSign(listOf("5"), listOf("SS45bis"), listOf("Brescia Centro", "Montichiari"), emptyList())
    val other = EdgeSign(emptyList(), listOf("A4"), listOf("Venezia", "Verona"), emptyList())
    val j = LaneModel.Junction(
        900.0, LanePlan.Kind.EXIT, 1,
        listOf(LanePlan.Lane("straight", false), LanePlan.Lane("straight", false),
            LanePlan.Lane("slight right", true, arrows = listOf("straight", "slight right"))),
        true,
        listOf(LaneModel.Road(22.0, true, 1, "motorway", true, taken, 2), LaneModel.Road(0.0, false, 2, "motorway", false, other, 4)),
        taken, other, null, 1, 2, 1, "motorway", false)
    val roads = listOf(
        road(1, "motorway", 1, 3, line(-1500.0 to 0.0, 0.0 to 0.0)),
        road(4, "motorway", 1, 2, line(0.0 to 0.0, 400.0 to 3.0, 900.0 to 10.0)),
        road(2, "motorway_link", 1, 1, line(*ramp.toTypedArray())),
        road(3, "motorway", 1, 3, line(900.0 to 30.0, -1500.0 to 30.0)),
        road(5, "tertiary", 0, 2, line(380.0 to 400.0, 370.0 to -400.0), bridge = true),
        road(6, "unclassified", 0, null, line(360.0 to -150.0, 420.0 to -170.0, 520.0 to -175.0)),
    )
    return Case("uscita", JunctionScene(j, 120.0, 1500.0, r, 780.0, 900.0, "IT", 2), roads, 780.0, true)
  }

  /** A4 → A21 fork: 4 lanes, the right two to Piacenza (counts 4 = 2 + 2), signs over the lanes. */
  private fun forkCase(): Case {
    val right = listOf(0.0 to 0.0, 60.0 to -3.0, 120.0 to -10.0, 200.0 to -24.0, 300.0 to -45.0, 420.0 to -72.0)
    val pts = line(-900.0 to 0.0, *right.toTypedArray())
    val r = route(pts, emptyList()).let { r0 ->
      route(pts, listOf(edge(0.0, 900.0, "motorway", "", 4, 11), edge(900.0, r0.length, "motorway", "", 2, 12)))
    }
    val toPc = LaneDest(listOf("Piacenza", "Cremona"), listOf("A21"), null)
    val toMi = LaneDest(listOf("Milano"), listOf("A4"), null)
    val j = LaneModel.Junction(
        900.0, LanePlan.Kind.FORK, 1,
        (0 until 4).map { LanePlan.Lane("straight", it >= 2, painted = false) },
        false,
        listOf(LaneModel.Road(10.0, true, 2, "motorway", false, null, 12), LaneModel.Road(-3.0, false, 2, "motorway", false, null, 13)),
        null, null, listOf(toMi, toMi, toPc, toPc), 2, 2, 11, "motorway", false)
    val roads = listOf(
        road(11, "motorway", 1, 4, line(-1500.0 to 0.0, 0.0 to 0.0)),
        road(12, "motorway", 1, 2, line(*right.toTypedArray())),
        road(13, "motorway", 1, 2, line(0.0 to 0.0, 200.0 to 8.0, 500.0 to 30.0)),
        road(14, "motorway", 1, 4, line(600.0 to 40.0, -1500.0 to 32.0)),
    )
    return Case("bivio", JunctionScene(j, 240.0, 1500.0, r, 660.0, 900.0, "IT", 3), roads, 790.0, true)
  }

  /** In town: a primary road with three lanes our way ("left|through|through;right"), right turn. */
  private fun townCase(): Case {
    val pts = line(-400.0 to 0.0, 0.0 to 0.0, 0.0 to -300.0)
    val r = route(pts, listOf(edge(0.0, 400.0, "primary", "", 5, 21), edge(400.0, 700.0, "secondary", "", 2, 22)))
    val j = LaneModel.Junction(
        400.0, LanePlan.Kind.JUNCTION, 1,
        listOf(LanePlan.Lane("left", false), LanePlan.Lane("straight", false),
            LanePlan.Lane("right", true, arrows = listOf("straight", "right"))),
        true,
        listOf(LaneModel.Road(90.0, true, 2, "secondary", false, null, 22), LaneModel.Road(0.0, false, 4, "primary", false, null, 23),
            LaneModel.Road(-90.0, false, 2, "secondary", false, null, 24)),
        null, null, null, 2, 4, 21, "primary", false)
    val roads = listOf(
        road(21, "primary", 0, 5, line(-600.0 to 0.0, 0.0 to 0.0), lanesF = 3, lanesB = 2),
        road(22, "secondary", 0, 2, line(0.0 to 0.0, 0.0 to -400.0)),
        road(23, "primary", 0, 4, line(0.0 to 0.0, 500.0 to 0.0)),
        road(24, "secondary", 0, 2, line(0.0 to 0.0, 0.0 to 400.0)),
        road(25, "residential", 0, null, line(-200.0 to 0.0, -200.0 to 300.0)),
        road(26, "residential", 0, null, line(-120.0 to 0.0, -120.0 to -250.0)),
    )
    return Case("citta", JunctionScene(j, 80.0, 300.0, r, 320.0, 400.0, "IT", null), roads, 350.0, false)
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
    for (c in listOf(exitCase(), forkCase(), townCase())) {
      jvRoadsNear = { _, _, _ -> c.roads }
      for (night in listOf(false, true)) for ((sizeName, wh) in listOf("vert" to (1600 to 600), "orizz" to (1400 to 1000))) {
        val name = "svincolo_${c.name}_${if (night) "notte" else "giorno"}_$sizeName"
        try {
          val scene = buildScene(c.scene, night)
          if (scene == null) { problems += "$name: nessuna scena"; continue }
          val stat = renderStatic(scene, wh.first, wh.second, density)
          val img = ImageBitmap(wh.first, wh.second)
          CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(img), Size(wh.first.toFloat(), wh.second.toFloat())) {
            drawImage(stat)
            drawVehicle(scene, c.vehAlong, c.scene.vehicleLane)
          }
          save(img, name, out)
          if (scene.asphalt.isEmpty()) problems += "$name: nessuna strada"
          if (scene.lit.isEmpty()) problems += "$name: nessuna corsia evidenziata"
          if (c.expectGantry && scene.gantry == null) problems += "$name: manca il portale dei cartelli"
          if (!c.expectGantry && scene.gantry != null) problems += "$name: portale dei cartelli senza cartelli"
          report.append("$name: ${scene.asphalt.size} strade, ${scene.markings.size} segni, ${scene.lit.size} corsie accese, " +
              "portale ${scene.gantry?.panels?.size ?: 0} pannelli\n")
        } catch (e: Throwable) {
          problems += "$name: errore ${e::class.simpleName}: ${e.message?.take(300)} ${e.stackTrace.take(4).joinToString(" < ")}"
        }
      }
    }
    // the whole view as on the screen (picture, distance, lane strip) for the exit
    val c = exitCase()
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

  private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCompat(tag: String): Boolean =
      onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
