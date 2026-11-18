package app.navmaster.truck.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request for the engine (pass-through points, variants) and the variants read from its answer. */
class NmRoutingRequestTest {
  private val spec = RouteSpec(hgv = true, vehicleType = "hgv", heightM = 4.0, widthM = 2.55, lengthM = 16.5, weightT = 27.0, axleLoadT = 11.5,
      hazmat = false, topSpeedKmh = 80.0, avoidTolls = false, avoidFerries = true, avoidMotorways = false, preference = "recommended")
  private val three = listOf(doubleArrayOf(45.46, 9.19), doubleArrayOf(45.10, 10.0), doubleArrayOf(44.49, 11.34))
  private val two = listOf(doubleArrayOf(45.46, 9.19), doubleArrayOf(44.49, 11.34))

  private fun nav(o: JsonObject) = o["navmaster"]!!.jsonObject

  @Test
  fun passThroughPointByPoint() {
    val o = NmRouting.request(three, spec, listOf("break", "via", "break"), 0, null)
    assertEquals(listOf(false, true, false), nav(o)["pass_through"]!!.jsonArray.map { it.jsonPrimitive.boolean })
    // the engine does not get openrouteservice's continue_straight any more
    assertNull(o["continue_straight"])
    // the server (maybe an older engine) keeps continue_straight
    val server = NmRouting.request(three, spec, listOf("break", "via", "break"), 0, null, forServer = true)
    assertEquals(true, server["continue_straight"]!!.jsonPrimitive.boolean)
    assertNull(nav(server)["pass_through"])
  }

