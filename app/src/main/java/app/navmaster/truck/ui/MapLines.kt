package app.navmaster.truck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.lt
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import uniffi.ferrostar.GeographicCoordinate

/*
 * The data of the map layers is handed to the map ONLY when it changes.
 *
 * The cause of the stuttering map: during guidance the map is recomposed at every frame (the
 * arrow glides between two positions), and every source got a new data object each time, so the
 * map was given the whole route again (a text of hundreds of kilobytes for a long trip) and all the
 * markers, 60 times a second; it re-cut its tiles over and over and could not keep up.
 * Here the text is made once per route and the same data object is kept, so nothing is redone.
 */

/** A GeoJSON source whose data is set again only when [json] really changes. */
@Composable
fun rememberJsonSource(json: String): GeoJsonSource {
  val data = remember(json) { GeoJsonData.JsonString(json) }
  return rememberGeoJsonSource(data)
}

/**
 * The route as GeoJSON text, made once per route (keyed by its size and a few of its points), off
 * the main thread (a long trip is a megabyte of text: the screen stopped for a moment at every
 * recalculation); the line of before stays on the map until the new text is ready.
 */
@Composable
fun rememberLineJson(points: List<GeographicCoordinate>?): String? {
  val n = points?.size ?: 0
  val state = androidx.compose.runtime.produceState<String?>(null, n, points?.firstOrNull(), points?.lastOrNull(), points?.getOrNull(n / 2)) {
    value = if (points == null || n < 2) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
      buildString(n * 24 + 120) {
        append("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
        for ((i, p) in points.withIndex()) {
          if (i > 0) append(',')
          append('[').append(p.lng).append(',').append(p.lat).append(']')
        }
        append("""]},"properties":{}}]}""")
      }
    }
  }
  return state.value
}

/** A line with a border (the route), drawn from GeoJSON text that is handed to the map once. */
@Composable
@MaplibreComposable
fun NmLine(json: String, idPrefix: String, color: Color, lineWidth: Float, borderWidth: Float, borderColor: Color = Color.White) {
  val src = rememberJsonSource(json)
  LineLayer(
      id = "$idPrefix-border",
      source = src,
      color = const(borderColor),
      width = const((lineWidth + borderWidth * 2f).dp),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
  )
  LineLayer(
      id = "$idPrefix-fill",
      source = src,
      color = const(color),
      width = const(lineWidth.dp),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
  )
}

/** The same for a list of points, the text made once per route. */
@Composable
@MaplibreComposable
fun NmRouteLine(points: List<GeographicCoordinate>?, idPrefix: String, color: Color, lineWidth: Float, borderWidth: Float, borderColor: Color = Nm.RouteBorder) {
  val json = rememberLineJson(points) ?: return
  NmLine(json, idPrefix, color, lineWidth, borderWidth, borderColor)
}

/**
 * The route being driven, cut in stretches of about 800 m (made once per route): the stretches
 * behind and ahead drawn whole by a filter, the stretch the vehicle is in split under it (redone
 * every few metres, a short line). A gradient along one line would be cheaper, but its precision
 * is a fraction of the whole route: kilometres on a long trip.
 */
class RouteChunks(val points: List<GeographicCoordinate>) {
  val cum: DoubleArray = app.navmaster.truck.core.Geo.cumulative(points)
  /** The first point of each stretch; a stretch ends on the first point of the next one. */
  val starts: IntArray
  /** All the stretches, each a feature with its number "i". */
  val json: String

  init {
    val s = ArrayList<Int>()
    s += 0
    var last = 0.0
    for (i in 1 until points.size - 1) if (cum[i] - last >= CHUNK_M) { s += i; last = cum[i] }
    starts = s.toIntArray()
    json = buildString(points.size * 24 + starts.size * 60) {
      append("""{"type":"FeatureCollection","features":[""")
      for (k in starts.indices) {
        val a = starts[k]
        val b = if (k + 1 < starts.size) starts[k + 1] else points.size - 1
        if (k > 0) append(',')
        append("""{"type":"Feature","properties":{"i":""").append(k).append("""},"geometry":{"type":"LineString","coordinates":[""")
        for (i in a..b) {
          if (i > a) append(',')
          append('[').append(points[i].lng).append(',').append(points[i].lat).append(']')
        }
        append("]}}")
      }
      append("]}")
    }
  }

