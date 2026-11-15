package app.navmaster.truck.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max

/**
 * The arrows of the junction view, measured on the pixels: the picture is drawn with the arrows
 * and without them, and what differs is the arrows. Each one must be on its lane (blue where it is
 * to be taken, plain asphalt where it is only the painted one), with room to spare on both sides,
 * whole inside the picture and under the signs' band, never over a line or an edge; one for each
 * lane that has one, all the same size and on one row. Every case, three picture shapes, day and
 * night.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class LaneArrowTest {
  private class Px(val argb: IntArray, val w: Int, val h: Int) {
    fun r(x: Int, y: Int) = (argb[y * w + x] shr 16) and 255
    fun g(x: Int, y: Int) = (argb[y * w + x] shr 8) and 255
    fun b(x: Int, y: Int) = argb[y * w + x] and 255
  }

  private fun render(c: JvCases.Case, w: Int, h: Int, night: Boolean, arrows: Boolean): Px {
    val img = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(2f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) { drawJunction(c.scene, night, arrows) }
    val bmp = img.asAndroidBitmap()
    val a = IntArray(w * h)
    bmp.getPixels(a, 0, w, 0, 0, w, h)
    return Px(a, w, h)
  }

  /** The lit lanes' blue, as drawn (litFar … lit), also where it is lightened by the day or the night. */
  private fun blue(px: Px, x: Int, y: Int) = px.b(x, y) > 150 && px.r(x, y) < 90 && px.b(x, y) - px.r(x, y) > 100
  /** Plain asphalt: dark and grey (the road, the ground), not a line, not the lit lanes. */
  private fun asphalt(px: Px, x: Int, y: Int): Boolean {
    val r = px.r(x, y); val g = px.g(x, y); val b = px.b(x, y)
    return r < 110 && g < 110 && b < 120 && abs(b - r) < 40
  }

  private class Arrow(val pixels: List<Int>, val x0: Int, val y0: Int, val x1: Int, val y1: Int) { val set: Set<Int> = pixels.toHashSet() }

  /** The pixels that differ, grouped in arrows (8-connected). */
  private fun arrowsOf(a: Px, b: Px): List<Arrow> {
    val w = a.w; val h = a.h
    val diff = BooleanArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
      val d = max(abs(a.r(x, y) - b.r(x, y)), max(abs(a.g(x, y) - b.g(x, y)), abs(a.b(x, y) - b.b(x, y))))
      diff[y * w + x] = d > 12
    }
    val seen = BooleanArray(w * h)
    val out = ArrayList<Arrow>()
    for (start in 0 until w * h) {
      if (!diff[start] || seen[start]) continue
      val stack = ArrayDeque<Int>(); stack.add(start); seen[start] = true
      val pix = ArrayList<Int>()
      var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
      while (stack.isNotEmpty()) {
        val i = stack.removeLast(); pix += i
        val x = i % w; val y = i / w
        x0 = minOf(x0, x); x1 = max(x1, x); y0 = minOf(y0, y); y1 = max(y1, y)
        for (dy in -1..1) for (dx in -1..1) {
          val nx = x + dx; val ny = y + dy
          if (nx !in 0 until w || ny !in 0 until h) continue
          val j = ny * w + nx
          if (diff[j] && !seen[j]) { seen[j] = true; stack.add(j) }
        }
      }
      if (pix.size >= 12) out += Arrow(pix, x0, y0, x1, y1)
    }
    return out
  }

  @Test
  fun arrowsAreWholeInsideTheirLanes() {
    val problems = mutableListOf<String>()
    val report = StringBuilder()
    val out = File("build/shots").apply { mkdirs() }
    val sizes = listOf("pannello" to (560 to 575), "striscia" to (1040 to 533), "largo" to (1600 to 600), "alto" to (700 to 900))
    for (c in JvCases.all()) {
      jvRoadsNear = { _, _, _ -> c.roads }
      for ((sn, wh) in sizes) for (night in listOf(false, true)) {
        val (w, h) = wh
        val tag = "${c.name}/$sn/${if (night) "notte" else "giorno"}"
        val with = render(c, w, h, night, true)
        val without = render(c, w, h, night, false)
        val arrows = arrowsOf(with, without)
        val l = Layout(c.scene)
        val p = Persp(w.toFloat(), h.toFloat(), l.n)
        val glyphs = laneGlyphs(l, p)
        val wantLit = glyphs.count { it.lit }
        val wantDim = glyphs.count { !it.lit }
        var lit = 0; var dim = 0
        val boxes = ArrayList<Arrow>()
        for (a in arrows) {
          // lit lane: the old pixels under it are blue; dull one: plain asphalt
          val onBlue = a.pixels.count { blue(without, it % w, it / w) }
          val onAsphalt = a.pixels.count { asphalt(without, it % w, it / w) }
          val isLit = onBlue > a.pixels.size / 2
          if (isLit) lit++ else dim++
          boxes += a
          val bad = a.pixels.size - (if (isLit) onBlue else onAsphalt)
          // the antialiased edge may sit over a pixel of the other kind: a few at most
          if (bad > max(3, a.pixels.size / 60)) problems += "$tag: freccia a (${a.x0},${a.y0}) con $bad pixel su " +
              (if (isLit) "altro che il blu della corsia" else "altro che asfalto") + " (su ${a.pixels.size})"
          // room on both sides, row by row: the lane goes on at least 3 px beyond the arrow
          var tight = 0
          for (y in a.y0..a.y1) {
            var lo = w; var hi = -1
            for (x in a.x0..a.x1) if (a.set.contains(y * w + x)) { lo = minOf(lo, x); hi = max(hi, x) }
            if (hi < 0) continue
            for (k in 1..3) {
              val l1 = lo - k; val r1 = hi + k
              val ok1 = l1 >= 0 && (if (isLit) blue(without, l1, y) else asphalt(without, l1, y))
              val ok2 = r1 < w && (if (isLit) blue(without, r1, y) else asphalt(without, r1, y))
              if (!ok1 || !ok2) { tight++; break }
            }
          }
          if (tight > 2) problems += "$tag: freccia a (${a.x0},${a.y0}) a meno di 3 px dal bordo della corsia in $tight righe"
          // whole inside the picture, under the signs' band
          if (a.x0 < 4 || a.y0 < 4 || a.x1 > w - 5 || a.y1 > h - 5) problems += "$tag: freccia vicina al bordo del quadro (${a.x0},${a.y0})–(${a.x1},${a.y1})"
          if (a.y0 < p.hz) problems += "$tag: freccia sopra l'orizzonte"
        }
        if (lit != wantLit) problems += "$tag: $lit frecce bianche invece di $wantLit"
        if (dim != wantDim) problems += "$tag: $dim frecce spente invece di $wantDim"
        // all on one row, the same size
        if (boxes.size >= 2) {
          val hs = boxes.map { it.y1 - it.y0 + 1 }
          val cs = boxes.map { (it.y0 + it.y1) / 2 }
          if (cs.max() - cs.min() > 0.12 * h) problems += "$tag: frecce non sulla stessa fila (${cs.min()}…${cs.max()})"
          if (hs.max() > hs.min() * 1.9 + 6) problems += "$tag: frecce di altezze molto diverse ($hs)"
        }
        report.append("$tag: ${glyphs.size} frecce (${wantLit} bianche, ${wantDim} spente), taglia ${glyphs.firstOrNull()?.size?.toInt()}\n")
        if (sn == "pannello" && !night || sn == "striscia" && !night) {
          val bmp = with.argb.let { a -> Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888) }
          FileOutputStream(File(out, "frecce_${c.name}_$sn.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
      }
    }
    File(out, "frecce.txt").writeText((if (problems.isEmpty()) "TUTTO OK\n" else "PROBLEMI:\n" + problems.joinToString("\n") + "\n") + "\n" + report)
    assertTrue(problems.joinToString("\n"), problems.isEmpty())
  }
}
