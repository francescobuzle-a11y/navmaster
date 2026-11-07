package app.navmaster.truck.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the free search: what a driver types against the names of the index. */
class MatchingTest {
  private fun t(s: String) = TextNorm.tokens(s)

  @Test
  fun legalFormsAndFillersAreLeftOut() {
    assertEquals(listOf("corneliani"), Matching.clean(t("Corneliani S.r.l.")))
    assertEquals(listOf("corneliani"), Matching.clean(t("il Corneliani srl")))
    assertEquals(listOf("latteria", "soresina"), Matching.clean(t("Latteria Soresina S.p.A.")))
    // "F.lli" becomes "f lli": the "f" is a filler, "lli" is a synonym of "fratelli"
    assertEquals(listOf("lli", "rossi"), Matching.clean(t("F.lli Rossi & C. snc")))
    // nothing but fillers: the words themselves, so that "La Spezia" still searches
    assertEquals(listOf("la"), Matching.clean(t("la")))
    assertEquals(listOf("castello", "san", "giorgio"), Matching.clean(t("Castello di San Giorgio")))
  }

  @Test
  fun synonymsOfACategoryWord() {
    assertTrue("pneumatici" in Matching.synonyms("gommista"))
    assertTrue("gommista" in Matching.synonyms("pneumatici"))
    assertTrue("logistica" in Matching.synonyms("autotrasporti"))
    assertEquals(listOf("corneliani"), Matching.synonyms("corneliani"))
    val ex = Matching.expansions(listOf("gommista", "brescia"))
    assertEquals(listOf("gommista", "brescia"), ex[0])
    // two words as one, as the name is often written
    assertEquals(listOf("gommistabrescia"), ex[1])
    assertTrue(ex.any { it == listOf("pneumatici", "brescia") })
    assertTrue(ex.size <= 12)
    // the most telling synonyms within the cap: "logistica" reaches "logistics"
    assertTrue(Matching.expansions(listOf("logistica")).any { it == listOf("logistics") })
    assertEquals(listOf("fieramilano", "rho"), Matching.expansions(listOf("fiera", "milano", "rho"))[1])
  }

  @Test
  fun editDistance() {
    assertEquals(0, Matching.editDistance("mantova", "mantova"))
    assertEquals(1, Matching.editDistance("mantva", "mantova"))
    assertEquals(1, Matching.editDistance("cornelliani", "corneliani"))
    assertEquals(1, Matching.editDistance("mnatova", "mantova")) // a swap
    assertEquals(2, Matching.editDistance("eselunge", "esselunga"))
    // over the cap: max + 1, not the true distance
    assertEquals(2, Matching.editDistance("eselunge", "esselunga", 1))
    assertTrue(Matching.editDistance("brescia", "cremona") > 2)
  }

  @Test
  fun correctionOfAMisspeltWord() {
    val terms = listOf("corneliani" to 3, "cornelio" to 9, "corneliano" to 2, "cornell" to 1)
    assertEquals("corneliani", Matching.correct("cornelliani", terms))
    assertEquals("mantova", Matching.correct("mantva", listOf("mantova" to 500, "mantua" to 3, "manta" to 20)))
    // the commonest among the equally near
    assertEquals("mantova", Matching.correct("mantoa", listOf("mantua" to 3, "mantova" to 500)))
    // short words are never corrected (too many neighbours), far words neither
    assertNull(Matching.correct("roma", listOf("rome" to 10)))
    assertNull(Matching.correct("brescia", listOf("cremona" to 10)))
    assertEquals(0, Matching.allowedErrors("roma"))
    assertEquals(1, Matching.allowedErrors("mantova"))
    assertEquals(2, Matching.allowedErrors("corneliani"))
  }

  @Test
  fun nameScoreOrdersTheHits() {
    val q = Matching.clean(t("corneliani"))
    assertEquals(100.0, Matching.nameScore("Corneliani S.p.A.", null, q), 0.0)
    assertEquals(100.0, Matching.nameScore("Corneliani", null, q), 0.0)
    assertEquals(80.0, Matching.nameScore("Corneliani Outlet", null, q), 0.0)
    // another name of the place (brand, official name) that is what was typed: as the name
    assertEquals(100.0, Matching.nameScore("Collection", "Corneliani", q), 0.0)
    assertEquals(70.0, Matching.nameScore("Collection", "Corneliani Outlet", q), 0.0)
    assertEquals(60.0, Matching.nameScore("Outlet Corneliani", null, q), 0.0)
    assertEquals(10.0, Matching.nameScore("Bar Centrale", null, q), 0.0)
    // two words: both in the name, start of the name first
    val q2 = Matching.clean(t("latteria soresina spa"))
    assertEquals(100.0, Matching.nameScore("Latteria Soresina", null, q2), 0.0)
    assertEquals(80.0, Matching.nameScore("Latteria Soresina Spaccio", null, q2), 0.0)
    assertEquals(60.0, Matching.nameScore("Spaccio Latteria Soresina", null, q2), 0.0)
    assertEquals(30.0, Matching.nameScore("Latteria Sociale", null, q2), 0.0)
    // the last word typed may be half a word
    assertEquals(80.0, Matching.nameScore("Latteria Soresina", null, listOf("latteria", "sores")), 0.0)
  }

