package app.navmaster.truck.nav

import app.navmaster.truck.routing.MovedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.ferrostar.GeographicCoordinate

/** The words for a stop moved onto the nearest road the lorry may drive, and for one with no road at all. */
class MovedStopNoteTest {
  private val stops = listOf(
      Stop(GeographicCoordinate(45.5416, 10.2118), "Brescia"),
      Stop(GeographicCoordinate(45.4, 10.9), "Verona"),
      Stop(GeographicCoordinate(44.49, 11.34), "Bologna"),
  )

  @Test
  fun noteNamesTheStopAndTheDistance() {
    assertEquals("Brescia: il camion non può entrare, tappa spostata di 1,3 km sulla strada percorribile più vicina",
        NavViewModel.movedNote(listOf(MovedPoint(1, 1311.0, 2000.0)), stops))
    assertEquals("Bologna: il camion non può entrare, destinazione spostata di 650 m sulla strada percorribile più vicina",
        NavViewModel.movedNote(listOf(MovedPoint(3, 652.0, 2000.0)), stops))
    assertEquals("Partenza: il mezzo non può passare di lì, partenza spostata di 2,4 km sulla strada percorribile più vicina",
        NavViewModel.movedNote(listOf(MovedPoint(0, 2400.0, 5000.0)), stops, "il mezzo"))
    // two points: one line each
    val two = NavViewModel.movedNote(listOf(MovedPoint(1, 1311.0, 2000.0), MovedPoint(2, 800.0, 2000.0)), stops)!!
    assertEquals(2, two.lines().size)
    assertEquals("Verona: il camion non può entrare, tappa spostata di 800 m sulla strada percorribile più vicina", two.lines()[1])
    assertNull(NavViewModel.movedNote(emptyList(), stops))
  }

  @Test
  fun unreachablePointByName() {
    assertEquals("Nessuna strada per il mezzo entro 5,0 km da «Brescia»: toglila dal viaggio o spostala",
        NavViewModel.unreachableWords(1, 4, 5000.0, stops))
    assertEquals("Nessuna strada per il mezzo entro 5,0 km da «Bologna»: toglila dal viaggio o spostala",
        NavViewModel.unreachableWords(3, 4, 5000.0, stops))
    assertEquals("Nessuna strada per il mezzo entro 5,0 km dalla partenza: spostala",
        NavViewModel.unreachableWords(0, 4, 5000.0, stops))
    // a request that is not the plan's (a recalculation while driving): the point by its place
    assertEquals("Nessuna strada per il mezzo entro 2,0 km dalla tappa 1: toglila dal viaggio o spostala",
        NavViewModel.unreachableWords(1, 3, 2000.0, stops))
    assertEquals("Nessuna strada per il mezzo entro 2,0 km dalla destinazione: toglila dal viaggio o spostala",
        NavViewModel.unreachableWords(2, 3, 2000.0, stops))
    assertEquals("la destinazione", NavViewModel.pointName(2, 3, stops))
  }
}
