package app.navmaster.truck.nav

import app.navmaster.truck.vehicle.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The route cards before departure: their order (the first card, the other kinds, the toll-free one,
 * the automatic variants, then «Altri percorsi», each by what the card shows), the selection that
 * follows its card (also by identity), the «PIÙ VELOCE», «CONSIGLIATO» and «CONVIENE» badges, the
 * honest title of a lorry's first card and the differences in the subtitles («più lunga» included)
 * worked out from what the cards show.
 */
class RouteCardsTest {
  private val REC = VariantKind.RECOMMENDED
  private val ALT = VariantKind.ALTERNATIVE

  /** A card of [min] minutes and [km] km. */
  private fun card(kind: VariantKind, min: Double, km: Double, more: Boolean = false, critical: Int = 0, title: String = kind.label) =
      CardFacts(kind, min * 60, km * 1000, more, critical, title)

  @Test
  fun firstStaysFirstVariantsByTimeThenKm() {
    val cards = listOf(card(REC, 90.0, 100.0), card(ALT, 95.0, 99.0), card(ALT, 88.0, 102.0), card(ALT, 95.0, 98.0))
    // the first card stays first even when a variant is faster; the variants by minutes, then km
    assertEquals(listOf(0, 2, 3, 1), NavViewModel.cardOrder(cards))
  }

  @Test
  fun groupsKindsThenNoTollThenVariantsThenAltriPercorsi() {
    val cards = listOf(
        card(REC, 90.0, 100.0),                 // 0 first card
        card(ALT, 95.0, 101.0),                 // 1 automatic
        card(VariantKind.NO_TOLL, 99.0, 97.0),  // 2 toll-free (appended before the shortest came)
        card(VariantKind.SHORTEST, 104.0, 92.0),// 3 another kind
        card(ALT, 91.0, 103.0),                 // 4 automatic
        card(ALT, 80.0, 99.0, more = true),     // 5 «Altri percorsi», faster than all: still after the automatic ones
        card(ALT, 91.0, 98.0),                  // 6 automatic, as fast as 4 with fewer km
        card(ALT, 100.0, 90.0, more = true),    // 7 «Altri percorsi»
    )
    assertEquals(listOf(0, 3, 2, 6, 4, 1, 5, 7), NavViewModel.cardOrder(cards))
  }

  @Test
  fun selectedFollowsItsCard() {
    val labels = listOf("Consigliato", "via A1", "via A22", "via A4")
    val cards = listOf(card(REC, 91.0, 100.0), card(ALT, 93.0, 101.0), card(ALT, 91.0, 100.0), card(ALT, 89.0, 100.0))
    val order = NavViewModel.cardOrder(cards)
    // the driver chose «via A1» (place 1): it moves to the end and the selection with it
    val (arranged, sel) = NavViewModel.arrange(labels, 1, order)
    assertEquals(listOf("Consigliato", "via A4", "via A22", "via A1"), arranged)
    assertEquals("via A1", arranged[sel])
    // the first card never moves: «not chosen yet» (selected 0) stays true
    assertEquals(0, NavViewModel.arrange(labels, 0, order).second)
  }

  @Test
  fun lateVariantsSlotIn() {
    // already in order, then the shortest and a faster variant arrive at the end
    val before = listOf(card(REC, 91.0, 100.0), card(ALT, 89.0, 100.0), card(ALT, 91.0, 99.0), card(ALT, 93.0, 101.0))
    assertEquals(listOf(0, 1, 2, 3), NavViewModel.cardOrder(before))
    val after = before + card(VariantKind.SHORTEST, 98.0, 95.0) + card(ALT, 90.0, 100.0)
    val labels = listOf("Consigliato", "A4", "A22", "A1", "Più corto", "nuova")
    val (arranged, sel) = NavViewModel.arrange(labels, 3, NavViewModel.cardOrder(after))
    assertEquals(listOf("Consigliato", "Più corto", "A4", "nuova", "A22", "A1"), arranged)
    assertEquals("A1", arranged[sel])
  }

