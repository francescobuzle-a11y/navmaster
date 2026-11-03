package app.navmaster.truck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.maplibre.compose.expressions.dsl.const
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
