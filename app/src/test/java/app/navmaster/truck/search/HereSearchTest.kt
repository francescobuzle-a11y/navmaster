package app.navmaster.truck.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answers of HERE "discover" as results of the search, and the merge with the other sources. */
class HereSearchTest {
  private val body = """
    {"items":[
      {"title":"Corneliani S.p.A.","id":"here:pds:place:380sr2yk-1","resultType":"place",
       "address":{"label":"Corneliani S.p.A., Via Mario Panizza 5, 46100 Mantova MN, Italia","countryCode":"ITA","countryName":"Italia",
                  "state":"Lombardia","county":"Mantova","city":"Mantova","street":"Via Mario Panizza","postalCode":"46100","houseNumber":"5"},
       "position":{"lat":45.14715,"lng":10.78215},"access":[{"lat":45.14720,"lng":10.78210}],"distance":31500,
       "categories":[{"id":"600-6100-0062","name":"Abbigliamento","primary":true},{"id":"800-8000-0000","name":"Industria"}],
       "contacts":[{"phone":[{"value":"+390376...."}],"www":[{"value":"https://www.corneliani.com"}]}]},
      {"title":"Via Mario Panizza 5","id":"here:af:streetsection:x","resultType":"houseNumber","houseNumberType":"PA",
       "address":{"label":"Via Mario Panizza 5, 46100 Mantova MN, Italia","city":"Mantova","street":"Via Mario Panizza","postalCode":"46100","houseNumber":"5","state":"Lombardia"},
       "position":{"lat":45.14718,"lng":10.78212},"distance":31510},
      {"title":"Mantova","id":"here:cm:namedplace:1","resultType":"locality","localityType":"city",
       "address":{"label":"Mantova, Lombardia, Italia","city":"Mantova","county":"Mantova","state":"Lombardia","postalCode":"46100"},
       "position":{"lat":45.15694,"lng":10.79153},"distance":33000},
      {"title":"senza posizione","id":"x","resultType":"place"}
    ]}
  """

  @Test
  fun parseDiscover() {
    val out = HereSearch.parse(body)
    assertEquals(3, out.size)
    assertEquals("Corneliani S.p.A.", out[0].title)
    assertEquals("Abbigliamento · Via Mario Panizza 5 · Mantova", out[0].detail)
    assertEquals(45.14715, out[0].coordinate.lat, 1e-6)
    assertEquals(10.78215, out[0].coordinate.lng, 1e-6)
    assertEquals(false, out[0].offline)
    assertEquals("📍", out[1].icon)
    assertEquals("46100 · Mantova · Lombardia", out[1].detail)
    assertEquals("🏙", out[2].icon)
    assertTrue(HereSearch.parse("not json").isEmpty())
    assertTrue(HereSearch.parse("""{"items":[]}""").isEmpty())
  }

  @Test
  fun iconOfACategory() {
    assertEquals("🚚", HereSearch.iconOf("Trasporti e logistica"))
    assertEquals("🔧", HereSearch.iconOf("Gommista"))
    assertEquals("🏢", HereSearch.iconOf("Abbigliamento"))
    assertEquals("🔎", HereSearch.iconOf(""))
  }

  @Test
  fun mergeWithoutDoubles() {
    val here = HereSearch.parse(body)
    val google = listOf(Found("Via Mario Panizza 5", "46100 · Mantova", uniffi.ferrostar.GeographicCoordinate(45.14719, 10.78213), "🔎", offline = false))
    val photon = listOf(
        Found("Corneliani", "46100 · Mantova", uniffi.ferrostar.GeographicCoordinate(45.14716, 10.78216), "🌐", offline = false),
        Found("Bar Corneliani", "Milano", uniffi.ferrostar.GeographicCoordinate(45.46, 9.19), "🌐", offline = false))
    val merged = Geocoder.merge(listOf(google, here, photon), "corneliani srl")
    // the address once (Google's version), the company once (HERE's), the company by its sign first
    assertEquals(listOf("Corneliani S.p.A.", "Via Mario Panizza 5", "Mantova", "Bar Corneliani"), merged.map { it.title })
    assertEquals("🔎", merged[1].icon)
  }
}
