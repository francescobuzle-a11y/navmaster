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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Dp
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
 * of each case in build/shots, and the speedometer measured at every size it takes on the tablet:
 * drawn inside the app's own theme (its body style gives every text a 24 sp line and a letter
 * spacing, which on the tablet pushed «km/h» and the figure of the little sign off their place and
 * over the next texts), at the tablet's own density.
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
      // a car on the motorway: three figures in the little sign
      "auto autostrada" to SpeedRules.Limit(130, Why.SIGNED, 130, null, 130),
  )

  private class Shot(val name: String, val bitmap: Bitmap)

  /** Draws [content] in a window of [qualifiers], inside the app's theme, and hands the picture and the checks to [check]. */
  private fun shoot(file: String, qualifiers: String, content: @androidx.compose.runtime.Composable () -> Unit, check: (Bitmap) -> String) {
    val out = File("build/shots").apply { mkdirs() }
    RuntimeEnvironment.setQualifiers(qualifiers)
    val scenario = ActivityScenario.launch(ComponentActivity::class.java)
    var problems = ""
    try {
      scenario.onActivity { it.setContent { NmTheme { content() } } }
      compose.waitForIdle()
      scenario.onActivity { a ->
        val v = a.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(b))
        FileOutputStream(File(out, file)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        problems = check(b)
      }
    } catch (e: Throwable) {
      problems = "${e::class.simpleName}: ${e.message?.take(300)}"
    } finally {
      scenario.close()
    }
    assertTrue(problems, problems.isEmpty())
  }

  /**
   * The speedometer tagged [tag], measured: the unit under the figure and above the row of the
   * limit, the little sign's figure on the white in the middle and clear of the red ring, the whole
   * row inside the card.
   */
  private fun checkArc(tag: String, lim: SpeedRules.Limit, speed: Int, b: Bitmap): String {
    var problems = ""
    val arc = hasAnyAncestor(hasTestTag(tag))
    val card = compose.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    val number = compose.onNode(hasText("$speed") and arc, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    val unit = compose.onNode(hasText("km/h") and arc, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    val sign = compose.onNode(hasTestTag("arcSign") and arc, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    val label = compose.onNode(hasText(if (lim.why == Why.LEGAL) "limite del mezzo" else if (lim.why == Why.OWN) "tuo limite" else "limite") and arc,
        useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
    if (unit.top < number.bottom - 0.5f) problems += "$tag: «km/h» (da ${unit.top}) dentro la cifra della velocità (fino a ${number.bottom}). "
    if (unit.bottom > sign.top + 0.5f) problems += "$tag: «km/h» (fino a ${unit.bottom}) tocca la riga del limite (da ${sign.top}). "
    if (unit.bottom > label.top + 0.5f) problems += "$tag: «km/h» (fino a ${unit.bottom}) tocca la scritta del limite (da ${label.top}). "
    if (sign.left < card.left + 1f || label.right > card.right - 1f) problems += "$tag: la riga del limite esce dalla scheda. "
    if (sign.bottom > card.bottom - 1f || label.bottom > card.bottom - 1f) problems += "$tag: la riga del limite esce dal fondo della scheda. "
    // the figure inside the little sign: dark ink only on the white, centred, not over the red ring
    val cx = (sign.left + sign.right) / 2f; val cy = (sign.top + sign.bottom) / 2f
    val radius = sign.width / 2f
    val ring = radius * 0.26f  // the ring is 13% of the sign's diameter
    var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
    for (y in sign.top.toInt()..sign.bottom.toInt()) for (x in sign.left.toInt()..sign.right.toInt()) {
      if (x !in 0 until b.width || y !in 0 until b.height) continue
      if (Math.hypot((x + 0.5f - cx).toDouble(), (y + 0.5f - cy).toDouble()) > radius - 1.5f) continue
      val c = b.getPixel(x, y)
      val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
      val lum = (r * 299 + g * 587 + bl * 114) / 1000
      if (lum < 110 && r - g < 60) { x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
    }
    if (x1 < 0) return problems + "$tag: nessuna cifra nel cartellino del limite. "
    val bx = (x0 + x1 + 1) / 2f; val by = (y0 + y1 + 1) / 2f
    if (Math.abs(bx - cx) > radius * 0.2f || Math.abs(by - cy) > radius * 0.2f)
      problems += "$tag: la cifra del cartellino non è al centro (${bx - cx}, ${by - cy} px, raggio $radius). "
    // every corner of the figure inside the white (the ring's inner edge), a pixel to spare
    for ((px, py) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
      val d = Math.hypot((px + 0.5f - cx).toDouble(), (py + 0.5f - cy).toDouble())
      if (d > radius - ring - 0.5f) problems += "$tag: la cifra tocca l'anello rosso (angolo $px,$py a $d px dal centro, bianco fino a ${radius - ring}). "
    }
    return problems
  }

  /** Every case: the sign, the camera's banner and the speedometer side by side (the picture to look at). */
  @Test
  fun everyCase() {
    shoot("limite_velocita.png", "w800dp-h760dp-land-tvdpi", {
      Column(Modifier.fillMaxSize().background(Color(0xFF1E252D)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((name, lim) in cases) {
          Row(Modifier.testTag(name), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            SpeedLimitSign(lim, 62, 5)
            CameraBanner(camera(130.0), 550.0, false, limit = lim)
            SpeedArc(62, lim, 5, null, null, 144.dp, muted = true, modifier = Modifier.testTag("arc-$name"))
          }
        }
      }
    }) { b -> cases.joinToString("") { (name, lim) -> checkArc("arc-$name", lim, 62, b) } }
  }

  /** The speedometer at every size it takes on the tablet (small, the junction view's, big while over the limit), each case. */
  @Test
  fun speedometerAtEverySize() {
    val widths: List<Dp> = listOf(112.dp, 122.dp, 144.dp, 150.dp, 216.dp)
    shoot("limite_tachimetro.png", "w1100dp-h1000dp-land-tvdpi", {
      Column(Modifier.fillMaxSize().background(Color(0xFF1E252D)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((name, lim) in cases) {
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            for (w in widths) SpeedArc(88, lim, 5, null, null, w, muted = true, modifier = Modifier.testTag("arc-$name-${w.value.toInt()}"))
          }
        }
      }
    }) { b ->
      buildString {
        for ((name, lim) in cases) for (w in widths) append(checkArc("arc-$name-${w.value.toInt()}", lim, 88, b))
      }
    }
  }
}
