package app.navmaster.truck.nav

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ferrostar.GeographicCoordinate

/** A point added to the trip goes where it lengthens it the least, and no stop still ahead is lost. */
class StopInsertionTest {
  private fun c(lat: Double, lng: Double) = GeographicCoordinate(lat, lng)

  // along a line north: start, two deliveries, destination
  private val start = c(45.0, 9.0)
  private val a = Stop(c(45.2, 9.0), "Consegna A")
  private val b = Stop(c(45.4, 9.0), "Consegna B")
  private val dest = Stop(c(45.6, 9.0), "Destinazione")

  @Test
  fun bestInsertionBetweenTheRightStops() {
    val stops = listOf(a, b, dest).map { it.coordinate }
    assertEquals(0, NavViewModel.bestInsertion(start, stops, c(45.1, 9.0)))
    assertEquals(1, NavViewModel.bestInsertion(start, stops, c(45.3, 9.01)))
    assertEquals(2, NavViewModel.bestInsertion(start, stops, c(45.5, 8.99)))
    // past the destination: still before it, never after
    assertEquals(2, NavViewModel.bestInsertion(start, stops, c(45.9, 9.0)))
    // without a start: before the destination
    assertEquals(2, NavViewModel.bestInsertion(null, stops, c(45.1, 9.0)))
    assertEquals(0, NavViewModel.bestInsertion(null, emptyList(), c(45.1, 9.0)))
  }

  @Test
  fun stopAddedWhileDrivingKeepsTheRemainingStops() {
    val via = Stop(c(45.3, 9.02), "Passa di qui", via = true)
    val after = NavViewModel.withStop(start, listOf(a, b, dest), via)
    assertEquals(listOf(a, via, b, dest), after)
    // a delivery near the destination: the deliveries before it stay
    val late = Stop(c(45.55, 9.0), "Ritiro")
    assertEquals(listOf(a, b, late, dest), NavViewModel.withStop(start, listOf(a, b, dest), late))
    // only the destination left
    assertEquals(listOf(via, dest), NavViewModel.withStop(start, listOf(dest), via))
    // no stop at all: the point alone
    assertEquals(listOf(via), NavViewModel.withStop(start, emptyList(), via))
  }