  /** The stretch at [alongM] metres. */
  fun chunkAt(alongM: Double): Int {
    var lo = 0; var hi = starts.size - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (cum[starts[mid]] <= alongM) lo = mid else hi = mid - 1
    }
    return lo
  }

  /** The stretch at [alongM] cut there: the part behind and the part ahead, as GeoJSON lines. */
  fun split(alongM: Double): Pair<String, String> {
    val k = chunkAt(alongM)
    val a = starts[k]
    val b = if (k + 1 < starts.size) starts[k + 1] else points.size - 1
    val p = app.navmaster.truck.core.Geo.pointAt(points, cum, alongM)
    val behind = ArrayList<GeographicCoordinate>()
    val ahead = ArrayList<GeographicCoordinate>()
    for (i in a..b) if (cum[i] < alongM) behind += points[i]
    behind += p
    ahead += p
    for (i in a..b) if (cum[i] > alongM) ahead += points[i]
    return lineJson(behind) to lineJson(ahead)
  }

  private fun lineJson(pts: List<GeographicCoordinate>): String = buildString(pts.size * 24 + 120) {
    append("""{"type":"FeatureCollection","features":[""")
    if (pts.size >= 2) {
      append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
      for ((i, q) in pts.withIndex()) {
        if (i > 0) append(',')
        append('[').append(q.lng).append(',').append(q.lat).append(']')
      }
      append("]}}")
    }
    append("]}")
  }

  companion object {
    const val CHUNK_M = 800.0
  }
}

/**
 * The map's line offset of [metres] to the right of the line's way (negative: to the left), at
 * every zoom: the ground's scale doubles with each level of zoom.
 */
fun groundOffset(metres: Double, lat: Double): org.maplibre.compose.expressions.ast.Expression<org.maplibre.compose.expressions.value.NumberValue<androidx.compose.ui.unit.Dp>> {
  fun dpAt(z: Double) = (metres * 512.0 * Math.pow(2.0, z) / (40075016.686 * kotlin.math.cos(Math.toRadians(lat)))).toFloat().dp
  return org.maplibre.compose.expressions.dsl.interpolate(
      org.maplibre.compose.expressions.dsl.exponential(2f), org.maplibre.compose.expressions.dsl.zoom(),
      8 to const(dpAt(8.0)), 22 to const(dpAt(22.0)))
}

/**
 * The route while driving: ahead in its colour with the border, behind (the road already done)
 * lighter and see-through, or not at all ([done]: 0 like the rest, 1 lighter, 2 hidden), the two
 * meeting under the vehicle at [alongM] metres; [offsetM] metres to the right of the road's middle
 * (the lane the vehicle drives in, as its arrow).
 */
@Composable
@MaplibreComposable
fun NmDrivenRoute(points: List<GeographicCoordinate>?, alongM: Double, offsetM: Double, lat: Double, done: Int, idPrefix: String,
                  color: Color, lineWidth: Float, borderWidth: Float, borderColor: Color = Nm.RouteBorder) {
  val n = points?.size ?: 0
  val chunks = androidx.compose.runtime.produceState<RouteChunks?>(null, n, points?.firstOrNull(), points?.lastOrNull(), points?.getOrNull(n / 2)) {
    value = if (points == null || n < 2) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { RouteChunks(points) }
  }.value ?: return
  // the cut redone every 5 m (a short line): the map is not given new data at every frame
  val step = (alongM.coerceAtLeast(0.0) / 5.0).toLong()
  val cur = chunks.chunkAt(step * 5.0)
  val cut = remember(chunks, step) { chunks.split(step * 5.0) }
  val all = rememberJsonSource(chunks.json)
  val behindSrc = rememberJsonSource(cut.first)
  val aheadSrc = rememberJsonSource(cut.second)
  val off = remember(offsetM, Math.round(lat * 2)) { groundOffset(offsetM, lat) }
  val behindFilter = remember(cur) {
    org.maplibre.compose.expressions.dsl.feature.get("i").asNumber().lt(const(cur.toFloat()))
  }
  val aheadFilter = remember(cur) {
    org.maplibre.compose.expressions.dsl.feature.get("i").asNumber().gt(const(cur.toFloat()))
  }
  val cap = const(LineCap.Round)
  val join = const(LineJoin.Round)
  if (done != 2) {
    val doneColor = if (done == 1) Color(0x7799C9FF) else color
    LineLayer(id = "$idPrefix-done-all", source = all, filter = behindFilter, color = const(doneColor), width = const(lineWidth.dp), offset = off, cap = cap, join = join)
    LineLayer(id = "$idPrefix-done-cut", source = behindSrc, color = const(doneColor), width = const(lineWidth.dp), offset = off, cap = cap, join = join)
  }
  LineLayer(id = "$idPrefix-border-all", source = all, filter = aheadFilter, color = const(borderColor), width = const((lineWidth + borderWidth * 2f).dp),
      offset = off, cap = cap, join = join)
  LineLayer(id = "$idPrefix-border-cut", source = aheadSrc, color = const(borderColor), width = const((lineWidth + borderWidth * 2f).dp),
      offset = off, cap = cap, join = join)
  LineLayer(id = "$idPrefix-fill-all", source = all, filter = aheadFilter, color = const(color), width = const(lineWidth.dp), offset = off, cap = cap, join = join)
  LineLayer(id = "$idPrefix-fill-cut", source = aheadSrc, color = const(color), width = const(lineWidth.dp), offset = off, cap = cap, join = join)
}