  @Test
  fun tiesKeepArrivalOrder() {
    // the same minutes and the same km shown: the order they came in, also for seconds and metres apart
    val cards = listOf(card(REC, 90.0, 100.0), card(ALT, 92.2, 100.3), card(ALT, 91.9, 99.8), card(ALT, 92.0, 100.0))
    assertEquals(listOf(0, 1, 2, 3), NavViewModel.cardOrder(cards))
  }

  @Test
  fun kmComparedAsShown() {
    // 100.6 km shows «101 km», 100.4 km «100 km»: the one showing fewer km first
    assertEquals(101_000, NavViewModel.shownMetres(100_600.0))
    assertEquals(100_000, NavViewModel.shownMetres(100_400.0))
    assertEquals(9_500, NavViewModel.shownMetres(9_460.0))
    assertEquals(850, NavViewModel.shownMetres(840.0))
    val cards = listOf(card(REC, 90.0, 99.0), card(ALT, 92.0, 100.6), card(ALT, 92.0, 100.4))
    assertEquals(listOf(0, 2, 1), NavViewModel.cardOrder(cards))
    // 100.4 against 100.45: both «100 km», so the order they came in (not the metres)
    val same = listOf(card(REC, 90.0, 99.0), card(ALT, 92.0, 100.45), card(ALT, 92.0, 100.4))
    assertEquals(listOf(0, 1, 2), NavViewModel.cardOrder(same))
  }