  @Test
  fun categoryHitsAndNoHits() {
    // the name has none of the words: the kind answers ("gommista"), with its synonyms ("pneus")
    assertEquals(95.0, Matching.score("Maestrelli Gomme", null, "gommista", listOf("gommista")), 0.0)
    assertEquals(50.0, Matching.score("Team Service", null, "gommista", listOf("pneus")), 0.0)
    assertEquals(80.0, Matching.score("TCF Rosignoli Logistics", null, "zona industriale", listOf("logistica")), 0.0)
    assertEquals(95.0, Matching.score("Mantova", null, "stazione", listOf("stazione")), 0.0)
    // a restaurant named La Stazione comes after the station
    assertEquals(70.0, Matching.score("La Stazione", null, "ristorante", listOf("stazione")), 0.0)
    // a word in the name and the kind as typed, together: every word explained (58); a word
    // that is neither (the town, taken out before the score) leaves nothing
    assertEquals(58.0, Matching.score("Rossi Gomme", null, "gommista", listOf("gommista", "rossi")), 0.0)
    assertEquals(0.0, Matching.score("Rossi Gomme", null, "gommista", listOf("gommista", "brescia")), 0.0)
    assertEquals(95.0, Matching.score("Rossi Gomme", null, "officina", listOf("officina")), 0.0)
    // a synonym as a whole word of the name, for a category query: as a name hit
    assertEquals(80.0, Matching.score("Cremona Fiere", null, "exhibition centre", listOf("fiera")), 0.0)
    // the words typed in the name or in the kind, together
    assertEquals(58.0, Matching.score("Cremona Po", null, "centro commerciale", listOf("cremona", "po", "centro", "commerciale")), 0.0)
    assertEquals(30.0, Matching.score("Cremona Centro", null, "ufficio postale", listOf("cremona", "po", "centro", "commerciale")), 0.0)
    // a synonym answers only as a whole word: "stationery" is no station
    assertEquals(80.0, Matching.score("EV Charging Station", null, "colonnina di ricarica", listOf("stazione")), 0.0)
    assertEquals(0.0, Matching.score("Tabaccheria Cartoleria", null, "stationery", listOf("stazione")), 0.0)
    // a department store is no warehouse, a name with nothing of what was typed is no hit
    assertEquals(0.0, Matching.score("A&O", null, "grande magazzino", listOf("interporto")), 0.0)
    assertEquals(0.0, Matching.score("Impianto idroelettrico", null, "centrale", listOf("beretta")), 0.0)
    // the name answers: the name's score
    assertEquals(100.0, Matching.score("Gommista Altoli", null, "gommista", listOf("gommista", "altoli")), 0.0)
    assertEquals(60.0, Matching.score("Fabbrica d'Armi Pietro Beretta", null, "capannone", listOf("beretta")), 0.0)
  }

  @Test
  fun oneWordLess() {
    assertEquals(listOf(listOf("ies", "mantova")), Matching.oneWordLess(listOf("ies", "raffineria", "mantova")))
    assertEquals(listOf(listOf("officina"), listOf("camion")), Matching.oneWordLess(listOf("officina", "camion")))
    assertEquals(listOf(listOf("negroni")), Matching.oneWordLess(listOf("negroni", "salumi")))
    assertTrue(Matching.oneWordLess(listOf("corneliani")).isEmpty())
  }

  @Test
  fun distancePenalty() {
    assertEquals(0.0, Matching.distancePenalty(0.0), 0.0)
    assertEquals(8.0, Matching.distancePenalty(1.0), 0.01)
    assertEquals(32.0, Matching.distancePenalty(15.0), 0.1)
    // the exact name 100 km away still beats a partial match next door (100 - 53 > 45)
    assertTrue(100.0 - Matching.distancePenalty(100.0) > 45.0)
  }

