package app.navmaster.truck.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.navmaster.truck.limits.RouteLimit
import app.navmaster.truck.vehicle.SpeedRules
import app.navmaster.truck.vehicle.SpeedRules.Why
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The speed limit as the driver sees it, in the cases of the A21 screenshot: the sign at the side
 * of the map, the speed camera's banner and the speedometer, all with the same numbers. A picture
 * of each case in build/shots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SpeedSignShots {
  @get:Rule val compose = createEmptyComposeRule()

  private fun camera(value: Double) = RouteLimit("speed_camera", value, null, null, null, 1200.0, 45.1, 10.0, false, null, "osm")

  private val cases = listOf(
      // the A21: the signs say 130, the law says 80 for a lorry over 12 t
      "autostrada camion" to SpeedRules.Limit(80, Why.LEGAL, 130, 80, 80),
      // the SS10: signed 90, the law 70 outside towns
      "SS10 camion" to SpeedRules.Limit(70, Why.LEGAL, 90, 70, 80),
      // the profile's own maximum is the lowest
      "limite del profilo" to SpeedRules.Limit(80, Why.OWN, 130, 90, 80),
      // nothing to add: the sign
      "SS343 cartello" to SpeedRules.Limit(70, Why.SIGNED, 70, 70, 80),
  )

  @Test
  fun everyCase() {
    val out = File("build/shots").apply { mkdirs() }
    RuntimeEnvironment.setQualifiers("w800dp-h600dp-land-mdpi")
    val scenario = ActivityScenario.launch(ComponentActivity::class.java)
    var problems = ""
    try {
      scenario.onActivity {
        it.setContent {
          Column(Modifier.fillMaxSize().background(Color(0xFF1E252D)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for ((name, lim) in cases) {
              Row(Modifier.testTag(name), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                SpeedLimitSign(lim, 62, 5)
                CameraBanner(camera(130.0), 550.0, false, limit = lim)
                SpeedArc(62, lim, 5, null, null, 170.dp, muted = true)
              }
            }
          }
        }
      }
      compose.waitForIdle()
      scenario.onActivity { a ->
        val v = a.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(b))
        FileOutputStream(File(out, "limite_velocita.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
      }
    } catch (e: Throwable) {
      problems = "${e::class.simpleName}: ${e.message?.take(300)}"
    } finally {
      scenario.close()
    }
    assertTrue(problems, problems.isEmpty())
  }
}