  @Test
  fun fastestBadge() {
    // one card: no badge
    assertEquals(-1, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0))))
    assertEquals(-1, NavViewModel.fastestCard(emptyList()))
    // a variant 2 minutes faster
    assertEquals(1, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0), card(ALT, 88.0, 101.0))))
    // only 20 s faster, the same minutes shown: the first card keeps it
    assertEquals(0, NavViewModel.fastestCard(listOf(CardFacts(REC, 5400.0, 100_000.0), CardFacts(ALT, 5380.0, 99_000.0))))
    // two variants as fast, both faster than the first card: the one with fewer km
    assertEquals(2, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0), card(ALT, 87.0, 103.0), card(ALT, 87.0, 101.0))))
    // the toll-free card only when it is really faster
    assertEquals(0, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0), card(VariantKind.NO_TOLL, 90.2, 95.0))))
    assertEquals(1, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0), card(VariantKind.NO_TOLL, 88.0, 95.0))))
  }

  @Test
  fun noFastestBadgeWhenTheFastestIsCritical() {
    // the truly fastest has a critical point: no badge at all (never «più veloce» on a slower card)
    val cards = listOf(card(REC, 90.0, 100.0), card(ALT, 85.0, 99.0, critical = 1), card(ALT, 88.0, 101.0))
    assertEquals(-1, NavViewModel.fastestCard(cards))
    // every card has one: none either
    val all = listOf(card(REC, 90.0, 100.0, critical = 2), card(ALT, 85.0, 99.0, critical = 1))
    assertEquals(-1, NavViewModel.fastestCard(all))
    // only the first card is safe but slower: it does not get it
    assertEquals(-1, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0), card(ALT, 85.0, 99.0, critical = 1))))
    // the fastest is safe, slower cards critical: the badge stays on it
    assertEquals(1, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0, critical = 1), card(ALT, 85.0, 99.0), card(ALT, 87.0, 98.0, critical = 3))))
    // a tie on the minutes shown with a critical card 0: card 0 is the fastest by the rule, so none
    assertEquals(-1, NavViewModel.fastestCard(listOf(card(REC, 90.0, 100.0, critical = 1), card(ALT, 90.2, 99.0))))
  }

  @Test
  fun noFastestBadgeWhileACarsKindsAreComing() {
    // a car: its «Più veloce» card may still come, so nothing until the kinds are there
    val cards = listOf(card(REC, 90.0, 100.0), card(ALT, 88.0, 101.0))
    assertEquals(-1, NavViewModel.fastestCard(cards, pending = true))
    assertEquals(1, NavViewModel.fastestCard(cards, pending = false))
    // the «Più veloce» card arrived: none, as before
    assertEquals(-1, NavViewModel.fastestCard(cards + card(VariantKind.FASTEST, 87.0, 102.0)))
  }

  @Test
  fun noFastestBadgeWhenATitleSaysIt() {
    // a car whose driver chose «Più veloce»: never a second card claiming it
    val car = listOf(card(VariantKind.FASTEST, 90.0, 100.0), card(ALT, 88.0, 101.0))
    assertEquals(-1, NavViewModel.fastestCard(car))
    // a car's recommended route that is also the fastest ("Consigliato = più veloce")
    val merged = listOf(card(REC, 90.0, 100.0, title = "Consigliato = più veloce"), card(ALT, 92.0, 101.0))
    assertEquals(-1, NavViewModel.fastestCard(merged))
  }

  @Test
  fun honestKindOnlyForHeavy() {
    assertEquals(RouteKind.RECOMMENDED, NavViewModel.honestKind(RouteKind.FASTEST, heavy = true))
    assertEquals(RouteKind.SHORTEST, NavViewModel.honestKind(RouteKind.SHORTEST, heavy = true))
    assertEquals(RouteKind.FASTEST, NavViewModel.honestKind(RouteKind.FASTEST, heavy = false))
    // lorry: «Più corto» chosen → only «Consigliato» after it (no "fastest", the same route twice)
    assertEquals(listOf(RouteKind.RECOMMENDED), NavViewModel.otherKinds(RouteKind.SHORTEST, heavy = true, crowKm = 100.0))
    // lorry: «Consigliato» or «Più veloce» → the shortest only, as before
    assertEquals(listOf(RouteKind.SHORTEST), NavViewModel.otherKinds(RouteKind.RECOMMENDED, heavy = true, crowKm = 100.0))
    assertEquals(listOf(RouteKind.SHORTEST), NavViewModel.otherKinds(RouteKind.FASTEST, heavy = true, crowKm = 100.0))
    // a long trip: no shortest
    assertEquals(emptyList<RouteKind>(), NavViewModel.otherKinds(RouteKind.RECOMMENDED, heavy = true, crowKm = 500.0))
    // a car: the other two kinds, unchanged
    assertEquals(listOf(RouteKind.RECOMMENDED, RouteKind.SHORTEST), NavViewModel.otherKinds(RouteKind.FASTEST, heavy = false, crowKm = 100.0))
    assertEquals(listOf(RouteKind.FASTEST, RouteKind.RECOMMENDED), NavViewModel.otherKinds(RouteKind.SHORTEST, heavy = false, crowKm = 100.0))
  }

  @Test
  fun shownDiffMatchesCards() {
    // 90.4 min / 100.0 km against 88.6 min / 100.3 km: the cards show 1 h 30 and 1 h 29, 100 km and 100 km
    assertEquals("−1 min, +0 km", NavViewModel.shownDiff(88.6 * 60, 100_300.0, 90.4 * 60, 100_000.0))
    // 100.6 km against 100.4 km: «101 km» and «100 km» on the cards
    assertEquals("+0 min, +1 km", NavViewModel.shownDiff(90.0 * 60, 100_600.0, 90.0 * 60, 100_400.0))
    assertEquals("+4 min, −6 km", NavViewModel.shownDiff(94.0 * 60, 94_000.0, 90.0 * 60, 100_000.0))
    // a short trip: one decimal, as the cards
    assertEquals("+1 min, +0,3 km", NavViewModel.shownDiff(12.0 * 60, 9_460.0, 11.0 * 60, 9_200.0))
    assertEquals("+0 min, −0,3 km", NavViewModel.shownDiff(11.0 * 60, 9_200.0, 11.0 * 60, 9_460.0))
    // under 50 m: no difference, never "+0,0 km" or "−0,0 km"
    assertEquals("+0 min, +0 km", NavViewModel.shownDiff(11.0 * 60, 90.0, 11.0 * 60, 50.0))
    assertEquals("+0 min, +0 km", NavViewModel.shownDiff(11.0 * 60, 50.0, 11.0 * 60, 90.0))
    assertEquals(0, NavViewModel.shownExtraM(840.0, 860.0))
    // 50 m shown (900 m against 850 m): rounded to 100 m, one decimal
    assertEquals("+0 min, +0,1 km", NavViewModel.shownDiff(11.0 * 60, 900.0, 11.0 * 60, 850.0))
    assertEquals("+0 min, −0,1 km", NavViewModel.shownDiff(11.0 * 60, 850.0, 11.0 * 60, 900.0))
    // 9.96 km shows «10 km»: against 9 km a whole km
    assertEquals("+0 min, +1 km", NavViewModel.shownDiff(11.0 * 60, 9_960.0, 11.0 * 60, 9_000.0))
    for (a in listOf(30.0, 60.0, 140.0, 480.0, 960.0, 9_940.0)) for (b in listOf(20.0, 75.0, 455.0, 990.0, 9_960.0)) {
      val d = NavViewModel.shownDiff(600.0, a, 600.0, b)
      assertFalse("$a against $b: $d", d.contains("0,0"))
    }
  }

  @Test
  fun longerFromTheShownKm() {
    // the engine says «longer» (+300 m on its own best route)
    val info = app.navmaster.truck.routing.VariantInfo(1, 1, "more", "via A22", "", "", emptyList(), 600.0, 300.0, true)
    // the cards show 2 km more: «più lunga»
    assertEquals("via A22 · più lunga · +10 min, +2 km",
        NavViewModel.variantTitle(info, 1, more = true, names = emptyMap(), diff = "+10 min, +2 km", extraShownM = 2_000))
    // the cards show the same km, or fewer: never «più lunga»
    assertEquals("via A22 · +10 min, +0 km",
        NavViewModel.variantTitle(info, 1, more = true, names = emptyMap(), diff = "+10 min, +0 km", extraShownM = 0))
    assertEquals("via A22 · +10 min, −2 km",
        NavViewModel.variantTitle(info, 1, more = true, names = emptyMap(), diff = "+10 min, −2 km", extraShownM = -2_000))
    // what decides it is the same difference the subtitle prints: 40 m shown are none
    val m = NavViewModel.shownExtraM(90.0, 50.0)
    assertEquals("via A22 · +0 min, +0 km",
        NavViewModel.variantTitle(info, 1, more = true, names = emptyMap(), diff = NavViewModel.shownDiff(600.0, 90.0, 600.0, 50.0), extraShownM = m))
    // an automatic variant never says it
    assertEquals("via A22 · +10 min, +2 km",
        NavViewModel.variantTitle(info, 1, more = false, names = emptyMap(), diff = "+10 min, +2 km", extraShownM = 2_000))
  }

  @Test
  fun firstCardRecommendedOnlyWhenRecommended() {
    assertEquals(true, NavViewModel.firstRecommended(REC))
    assertEquals(false, NavViewModel.firstRecommended(VariantKind.SHORTEST))
    assertEquals(false, NavViewModel.firstRecommended(VariantKind.FASTEST))
    assertEquals(false, NavViewModel.firstRecommended(VariantKind.NO_TOLL))
    assertEquals(false, NavViewModel.firstRecommended(ALT))
  }

  @Test
  fun recommendedBadgeWords() {
    // the toll-free card the toll advice recommends: «CONVIENE»
    assertEquals("CONVIENE", NavViewModel.recommendedBadge(VariantKind.NO_TOLL, "Senza pedaggi"))
    // the first card titled «Consigliato» says it already: no second mark
    assertEquals(null, NavViewModel.recommendedBadge(REC, "Consigliato"))
    assertEquals(null, NavViewModel.recommendedBadge(REC, "Consigliato · senza autostrade"))
    // a recommended card whose title does not say it
    assertEquals("CONSIGLIATO", NavViewModel.recommendedBadge(ALT, "via A1"))
  }

  /** A card on screen: [route] is the engine's route it shows, kept by a copy. */
  private data class Shown(val title: String, val route: Any)

  @Test
  fun selectVariantByIdentityAfterATitleCopy() {
    val a = Shown("Consigliato", Any())
    val b = Shown("via A22", Any())
    val c = Shown("via A1", Any())
    val cards = listOf(a, b, c)
    assertEquals(1, NavViewModel.placeOf(cards, b) { it.route })
    // the driver tapped «via A22»; meanwhile its title changed (a copy, the same route) and the
    // row was put in order again: the tap still selects it
    val b2 = b.copy(title = "via A22 = più corto")
    val later = listOf(a, c, b2)
    assertEquals(2, NavViewModel.placeOf(later, b) { it.route })
    // a card with the same title but another route is not it
    val other = Shown("via A22", Any())
    assertEquals(-1, NavViewModel.placeOf(listOf(a, c, other), b) { it.route })
    // the same object wins over another card with its route
    val twin = b.copy()
    assertEquals(1, NavViewModel.placeOf(listOf(twin, b), b) { it.route })
  }

  @Test
  fun altriPercorsiBatchWithTheSelectionOnAnAutomaticVariant() {
    val before = listOf(card(REC, 90.0, 100.0), card(VariantKind.SHORTEST, 100.0, 92.0), card(ALT, 91.0, 101.0), card(ALT, 95.0, 99.0))
    assertEquals(listOf(0, 1, 2, 3), NavViewModel.cardOrder(before))
    // the driver chose the automatic «via A22» (place 3), then «Altri percorsi» brings two routes,
    // one faster than every card
    val after = before + card(ALT, 93.0, 104.0, more = true) + card(ALT, 85.0, 103.0, more = true)
    val labels = listOf("Consigliato", "Più corto", "via A1", "via A22", "altra 1", "altra 2")
    val order = NavViewModel.cardOrder(after)
    val (arranged, sel) = NavViewModel.arrange(labels, 3, order)
    // after the automatic variants, by their minutes; the selection still on «via A22»
    assertEquals(listOf("Consigliato", "Più corto", "via A1", "via A22", "altra 2", "altra 1"), arranged)
    assertEquals("via A22", arranged[sel])
    // the badge on the truly fastest card, now the last-but-one
    val (facts, _) = NavViewModel.arrange(after, 3, order)
    assertEquals(4, NavViewModel.fastestCard(facts))
  }

  @Test
  fun subtitleWithTheShownDiff() {
    val info = app.navmaster.truck.routing.VariantInfo(1, 1, "crossing", "via A4 + D3", "−2 min, +0 km", "", emptyList(), -100.0, 300.0, false,
        countries = listOf("italy", "austria", "slovakia"), newCountries = listOf("slovakia"))
    assertEquals("via A4 + D3, per la Slovacchia · −1 min, +0 km",
        NavViewModel.variantTitle(info, 1, more = false, names = mapOf("slovakia" to "Slovacchia"), diff = "−1 min, +0 km"))
    // without it, the engine's text as before
    assertEquals("via A4 + D3, per la Slovacchia · −2 min, +0 km",
        NavViewModel.variantTitle(info, 1, more = false, names = mapOf("slovakia" to "Slovacchia")))
    // a variant without the engine's facts keeps its bare title
    assertEquals("Alternativa 3", NavViewModel.variantTitle(null, 3, more = true))
  }
}
