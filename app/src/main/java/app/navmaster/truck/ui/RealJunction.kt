package app.navmaster.truck.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.RoadTiles
import app.navmaster.truck.ui.jv.JvEdge
import app.navmaster.truck.ui.jv.JvFrame
import app.navmaster.truck.ui.jv.JvGeo
import app.navmaster.truck.ui.jv.JvInput
import app.navmaster.truck.ui.jv.JvProject
import app.navmaster.truck.ui.jv.JvRoad
import kotlin.math.sin

/** The real junction of a scene, with the drawings of its last picture kept (one per camera position and panel size). */
internal class RealJv(val geo: JvGeo) {
  var cacheKey = ""
  var cache: List<Pair<Path, Int>>? = null
  var cacheFrame: JvFrame? = null
  var hills: Path? = null
  var hillsKey = ""
}

private val LHT = setOf("GB", "IE", "CY", "MT", "IM", "GG", "JE")

/**
 * The real junction for a scene of the guidance: the route around it (its roads with their lanes
 * and arrows) and the roads of the map around the junction, with their real lanes when the graph of
 * the country (or its light package) is there, from the map tiles otherwise.
 */
internal fun realJunctionOf(scene: JunctionScene, md: JvModel): RealJv? {
  val a = scene.analysis ?: return null
  if (scene.roundabout) return null
  val m = scene.maneuverAtM
  val start = (m - 600).coerceAtLeast(0.0)
  val end = (m + 500).coerceAtMost(a.length)
  if (m - start < 80 || end - m < 60) return null
  val o = a.pointAt(m)
  val routeLL = (0..((end - start) / 3.0).toInt()).map { i -> a.pointAt(start + i * 3.0).let { doubleArrayOf(it.lat, it.lng) } }
  val edges = a.edges.map { e -> JvEdge(e.startM, e.endM, e.lanes, e.turnLanes ?: "", e.isRamp, e.roadClass, e.wayId) }
  // the roads around: the graph first (real lanes), then the light package, then the map tiles
  var roads: List<JvRoad> = AppGraph.gh.roadsAround(o.lat, o.lng, 900.0).map { r ->
    JvRoad(r.lat, r.lon, r.cls, r.link, r.oneway, r.lanesFwd, r.lanesBack, r.turnLanes, r.roundabout, r.bridge, r.tunnel, r.way)
  }
  var source = "graph"
  if (roads.isEmpty()) {
    roads = runCatching { AppGraph.roads.near(o.lat, o.lng, 900.0) }.getOrDefault(emptyList()).map { r ->
      val lf = r.laneCount(true)
      val lb = if (r.oneway != 0) 0 else r.laneCount(false)
      JvRoad(DoubleArray(r.pts.size) { r.pts[it].lat }, DoubleArray(r.pts.size) { r.pts[it].lng }, r.hw.removeSuffix("_link"), r.isLink,
          r.oneway != 0, if (r.oneway < 0) 0 else lf, if (r.oneway < 0) lf else lb, r.turnLanes ?: "", r.roundabout, r.bridge, r.tunnel, r.osm)
    }
    source = "light package"
  }
  if (roads.isEmpty()) {
    val file = AppGraph.regions.regionAt(o.lat, o.lng)?.map
    if (file != null && file.exists()) {
      roads = RoadTiles.around(file, o.lat, o.lng, 800.0).map { l -> JvRoad(l.lat, l.lon, l.cls, l.ramp, l.oneway, 0, 0, "", false, l.bridge, l.tunnel) }
      source = "map tiles"
    }
  }
  val country = scene.country?.uppercase()
  val inp = JvInput(routeLL, start, m, edges, roads, scene.side, md.takeBranch, scene.fork, scene.motorway, scene.exit,
      if (scene.lanes.size >= 2) md.active else emptyList(), country in LHT)
  val t0 = System.currentTimeMillis()
  val geo = JvGeo.build(inp) ?: return null
  android.util.Log.i("NavMasterJV", "real junction from $source: ${roads.size} roads, lanes at the gore ${geo.lanesAtNode} " +
      "(${geo.profile.n.toList().distinct()} before, ${geo.outProfile.n.toList().distinct()} after), take ${geo.active}, " +
      "arrows ${geo.arrowsAtNode}, split ${geo.split.toInt()} m, ${geo.shapes.size} shapes in ${System.currentTimeMillis() - t0} ms")
  return RealJv(geo)
}

/**
 * Draws [rj] as the driver needs it: the road seen from above and behind the vehicle, with its real
 * lanes (the ones opening before the exit too), the road going on ahead, the arrows painted on the
 * lanes and the lanes to take painted violet with the arrow into the road to take. The camera waits
 * before the gore, then goes with the vehicle (the blue arrow on its lane). [vehAlong]: where the
 * vehicle is (metres of the route).
 */
