package app.navmaster.truck.routing

import it.navmaster.engine.storage.BBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which graphs a trip across borders opens (NmEngine.openPlan): the countries of the points, then
 * those on the sequences of countries the engine will weigh (CrossBorderDirections.countrySequences
 * on the installed countries), MAX_OPEN in all; the rest is said as left out. Canneto sull'Oglio →
 * Bielsko-Biała with seven countries installed: Austria, the Czech Republic, Slovakia and Poland
 * in, Germany (and Hungary, Slovenia) out.
 */
class NmEngineOpenPlanTest {
  // catalog outlines [minLon, minLat, maxLon, maxLat], roughly
  private val boxes = mapOf(
      "italy" to listOf(6.6, 36.6, 18.6, 47.1), "austria" to listOf(9.5, 46.3, 17.2, 49.1), "germany" to listOf(5.8, 47.2, 15.1, 55.1),
      "czech-republic" to listOf(12.0, 48.5, 18.9, 51.1), "slovakia" to listOf(16.8, 47.7, 22.6, 49.7), "poland" to listOf(14.1, 49.0, 24.2, 55.0),
      "hungary" to listOf(16.1, 45.7, 22.9, 48.6), "slovenia" to listOf(13.3, 45.4, 16.6, 46.9), "switzerland" to listOf(5.9, 45.8, 10.5, 47.8),
  )
  private val bounds = { id: String -> boxes[id]?.let { b -> BBox(b[0], b[2], b[1], b[3]) } }
  // the borders with crossings (outlines touching is not enough: Austria's and Poland's boxes overlap by a hair)
  private val borders = setOf("italy-austria", "italy-slovenia", "slovenia-austria", "slovenia-hungary", "austria-germany", "austria-czech-republic",
      "austria-slovakia", "austria-hungary", "germany-czech-republic", "germany-poland", "czech-republic-poland", "czech-republic-slovakia",
      "slovakia-poland", "slovakia-hungary", "italy-switzerland", "switzerland-austria", "switzerland-germany")
  private val linked = { a: String, b: String -> bounds(a)!!.intersects(bounds(b)!!) && ("$a-$b" in borders || "$b-$a" in borders) }
  private val canneto = doubleArrayOf(45.1502, 10.3792)
  private val bielsko = doubleArrayOf(49.8224, 19.0444)

  @Test
  fun sequencesBringTheCzechRepublicAndSlovakiaIn() {
    val installed = listOf("italy", "austria", "germany", "czech-republic", "slovakia", "poland", "hungary")
    // today's "near" order: the countries of the points first, then the others near the way
    val near = listOf("italy", "poland", "austria", "germany", "czech-republic", "slovakia", "hungary")
    val plan = NmEngine.openPlan(installed, listOf("italy", "poland"), listOf(canneto, bielsko), near, bounds, linked)
    // the countries of the points first, then those of the two straightest sequences (either order)
    assertEquals(listOf("italy", "poland", "austria"), plan.open.take(3))
    assertEquals(setOf("czech-republic", "slovakia"), plan.open.drop(3).toSet())
    assertEquals(listOf("italy-austria-czech-republic-poland", "italy-austria-slovakia-poland", "italy-austria-germany-poland").toSet(), plan.sequences.map { it.joinToString("-") }.toSet())
    assertEquals(listOf("germany", "hungary"), plan.excluded)
    val seqs = plan.sequences.map { it.joinToString("-") }
    assertTrue(seqs.toString(), "italy-austria-czech-republic-poland" in seqs && "italy-austria-slovakia-poland" in seqs)
    // the shorter and straighter sequences first: the German one (a long way round) last
    assertTrue(seqs.toString(), seqs.indexOf("italy-austria-germany-poland") > seqs.indexOf("italy-austria-slovakia-poland"))
    assertEquals(NmEngine.MAX_OPEN, plan.open.size)
  }

  @Test
  fun withoutSequencesTodaysOrder() {
    // Poland not linked to anything installed: no sequence, the near countries as before
    val installed = listOf("italy", "poland", "hungary")
    val plan = NmEngine.openPlan(installed, listOf("italy", "poland"), listOf(canneto, bielsko), listOf("italy", "poland", "hungary"), bounds, linked)
    assertEquals(listOf("italy", "poland", "hungary"), plan.open)
    assertTrue(plan.sequences.isEmpty())
    assertTrue(plan.excluded.isEmpty())
  }

  @Test
  fun oneCountryAndAPointOutsideEveryOutline() {
    val plan = NmEngine.openPlan(listOf("italy", "austria"), listOf("italy", null), listOf(canneto, doubleArrayOf(45.5, 10.5)), listOf("italy"), bounds, linked)
    assertEquals(listOf("italy"), plan.open)
    assertTrue(plan.sequences.isEmpty())
  }

  @Test
  fun aStopInAThirdCountryAddsItsSequences() {
    val installed = listOf("italy", "austria", "germany", "czech-republic", "slovakia", "poland")
    val innsbruck = doubleArrayOf(47.2692, 11.4041)
    val plan = NmEngine.openPlan(installed, listOf("italy", "austria", "poland"), listOf(canneto, innsbruck, bielsko), listOf("italy", "austria", "poland"), bounds, linked)
    assertEquals(listOf("italy", "austria", "poland"), plan.open.take(3))
    assertTrue(plan.sequences.any { it == listOf("italy", "austria") })
    assertTrue(plan.sequences.any { it == listOf("austria", "czech-republic", "poland") } && plan.sequences.any { it == listOf("austria", "slovakia", "poland") })
    assertEquals(NmEngine.MAX_OPEN, plan.open.size)
  }

