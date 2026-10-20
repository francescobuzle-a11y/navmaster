package app.navmaster.truck.search

import android.util.Log
import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import uniffi.ferrostar.GeographicCoordinate

/**
 * Every named place of the installed countries (luoghi.sqlite, built by tools/places/build_places.py
 * from OpenStreetMap and Overture Maps: companies, warehouses, shops, workshops, restaurants,
 * hotels, stations, schools, churches, parks ...), searched by name on the tablet, without the
 * network, the way Google Maps answers "Cram Autotrasporti" or "Malpensa Logistica Europa".
 *
 * The search: every word of the query as a prefix (FTS), on the name, the other names, the town
 * and the kind in Italian ("gommista brescia" finds the tyre shops of Brescia); the hits are
 * then ranked by how well the NAME matches (the whole name, its start, all the words, or only
 * the town/kind) and by the distance from where the driver is, the nearer first.
 */
class PlaceIndex(regions: RegionManager) {
  private val dbs = RegionDbs(regions) { it.places }

  class Place(
      val region: String,
      val id: Long,
      val name: String,
      val kind: String,
      val label: String?,
      val lat: Double,
      val lon: Double,
      val city: String?,
      val street: String?,
      val number: String?,
      val phone: String?,
      val website: String?,
      val src: String,
  )

  /** Something on the tablet answers by name (at least one luoghi.sqlite). */
  fun available(): Boolean = dbs.all().isNotEmpty()

