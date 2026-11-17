package app.navmaster.truck.routing

import it.navmaster.engine.RoutingGraph
import it.navmaster.engine.api.DirectionsRequest
import it.navmaster.engine.api.DirectionsService
import it.navmaster.engine.api.RequestContext
import it.navmaster.engine.api.ResponseFormat
import it.navmaster.engine.api.SearchSlots
import it.navmaster.engine.profile.HgvProfile
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End to end on the engine jar of the app: the body NmRouting.request writes (a via in the middle,
 * the automatic variants and "Altri percorsi") goes through the engine's DirectionsService as
 * NmEngine sends it, and the answer is read by NmRouting.parse and guided by OrsGuide. Needs the
 * engine's Heidelberg extract next to the app (../navmaster-engine/data), else it is skipped.
 */
class EngineContractTest {
  private val spec = RouteSpec(hgv = true, vehicleType = "hgv", heightM = 4.0, widthM = 2.55, lengthM = 16.5, weightT = 27.0, axleLoadT = 11.5,
      hazmat = false, topSpeedKmh = 80.0, avoidTolls = false, avoidFerries = true, avoidMotorways = false, preference = "recommended")
  private val points = listOf(doubleArrayOf(49.4036, 8.6757), doubleArrayOf(49.4094, 8.6937), doubleArrayOf(49.4186, 8.7606))
  private val kinds = listOf("break", "via", "break")

  @Test
  fun appBodiesThroughTheEngine() {
    val pbf = File("../../navmaster-engine/data/heidelberg.test.pbf")
    assumeTrue("no Heidelberg extract at ${pbf.absolutePath}", pbf.isFile)
    val dir = Files.createTempDirectory("nm-app-contract").toFile()
    val pool = Executors.newFixedThreadPool(1) { r -> Thread(r, "nm-leg").also { it.isDaemon = true } }
    try {
      RoutingGraph.build(pbf, dir, listOf(HgvProfile())).first.use { it.prepareCH(landmarkCount = 4) }
      RoutingGraph.load(dir, mmap = false).use { graph ->
        val service = DirectionsService(graph)
        fun answer(body: String): String {
          @Suppress("UNCHECKED_CAST")
          val parsed = it.navmaster.engine.api.Json.parse(body) as Map<String, Any?>
          val rc = RequestContext(executor = pool, slots = SearchSlots(2))
          return it.navmaster.engine.api.Json.stringify(service.handle(DirectionsRequest.fromJson("driving-hgv", parsed), ResponseFormat.GEOJSON, rc))
        }
        // the best route first, as the app asks for it
        val bestText = answer(NmRouting.request(points, spec, kinds, 0, null).toString())
        val best = checkNotNull(NmRouting.parse(Json.parseToJsonElement(bestText).jsonObject, 3)) { "no best route" }
        assertEquals(1, best.size)
        assertEquals(3, best[0].waypointIndex.size)
        assertTrue(checkNotNull(OrsGuide.build(bestText, 0, true, null, kinds)) { "best not guided" }.osrm.contains("\"routes\""))
        var variants = 0
        for (ask in listOf(VariantAsk.AUTO, VariantAsk.MORE)) {
          val text = answer(NmRouting.request(points, spec, kinds, 0, listOf(90.0, null, null), ask).toString())
          val root = Json.parseToJsonElement(text).jsonObject
          assertTrue("engine error: ${root["error"]}", root["error"] == null)
          val found = NmRouting.parse(root, 3) ?: emptyList()
          assertTrue(found.size <= ask.count - 1)
          for (r in found) {
            assertEquals(3, r.waypointIndex.size)
            val v = checkNotNull(r.variant) { "properties.navmaster.variant" }
            assertTrue(v.rank >= 1 && v.label.isNotBlank())
            assertTrue(r.timeMs <= ask.maxFactor * best[0].timeMs + 1000)
            val guided = checkNotNull(OrsGuide.build(text, r.feature, true, null, kinds)) { "guidance of variant ${v.rank}" }
            assertTrue(guided.osrm.contains("\"routes\""))
          }
          variants += found.size
          println("app → engine ${ask.count}: ${found.size} variants ${found.map { it.variant?.label + " " + it.variant?.diff }}")
        }
        assertTrue("no variant from the app bodies on Heidelberg", variants > 0)
      }
    } finally {
      pool.shutdownNow()
      dir.deleteRecursively()
    }
  }