  @Test
  fun aStopKeepsTheNextLegsSequencesBeforeTheFirstLegsDetours() {
    // Canneto → Vienna (stop) → Bielsko with Slovenia (and Hungary) installed: the detours of the first leg
    // (italy-slovenia-austria, italy-slovenia-hungary-austria) must not push out the only ways of the second
    // leg (austria-slovakia-poland, austria-czech-republic-poland): without them the engine has no sequence at all
    val installed = listOf("italy", "austria", "germany", "czech-republic", "slovakia", "poland", "hungary", "slovenia")
    val vienna = doubleArrayOf(48.2082, 16.3738)
    val plan = NmEngine.openPlan(installed, listOf("italy", "austria", "poland"), listOf(canneto, vienna, bielsko), installed, bounds, linked)
    assertEquals(listOf("italy", "austria", "poland"), plan.open.take(3))
    assertEquals(plan.open.toString(), setOf("czech-republic", "slovakia"), plan.open.drop(3).toSet())
    assertTrue(plan.sequences.toString(), plan.sequences.any { it == listOf("italy", "slovenia", "austria") })
    assertTrue("slovenia" in plan.excluded && "germany" in plan.excluded)
    // the sequences in the order the graphs were taken: every leg's shortest ways before any leg's detours
    val seqs = plan.sequences.map { it.joinToString("-") }
    assertTrue(seqs.toString(), seqs.indexOf("italy-slovenia-austria") > seqs.indexOf("austria-czech-republic-poland") && seqs.indexOf("italy-slovenia-austria") > seqs.indexOf("austria-slovakia-poland"))
  }

  @Test
  fun aStopInAustriaWithSwitzerlandInstalled() {
    // Canneto → Innsbruck (stop) → Bielsko with Switzerland installed: the Swiss detour of the first leg
    // stays behind the Czech and Slovak ways of the second; Germany (a long way round) out
    val installed = listOf("italy", "austria", "germany", "czech-republic", "slovakia", "poland", "switzerland")
    val innsbruck = doubleArrayOf(47.2692, 11.4041)
    val plan = NmEngine.openPlan(installed, listOf("italy", "austria", "poland"), listOf(canneto, innsbruck, bielsko), installed, bounds, linked)
    assertEquals(listOf("italy", "austria", "poland"), plan.open.take(3))
    assertEquals(plan.open.toString(), setOf("czech-republic", "slovakia"), plan.open.drop(3).toSet())
    assertTrue(plan.sequences.toString(), plan.sequences.any { it == listOf("italy", "switzerland", "austria") })
    assertEquals(plan.excluded.toString(), setOf("switzerland", "germany"), plan.excluded.toSet())
  }

  @Test
  fun aRecalculationFromAustriaClosesItalyNotItsOwnTransitGraphs() {
    // after Canneto → Bielsko: italy and poland routed, the transit graphs never routed; past the Brenner
    // the recalculation from Austria plans austria, poland, czech-republic, slovakia, germany
    val installed = listOf("italy", "austria", "germany", "czech-republic", "slovakia", "poland")
    val innsbruck = doubleArrayOf(47.2692, 11.4041)
    val plan = NmEngine.openPlan(installed, listOf("austria", "poland"), listOf(innsbruck, bielsko), listOf("austria", "poland"), bounds, linked)
    assertEquals(plan.open.toString(), setOf("austria", "poland", "czech-republic", "slovakia", "germany"), plan.open.toSet())
    val open = listOf(NmEngine.Slot("italy", 100, 100), NmEngine.Slot("poland", 100, 200), NmEngine.Slot("austria", 0, 200),
        NmEngine.Slot("czech-republic", 0, 200), NmEngine.Slot("slovakia", 0, 200))
    // opening germany: italy goes (the only one outside the plan), not a transit graph with routed 0
    assertEquals("italy", NmEngine.toClose(open, plan.open.toSet()))
    // without the plan the old rule would have closed a graph of the plan itself
    assertTrue(NmEngine.toClose(open, emptySet()) in plan.open)
    // every open graph in the plan: nothing to close
    assertEquals(null, NmEngine.toClose(open.filter { it.id != "italy" }, plan.open.toSet()))
  }

  @Test
  fun theWarmUpNeverClosesAGraphARouteJustUsed() {
    // after Canneto → Bielsko every graph of the trip has routed set (transit ones too); the warm-up
    // opening monaco (background: protectAfter = now − 15 min) finds nothing it may close, and skips it
    val now = 10_000_000L
    val trip = listOf("italy", "poland", "austria", "slovakia", "czech-republic").map { NmEngine.Slot(it, now - 60_000, now - 60_000) }
    assertEquals(null, NmEngine.toClose(trip, emptySet(), now - NmEngine.ROUTED_KEEP_MS))
    // a graph never routed (an earlier warm-up) goes first, as before
    val withWarm = trip.drop(1) + NmEngine.Slot("hungary", 0, now)
    assertEquals("hungary", NmEngine.toClose(withWarm, emptySet(), now - NmEngine.ROUTED_KEEP_MS))
    // a trip older than 15 minutes is no longer protected
    val old = trip.map { it.copy(routed = now - NmEngine.ROUTED_KEEP_MS - 1) }
    assertEquals("italy", NmEngine.toClose(old, emptySet(), now - NmEngine.ROUTED_KEEP_MS))
    // a route (not the warm-up) still closes the oldest one when it has to
    assertTrue(NmEngine.toClose(trip, emptySet()) != null)
  }
}
