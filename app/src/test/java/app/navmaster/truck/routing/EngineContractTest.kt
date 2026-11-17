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
import kotlinx.serialization.json.jsonObject
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
}