  @Test
  fun noPassThroughWithoutIntermediateVia() {
    // stops only: the request is the one of before (no pass_through, no continue_straight)
    val o = NmRouting.request(three, spec, listOf("break", "break", "break"), 0, null)
    assertNull(nav(o)["pass_through"])
    assertNull(o["continue_straight"])
    // a "via" as the last point counts for nothing
    val last = NmRouting.request(two, spec, listOf("break", "via"), 0, null)
    assertNull(nav(last)["pass_through"])
    assertEquals(true, nav(last)["roads"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun automaticVariants() {
    val o = NmRouting.request(three, spec, listOf("break", "via", "break"), 0, null, VariantAsk.AUTO)
    val a = nav(o)["alternatives"]!!.jsonObject
    assertEquals(4, a["count"]!!.jsonPrimitive.int)
    assertEquals(1.10, a["tier1_factor"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(1.10, a["tier1_km_factor"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(1.20, a["max_factor"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(1.20, a["max_km_factor"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(25_000, a["budget_ms"]!!.jsonPrimitive.int)
    assertEquals(true, a["exclude_best"]!!.jsonPrimitive.boolean)
    assertNull(a["allow_fewer_km"])
    // any number of points, never openrouteservice's alternatives towards the engine
    assertNull(o["alternative_routes"])
    assertNotNull(nav(o)["pass_through"])
  }

  @Test
  fun moreRoutesVariants() {
    val o = NmRouting.request(two, spec, listOf("break", "break"), 0, null, VariantAsk.MORE)
    val a = nav(o)["alternatives"]!!.jsonObject
    assertEquals(6, a["count"]!!.jsonPrimitive.int)
    assertEquals(1.25, a["tier1_factor"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(1.5, a["max_factor"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(1.6, a["max_km_factor"]!!.jsonPrimitive.double, 1e-9)
    assertEquals(true, a["allow_fewer_km"]!!.jsonPrimitive.boolean)
    assertNull(a["budget_ms"])
    assertNull(o["alternative_routes"])
    // with the variants already on screen: those plus 3 new ones (the engine does not know them), at most 9
    assertEquals(6, VariantAsk.more(0).count)
    assertEquals(7, VariantAsk.more(3).count)
    assertEquals(VariantAsk.MAX_COUNT, VariantAsk.more(8).count)
    assertEquals(1.6, VariantAsk.more(3).maxKmFactor, 1e-9)
    assertEquals(true, VariantAsk.more(3).allowFewerKm)
    val o7 = NmRouting.request(two, spec, listOf("break", "break"), 0, null, VariantAsk.more(3))
    assertEquals(7, nav(o7)["alternatives"]!!.jsonObject["count"]!!.jsonPrimitive.int)
    // the server gets openrouteservice's alternatives instead
    val server = NmRouting.request(two, spec, listOf("break", "break"), 2, null, VariantAsk.MORE, forServer = true)
    assertNull(nav(server)["alternatives"])
    assertEquals(3, server["alternative_routes"]!!.jsonObject["target_count"]!!.jsonPrimitive.int)
  }

  /**
   * The bodies the engine's own test (navmaster-engine AppContractTest) sends through its
   * DirectionsService on Heidelberg, letter by letter: if this changes, change that test too.
   */
  @Test
  fun engineContractBodies() {
    val pts = listOf(doubleArrayOf(49.4036, 8.6757), doubleArrayOf(49.4094, 8.6937), doubleArrayOf(49.4186, 8.7606))
    val kinds = listOf("break", "via", "break")
    assertEquals(
        """{"coordinates":[[8.6757,49.4036],[8.6937,49.4094],[8.7606,49.4186]],"preference":"recommended","units":"m","instructions":true,"language":"it","maneuvers":true,"extra_info":["tollways","waycategory","waytype"],"geometry":true,"navmaster":{"roads":true,"avoid_penalty":true,"pass_through":[false,true,false],"alternatives":{"count":4,"tier1_factor":1.1,"tier1_km_factor":1.1,"max_factor":1.2,"max_km_factor":1.2,"budget_ms":25000,"exclude_best":true}},"options":{"avoid_features":["ferries"],"vehicle_type":"hgv","profile_params":{"restrictions":{"height":4.0,"width":2.55,"length":16.5,"weight":27.0,"axleload":11.5}}},"maximum_speed":80.0}""",
        NmRouting.request(pts, spec, kinds, 0, null, VariantAsk.AUTO).toString())
    assertEquals(
        """{"coordinates":[[8.6757,49.4036],[8.6937,49.4094],[8.7606,49.4186]],"preference":"recommended","units":"m","instructions":true,"language":"it","maneuvers":true,"extra_info":["tollways","waycategory","waytype"],"geometry":true,"navmaster":{"roads":true,"avoid_penalty":true,"pass_through":[false,true,false],"alternatives":{"count":6,"tier1_factor":1.25,"tier1_km_factor":1.25,"max_factor":1.5,"max_km_factor":1.6,"allow_fewer_km":true,"exclude_best":true}},"options":{"avoid_features":["ferries"],"vehicle_type":"hgv","profile_params":{"restrictions":{"height":4.0,"width":2.55,"length":16.5,"weight":27.0,"axleload":11.5}}},"bearings":[[90.0,45],[],[]],"maximum_speed":80.0}""",
        NmRouting.request(pts, spec, kinds, 0, listOf(90.0, null, null), VariantAsk.MORE).toString())
  }

  @Test
  fun plainRequestUnchanged() {
    val o = NmRouting.request(two, spec, listOf("break", "break"), 0, listOf(90.0, null))
    assertEquals(setOf("roads", "avoid_penalty"), nav(o).keys)
    assertEquals(2, o["bearings"]!!.jsonArray.size)
    assertTrue((o["bearings"]!!.jsonArray[1] as JsonArray).isEmpty())
  }

  @Test
  fun variantsReadFromTheAnswer() {
    val text = """
      {"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[9.19,45.46],[10.0,45.1],[11.34,44.49]]},
         "properties":{"way_points":[0,1,2],"summary":{"distance":215000.0,"duration":9000.0},
           "navmaster":{"roads":[],"variant":{"rank":1,"tier":1,"source":"motorway","label":"via A21","diff":"+4 min, −6 km",
             "signature":"A1 · A21 · A1","refs":[{"ref":"A1","km":61.0},{"ref":"A21","km":87.2}],"new_refs":["A21"],
             "extra_s":240.0,"extra_m":-6000.0,"factor":1.03,"km_factor":0.97,"longer":false}}}},
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[9.19,45.46]]},"properties":{}},
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[9.19,45.46],[10.5,45.0],[11.34,44.49]]},
         "properties":{"way_points":[0,1,2],"summary":{"distance":240000.0,"duration":10200.0},
           "navmaster":{"variant":{"rank":2,"tier":2,"source":"penalty","label":"via SS9","diff":"+24 min, +19 km","longer":true}}}},
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[9.19,45.46],[11.34,44.49]]},
         "properties":{"way_points":[0,1],"summary":{"distance":230000.0,"duration":9600.0}}},
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[10.38,45.15],[16.6,48.8],[19.04,49.82]]},
         "properties":{"way_points":[0,2],"summary":{"distance":1180000.0,"duration":58000.0},
           "navmaster":{"variant":{"rank":3,"tier":1,"source":"crossing","label":"via D2 + D1, per la Cechia","diff":"+16 min, +24 km",
             "countries":["italy","austria","czech-republic","poland"],
             "crossings":[{"lat":47.0,"lon":11.5},{"lat":48.75,"lon":16.65},{"lat":49.9,"lon":18.3}],"crossing":{"lat":47.0,"lon":11.5}}}}}
      ],"metadata":{"countries":["italy","austria","slovakia","poland"],"navmaster":{"alternatives":{"asked":4,"found":3,"variants":2,"note":null}}}}
    """.trimIndent()
    val routes = NmRouting.parse(Json.parseToJsonElement(text).jsonObject, 3)!!
    assertEquals(4, routes.size)
    val v = routes[0].variant!!
    assertEquals(1, v.rank)
    assertEquals(1, v.tier)
    assertEquals("motorway", v.source)
    assertEquals("via A21", v.label)
    assertEquals("+4 min, −6 km", v.diff)
    assertEquals("A1 · A21 · A1", v.signature)
    assertEquals(listOf("A1" to 61.0, "A21" to 87.2), v.refs)
    assertEquals(240.0, v.extraS, 1e-9)
    assertEquals(-6000.0, v.extraM, 1e-9)
    assertFalse(v.longer)
    assertEquals(0, routes[0].feature)
    assertEquals(listOf(0, 1, 2), routes[0].waypointIndex.toList())
    // the feature without a line is skipped, the next one keeps its place in the answer (its guidance)
    assertEquals(2, routes[1].feature)
    assertTrue(routes[1].variant!!.longer)
    assertEquals("via SS9", routes[1].variant!!.label)
    assertTrue(routes[1].variant!!.refs.isEmpty())
    // no variant said (a trip across borders, an older engine): none
    assertNull(routes[2].variant)
    assertEquals(3, routes[2].feature)
    assertEquals(listOf(0, 1), routes[2].waypointIndex.toList())
    // no countries said: empty
    assertTrue(v.countries.isEmpty() && v.crossings.isEmpty() && v.newCountries.isEmpty())
    // a variant through another country: its countries, its crossings, the countries the best does not cross
    val across = routes[3].variant!!
    assertEquals(listOf("italy", "austria", "czech-republic", "poland"), across.countries)
    assertEquals(listOf(47.0 to 11.5, 48.75 to 16.65, 49.9 to 18.3), across.crossings)
    assertEquals(listOf("czech-republic"), across.newCountries)
    assertEquals("via D2 + D1, per la Cechia", across.label)
  }
}