internal fun DrawScope.drawRealJunction(rj: RealJv, vehAlong: Double, night: Boolean, signsSpace: Float, vehLane: Int? = null) {
  val w = size.width.toDouble()
  val h = size.height.toDouble()
  if (w < 10 || h < 10) return
  val geo = rj.geo
  // the camera moves with the vehicle: a new drawing every metre, not at every frame
  val vehKey = vehAlong.toLong()
  val key = "$vehKey:${size.width.toInt()}x${size.height.toInt()}:${signsSpace.toInt()}:$vehLane"
  var frame = rj.cacheFrame
  var cached = rj.cache
  if (key != rj.cacheKey || cached == null || frame == null) {
    frame = JvProject.frame(geo, vehAlong, w, h, signsSpace.toDouble(), vehLane) ?: return
    // one drawing for all the shapes of a kind in a layer (a few drawings instead of a thousand)
    val out = ArrayList<Pair<Path, Int>>()
    var curPath: Path? = null
    var curKind = -1
    for (p in frame.polys) {
      if (p.kind != curKind || curPath == null) {
        curPath = Path()
        curKind = p.kind
        out += curPath to p.kind
      }
      // all the outlines of a drawing turn the same way, so that two overlapping shapes add up
      var area = 0.0
      for (i in 0 until p.n) {
        val j = if (i + 1 == p.n) 0 else i + 1
        area += p.xs[i].toDouble() * p.ys[j] - p.xs[j].toDouble() * p.ys[i]
      }
      val path: Path = curPath
      if (area >= 0) {
        path.moveTo(p.xs[0], p.ys[0])
        for (i in 1 until p.n) path.lineTo(p.xs[i], p.ys[i])
      } else {
        path.moveTo(p.xs[p.n - 1], p.ys[p.n - 1])
        for (i in p.n - 2 downTo 0) path.lineTo(p.xs[i], p.ys[i])
      }
      path.close()
    }
    rj.cache = out
    rj.cacheFrame = frame
    rj.cacheKey = key
    cached = out
  }
  val hz = frame.horizon
  // sky, clouds, hills, land
  val skyTop = if (night) Color(0xFF0A1322) else Color(0xFF3E86D8)
  val skyLow = if (night) Color(0xFF22324A) else Color(0xFFD6E8F8)
  drawRect(Brush.verticalGradient(listOf(skyTop, skyLow), 0f, hz), size = Size(size.width, hz))
  if (!night && hz > h * 0.10) {
    for ((cxr, cyr, rr) in listOf(Triple(0.16, 0.40, 0.07), Triple(0.55, 0.25, 0.09), Triple(0.84, 0.45, 0.06))) {
      val x0 = (w * cxr).toFloat()
      val y0 = (hz * cyr).toFloat()
      val r = (w * rr).toFloat()
      drawOval(Color(0xE6FFFFFF), Offset(x0 - r, y0 - r * 0.35f), Size(r * 2, r * 0.7f))
      drawOval(Color(0xE6FFFFFF), Offset(x0 - r * 0.55f, y0 - r * 0.6f), Size(r * 1.1f, r * 0.8f))
    }
  }
  val hillKey = "${size.width.toInt()}x${size.height.toInt()}:${hz.toInt()}"
  val hills = (rj.hills?.takeIf { rj.hillsKey == hillKey } ?: Path().apply {
    moveTo(0f, hz)
    for (i in 0..100) lineTo((w * i / 100).toFloat(), (hz - h * (0.012 + 0.007 * sin(i * 0.21) + 0.005 * sin(i * 0.57 + 1.3))).toFloat())
    lineTo(size.width, hz)
    close()
  }.also { rj.hills = it; rj.hillsKey = hillKey })
  drawPath(hills, if (night) Color(0xFF1C2A1F) else Color(0xFF9CB99A))
  val grassFar = if (night) Color(0xFF22321E) else Color(0xFF8CC063)
  val grassNear = if (night) Color(0xFF152014) else Color(0xFF4F9136)
  drawRect(Brush.verticalGradient(listOf(grassFar, grassNear), hz, size.height), Offset(0f, hz), Size(size.width, size.height - hz))

  val shoulder = if (night) Color(0xFF1E2023) else Color(0xFF2E3034)
  val asphalt = if (night) Color(0xFF26282C) else Color(0xFF3B3E43)
  val mark = if (night) Color(0xFFD2D5D9) else Color.White
  val rail = if (night) Color(0xFF69707A) else Color(0xFFC7CCD2)
  val arrow = if (night) Color(0xFF8246DA) else Color(0xFF9148EE)
  val arrowLight = if (night) Color(0xFFB891F2) else Color(0xFFD2B4FF)
  val gore = if (night) Color(0xFFBFC3C8) else Color(0xFFECECEC)
  for ((p, kind) in cached) {
    when (kind) {
      JvGeo.SHOULDER -> drawPath(p, shoulder)
      JvGeo.ASPHALT -> drawPath(p, asphalt)
      JvGeo.MARK -> drawPath(p, mark)
      JvGeo.RAIL -> drawPath(p, rail)
      JvGeo.GORE -> drawPath(p, gore)
      JvGeo.LANE_ARROW -> drawPath(p, mark.copy(alpha = 0.75f))
      JvGeo.LANE_ARROW_LIT -> drawPath(p, mark)
      JvGeo.ARROW_SHADOW -> translate(0f, (h * 0.008).toFloat()) { drawPath(p, Color(0x66000000)) }
      JvGeo.ARROW -> {
        drawPath(p, Brush.verticalGradient(listOf(arrowLight, arrow), hz, size.height))
        drawPath(p, arrowLight, style = Stroke((w * 0.004).toFloat()))
      }
    }
  }
  // the vehicle: a blue arrow on its lane, where it really is
  frame.vehicle?.let { v ->
    val car = Path().apply { moveTo(v[0], v[1]); lineTo(v[2], v[3]); lineTo(v[4], v[5]); lineTo(v[6], v[7]); close() }
    drawPath(car, Color(0xFF1E88E5))
    drawPath(car, Color.White, style = Stroke((w * 0.005).toFloat().coerceAtLeast(2f)))
  }
}