  /**
   * A destination in the woods east of the city, about 1.3 km from the nearest road the lorry may
   * drive: with openrouteservice's 400 m the engine answers its error 2010 for that point, with the
   * app's wider radius (2 km) a route, and the app measures how far the point was moved.
   */
  @Test
  fun stopFarFromTheLorrysRoadsReachedWithAWiderRadius() {
    val pbf = File("../../navmaster-engine/data/heidelberg.test.pbf")
    assumeTrue("no Heidelberg extract at ${pbf.absolutePath}", pbf.isFile)
    val dir = Files.createTempDirectory("nm-app-contract-far").toFile()
    val pool = Executors.newFixedThreadPool(1) { r -> Thread(r, "nm-leg").also { it.isDaemon = true } }
    try {
      RoutingGraph.build(pbf, dir, listOf(HgvProfile())).first.use { it.prepareCH(landmarkCount = 4) }
      RoutingGraph.load(dir, mmap = false).use { graph ->
        val service = DirectionsService(graph)
        fun answer(body: String): String {
          @Suppress("UNCHECKED_CAST")
          val parsed = it.navmaster.engine.api.Json.parse(body) as Map<String, Any?>
          val rc = RequestContext(executor = pool, slots = SearchSlots(2))
          return try {
            it.navmaster.engine.api.Json.stringify(service.handle(DirectionsRequest.fromJson("driving-hgv", parsed), ResponseFormat.GEOJSON, rc))
          } catch (e: it.navmaster.engine.api.ApiException) {
            it.navmaster.engine.api.Json.stringify(service.errorBody(e))
          }
        }
        val far = listOf(doubleArrayOf(49.4036, 8.6757), doubleArrayOf(49.36757, 8.79347))
        val kinds2 = listOf("break", "break")
        // openrouteservice's request: the point has no road within 400 m
        val plain = Json.parseToJsonElement(answer(NmRouting.request(far, spec, kinds2, 0, null).toString())).jsonObject
        val error = checkNotNull(plain["error"]?.jsonObject) { "a route to the woods within 400 m?" }
        assertEquals(2010, error["code"]!!.jsonPrimitive.int)
        val message = error["message"]!!.jsonPrimitive.content
        assertEquals(1, NmRouting.pointOf(message))
        assertEquals("nessuna strada percorribile entro 400 m dalla destinazione", NmRouting.words(2010, message, 2))
        // the app's second try: the same body with the wider radius for that point
        val radii = SnapRadii().also { it.widen(far[1][0], far[1][1]) }.radiiOf(far)
        assertTrue(radii.contentEquals(doubleArrayOf(400.0, 2000.0)))
        val text = answer(NmRouting.request(far, spec, kinds2, 0, null, radiuses = radii).toString())
        val root = Json.parseToJsonElement(text).jsonObject
        assertTrue("engine error: ${root["error"]}", root["error"] == null)
        val found = checkNotNull(NmRouting.parse(root, 2)) { "no route with the wider radius" }
        val moved = NmRouting.moved(far, radii, found[0])
        assertEquals(1, moved.size)
        assertEquals(1, moved[0].index)
        assertTrue("moved by ${moved[0].meters} m", moved[0].meters in 1000.0..2000.0)
        // where the guidance really arrives: the route's last point (the stop counts as reached there)
        assertEquals(found[0].lat.last(), moved[0].lat, 1e-9)
        assertEquals(found[0].lon.last(), moved[0].lon, 1e-9)
        assertTrue(checkNotNull(OrsGuide.build(text, 0, true, null, kinds2)) { "far route not guided" }.osrm.contains("\"routes\""))
        println("app → engine: destination in the woods moved by ${moved[0].meters.toInt()} m, route ${found[0].distanceM.toInt()} m")
      }
    } finally {
      pool.shutdownNow()
      dir.deleteRecursively()
    }
  }
}