  fun search(query: String, near: GeographicCoordinate?, limit: Int = 12): List<Found> {
    val toks = TextNorm.tokens(query)
    if (toks.isEmpty()) return emptyList()
    val fts = toks.joinToString(" ") { "$it*" }
    val q = toks.joinToString(" ")
    val hits = ArrayList<Pair<Double, Place>>()
    for ((region, db) in dbs.all()) {
      runCatching {
        db.rawQuery(
            "SELECT p.id, p.name, p.kind, p.label, p.lat, p.lon, p.city, p.street, p.housenumber, p.phone, p.website, p.src, p.alt " +
                "FROM place_fts f JOIN place p ON p.id = f.docid WHERE f.norm MATCH ? LIMIT 400", arrayOf(fts)).use { c ->
          while (c.moveToNext()) {
            val p = Place(region.id, c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5),
                c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11))
            hits += score(p, q, toks, c.getString(12), near) to p
          }
        }
      }.onFailure { Log.w(TAG, "places ${region.id}: $it") }
    }
    hits.sortByDescending { it.first }
    val out = ArrayList<Found>()
    for ((_, p) in hits) {
      // the same place from both sources, or twice in the map: once
      if (out.any { it.title.equals(p.name, true) && Geo.dist(it.coordinate.lat, it.coordinate.lng, p.lat, p.lon) < 150 }) continue
      out += found(p, near)
      if (out.size >= limit) break
    }
    return out
  }

  /** The bigger the better: the name itself first, then the nearest. */
  private fun score(p: Place, q: String, toks: List<String>, alt: String?, near: GeographicCoordinate?): Double {
    val n = TextNorm.norm(p.name)
    val a = TextNorm.norm(alt)
    var s = when {
      n == q || a == q -> 100.0
      n.startsWith("$q ") || n.startsWith(q) -> 80.0
      a.isNotEmpty() && (a.startsWith("$q ") || a.startsWith(q)) -> 70.0
      toks.all { t -> n.split(' ').any { it.startsWith(t) } } -> 60.0
      toks.all { t -> n.contains(t) || a.contains(t) } -> 45.0
      // only some words in the name (the others match the town or the kind)
      toks.any { t -> n.split(' ').any { it.startsWith(t) } } -> 30.0
      else -> 10.0
    }
    if (near != null) {
      val km = Geo.dist(near.lat, near.lng, p.lat, p.lon) / 1000.0
      // 0 at the door, about -8 at 1 km, -16 at 3 km, -32 at 15 km, -56 at 100 km: a far Bar
      // Centrale never beats the one in town, but a company with the exact name is found anywhere
      s -= 8.0 * kotlin.math.log2(1.0 + km)
    }
    if (p.src == "osm") s += 1.0
    return s
  }

  private fun found(p: Place, near: GeographicCoordinate?): Found {
    val where = listOfNotNull(p.label?.takeIf { it.isNotBlank() }, listOfNotNull(p.street, p.number).joinToString(" ").takeIf { it.isNotBlank() },
        p.city).joinToString(" · ")
    return Found(p.name, where, GeographicCoordinate(p.lat, p.lon), iconOf(p.kind), near?.let { Geo.dist(it.lat, it.lng, p.lat, p.lon) })
  }

  companion object {
    private const val TAG = "NavMasterSearch"

    fun iconOf(kind: String): String {
      val (k, v) = kind.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
      return when {
        k == "amenity" && v in setOf("restaurant", "fast_food", "food_court") -> "🍽"
        k == "amenity" && v in setOf("cafe", "bar", "pub", "ice_cream") -> "☕"
        k == "amenity" && v == "fuel" -> "⛽"
        k == "amenity" && v == "parking" -> "🅿"
        k == "amenity" && v in setOf("hospital", "clinic", "doctors", "pharmacy", "dentist") -> "🏥"
        k == "amenity" && v in setOf("school", "kindergarten", "college", "university") -> "🏫"
        k == "amenity" && v == "place_of_worship" -> "⛪"
        k == "amenity" && v in setOf("bank", "atm") -> "🏦"
        k == "amenity" && v in setOf("police", "townhall", "post_office", "courthouse") -> "🏛"
        k == "amenity" && v in setOf("car_wash", "car_rental") -> "🚗"
        k == "shop" && v in setOf("car_repair", "truck_repair", "tyres", "car_parts") -> "🔧"
        k == "craft" && v in setOf("truck_repair", "agricultural_engines", "metal_construction") -> "🔧"
        k == "shop" && v in setOf("supermarket", "convenience", "mall", "department_store") -> "🛒"
        k == "shop" -> "🏪"
        k == "office" && v in setOf("logistics", "transport", "courier", "moving_company") -> "🚚"
        k == "office" -> "🏢"
        k == "industrial" || (k == "landuse" && v in setOf("industrial", "commercial", "retail")) -> "🏭"
        k == "building" && v in setOf("industrial", "warehouse", "factory", "hangar") -> "🏭"
        k == "building" && v in setOf("commercial", "office", "retail") -> "🏢"
        k == "man_made" && v == "works" -> "🏭"
        k == "tourism" && v in setOf("hotel", "motel", "guest_house", "hostel", "apartment", "chalet") -> "🏨"
        k == "tourism" && v in setOf("camp_site", "caravan_site") -> "🏕"
        k == "tourism" -> "🎫"
        k == "leisure" -> "🌳"
        k == "aeroway" -> "✈"
        k == "railway" || k == "public_transport" -> "🚉"
        k == "natural" && v == "peak" -> "⛰"
        k == "natural" -> "🌲"
        k == "historic" -> "🏰"
        k == "healthcare" -> "🏥"
        k == "place" -> "🏘"
        k == "overture" && (v.contains("logistic") || v.contains("truck") || v.contains("freight") || v.contains("shipping")) -> "🚚"
        k == "overture" && (v.contains("restaurant") || v.contains("pizza")) -> "🍽"
        k == "overture" && (v.contains("cafe") || v.contains("bar") || v.contains("coffee")) -> "☕"
        k == "overture" && (v.contains("hotel") || v.contains("motel")) -> "🏨"
        k == "overture" && (v.contains("repair") || v.contains("tire")) -> "🔧"
        k == "overture" && (v.contains("store") || v.contains("shop") || v.contains("market")) -> "🏪"
        k == "overture" && (v.contains("industr") || v.contains("manufactur") || v.contains("factory") || v.contains("warehouse")) -> "🏭"
        k == "overture" -> "🏢"
        else -> "📍"
      }
    }
  }
}