  @Test
  fun townAtTheEndOfTheQuery() {
    val towns = listOf(
        Matching.Town("Mantova", "city", 45.16, 10.79, 48000),
        Matching.Town("Canneto sull'Oglio", "village", 45.15, 10.38, 4400),
        Matching.Town("Cremona", "city", 45.13, 10.02, 72000),
        Matching.Town("Mantovana", "hamlet", 44.8, 8.6, 0),
    )
    val lookup: (String) -> List<Matching.Town> = { q -> towns.filter { TextNorm.norm(it.name).startsWith(q) } }
    val canneto = 45.152 to 10.382
    val none = emptyList<Matching.Town>()
    assertEquals(listOf("esselunga") to listOf(towns[0]), Matching.splitTown(t("esselunga mantova"), canneto, lookup))
    assertEquals(listOf("conad") to listOf(towns[1]), Matching.splitTown(t("conad canneto sull'oglio"), canneto, lookup))
    assertEquals(listOf("conad") to listOf(towns[1]), Matching.splitTown(t("conad canneto"), canneto, lookup))
    assertEquals(listOf("scania") to listOf(towns[2]), Matching.splitTown(t("scania cremona"), canneto, lookup))
    // not a town: nothing taken out
    assertEquals(listOf("arvedi", "tubi") to none, Matching.splitTown(t("arvedi tubi"), canneto, lookup))
    // the whole query is never a town (the address book answers that)
    assertEquals(listOf("mantova") to none, Matching.splitTown(t("mantova"), canneto, lookup))
    // "mant" is the start of Mantova but too short to mean it
    assertEquals(listOf("esselunga", "man") to none, Matching.splitTown(t("esselunga man"), canneto, lookup))
    // half a name only near the driver (from Rome, "canneto" is not Canneto sull'Oglio); a whole city anywhere
    val rome = 41.9 to 12.5
    assertEquals(listOf("conad", "canneto") to none, Matching.splitTown(t("conad canneto"), rome, lookup))
    assertEquals(listOf("esselunga") to listOf(towns[0]), Matching.splitTown(t("esselunga mantova"), rome, lookup))
    // a hamlet with the exact name only near the driver: "Grana Padano consorzio" is no search in Consorzio Velia
    val velia = listOf(Matching.Town("Consorzio Velia", "hamlet", 40.2, 15.1, 0), Matching.Town("Consorzio", "hamlet", 40.3, 15.2, 0))
    assertEquals(listOf("grana", "padano", "consorzio") to none, Matching.splitTown(t("grana padano consorzio"), canneto) { q -> velia.filter { TextNorm.norm(it.name).startsWith(q) } })
    // two towns of the same name: both, the big one first
    val gardone = listOf(Matching.Town("Gardone Riviera", "town", 45.62, 10.56, 2600), Matching.Town("Gardone Val Trompia", "town", 45.69, 10.18, 11000))
    assertEquals(listOf("beretta") to listOf(gardone[1], gardone[0]), Matching.splitTown(t("beretta gardone"), canneto) { q -> gardone.filter { TextNorm.norm(it.name).startsWith(q) } })
  }

  @Test
  fun samePlaceFromTwoSources() {
    assertTrue(Matching.samePlace("Esselunga", 45.1563, 10.7827, "Esselunga Mantova", 45.1565, 10.7830))
    // a shop and the address of its door are two results (the sign and the house number)
    assertTrue(!Matching.samePlace("Lidl", 45.1404, 10.0432, "Via Roma 3", 45.14041, 10.04321))
    assertTrue(Matching.samePlace("Lidl", 45.1404, 10.0432, "Supermercato", 45.14041, 10.04321))
    assertTrue(!Matching.samePlace("Lidl", 45.1404, 10.0432, "Lidl", 45.1490, 10.0029))
    assertTrue(!Matching.samePlace("Bar Sport", 45.1404, 10.0432, "Farmacia", 45.1409, 10.0432))
    // a company and the address of its door, both kept; two addresses of the same door, once
    assertTrue(!Matching.samePlace("Corneliani S.p.A.", 45.14715, 10.78215, "Via Mario Panizza 5", 45.14719, 10.78213))
    assertTrue(Matching.samePlace("Via Mario Panizza 5", 45.14718, 10.78212, "Via Mario Panizza 5", 45.14719, 10.78213))
    assertTrue(Matching.addressLike("Strada Cipata"))
    assertTrue(!Matching.addressLike("Esselunga"))
  }

  @Test
  fun townRadius() {
    assertTrue(Matching.townRadius("city") > Matching.townRadius("village"))
    assertTrue(Matching.townRadius("hamlet") >= 2000.0)
  }
}