  @Test
  fun variantTitles() {
    val info = app.navmaster.truck.routing.VariantInfo(1, 1, "motorway", "via A21", "+4 min, −6 km", "A1 · A21 · A1", emptyList(), 240.0, -6000.0, false)
    assertEquals("via A21 · +4 min, −6 km", NavViewModel.variantTitle(info, 1, more = false))
    val longer = app.navmaster.truck.routing.VariantInfo(2, 2, "penalty", "via SS9", "", "", emptyList(), 1440.0, 19000.0, true)
    // automatic: never "più lunga"; the differences worked out when the engine does not say them
    assertEquals("via SS9 · +24 min, +19 km", NavViewModel.variantTitle(longer, 2, more = false))
    assertEquals("via SS9 · più lunga · +24 min, +19 km", NavViewModel.variantTitle(longer, 2, more = true))
    // shorter but slower ("longer" for the engine, by its time): never "più lunga", only the differences
    val slower = app.navmaster.truck.routing.VariantInfo(3, 2, "penalty", "via SS45", "+14 min, −8 km", "", emptyList(), 840.0, -8000.0, true)
    assertEquals("via SS45 · +14 min, −8 km", NavViewModel.variantTitle(slower, 3, more = true))
    assertEquals("Alternativa 3", NavViewModel.variantTitle(null, 3, more = true))
    // across borders: a variant through a country the best route does not cross says so; the engine's own
    // label first, else the catalog's name (with its article)
    val czech = app.navmaster.truck.routing.VariantInfo(3, 1, "crossing", "via D2 + D1, per la Cechia", "+16 min, +24 km", "", emptyList(), 960.0, 24000.0, false,
        countries = listOf("italy", "austria", "czech-republic", "poland"), newCountries = listOf("czech-republic"))
    assertEquals("via D2 + D1, per la Cechia · +16 min, +24 km", NavViewModel.variantTitle(czech, 3, more = false))
    val unnamed = app.navmaster.truck.routing.VariantInfo(3, 1, "crossing", "via D2 + D1", "+16 min, +24 km", "", emptyList(), 960.0, 24000.0, false,
        countries = listOf("italy", "austria", "czech-republic", "poland"), newCountries = listOf("czech-republic"))
    assertEquals("via D2 + D1, per la Repubblica Ceca · +16 min, +24 km", NavViewModel.variantTitle(unnamed, 3, more = false, names = mapOf("czech-republic" to "Repubblica Ceca")))
    assertEquals("via D2 + D1, per l'Austria · +16 min, +24 km",
        NavViewModel.variantTitle(unnamed, 3, more = false, names = mapOf("czech-republic" to "Austria")))
    assertEquals("via D2 + D1, per la Czech-republic · +16 min, +24 km", NavViewModel.variantTitle(unnamed, 3, more = false, names = emptyMap()))
    // what the engine really writes for a country it has no name for: the bare id, replaced by the catalog's name
    val bareId = app.navmaster.truck.routing.VariantInfo(3, 1, "crossing", "via D2 + D1, per czech-republic", "+16 min, +24 km", "", emptyList(), 960.0, 24000.0, false,
        countries = listOf("italy", "austria", "czech-republic", "poland"), newCountries = listOf("czech-republic"))
    assertEquals("via D2 + D1, per la Repubblica Ceca · +16 min, +24 km", NavViewModel.variantTitle(bareId, 3, more = false, names = mapOf("czech-republic" to "Repubblica Ceca")))
    assertEquals("via D2 + D1, per czech-republic · +16 min, +24 km", NavViewModel.variantTitle(bareId, 3, more = false, names = emptyMap()))
    // the engine's own name stays as it is (nothing to replace), also with the catalog's name at hand
    assertEquals("via D2 + D1, per la Cechia · +16 min, +24 km", NavViewModel.variantTitle(czech, 3, more = false, names = mapOf("czech-republic" to "Repubblica Ceca")))
    val twoIds = app.navmaster.truck.routing.VariantInfo(3, 1, "crossing", "altro valico, per czech-republic e slovakia", "+16 min, +24 km", "", emptyList(), 960.0, 24000.0, false,
        countries = listOf("italy", "austria", "czech-republic", "slovakia", "poland"), newCountries = listOf("czech-republic", "slovakia"))
    assertEquals("altro valico, per la Repubblica Ceca e slovakia · +16 min, +24 km",
        NavViewModel.variantTitle(twoIds, 3, more = false, names = mapOf("czech-republic" to "Repubblica Ceca")))
    // the same countries as the best: nothing added
    val same = app.navmaster.truck.routing.VariantInfo(3, 1, "crossing", "altro valico", "+6 min, +4 km", "", emptyList(), 360.0, 4000.0, false,
        countries = listOf("italy", "austria"), newCountries = emptyList())
    assertEquals("altro valico · +6 min, +4 km", NavViewModel.variantTitle(same, 3, more = false))
    // the engine's titles: the roads the best does not use, or the same road on another line
    val other = app.navmaster.truck.routing.VariantInfo(2, 1, "motorway", "via A1, altro tracciato", "+11 min, +19 km", "A1 · A24", emptyList(), 660.0, 19000.0, false)
    assertEquals("via A1, altro tracciato · +11 min, +19 km", NavViewModel.variantTitle(other, 2, more = false))
    val a24 = app.navmaster.truck.routing.VariantInfo(1, 1, "motorway", "via A24", "+11 min, +19 km", "A1 · A24", emptyList(), 660.0, 19000.0, false)
    // a title already on screen (another request of variants): a number after the road
    assertEquals("via A24 (2) · +11 min, +19 km", NavViewModel.variantTitle(a24, 4, more = true, taken = listOf("via A24 · +11 min, +19 km")))
    assertEquals("via A24 (3) · +11 min, +19 km",
        NavViewModel.variantTitle(a24, 5, more = true, taken = listOf("via A24 · +11 min, +19 km", "via A24 (2) · +11 min, +19 km")))
    assertEquals("via A24 · +11 min, +19 km", NavViewModel.variantTitle(a24, 4, more = true, taken = listOf("via A50 + A24 · +28 min, +45 km")))
    // the same road with other differences ("Altri percorsi", a separate request): numbered all the same
    val a24b = app.navmaster.truck.routing.VariantInfo(1, 2, "motorway", "via A24", "+25 min, +30 km", "A1 · A24", emptyList(), 1500.0, 30000.0, true)
    assertEquals("via A24 (2) · più lunga · +25 min, +30 km", NavViewModel.variantTitle(a24b, 4, more = true, taken = listOf("via A24 · +11 min, +19 km")))
    // a road the engine numbered already: on from its number, never "(2) (2)"
    val a24n = app.navmaster.truck.routing.VariantInfo(1, 2, "motorway", "via A24 (2)", "+25 min, +30 km", "A1 · A24", emptyList(), 1500.0, 30000.0, false)
    assertEquals("via A24 (2) · +25 min, +30 km", NavViewModel.variantTitle(a24n, 4, more = false))
    assertEquals("via A24 (3) · +25 min, +30 km", NavViewModel.variantTitle(a24n, 4, more = false, taken = listOf("via A24 (2) · +11 min, +19 km")))
    assertEquals("", NavViewModel.diffWords(10.0, 200.0))
  }
}
