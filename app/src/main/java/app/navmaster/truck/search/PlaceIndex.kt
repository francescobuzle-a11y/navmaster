package app.navmaster.truck.search

import android.database.sqlite.SQLiteDatabase
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
 * The search (the rules are in Matching, tested on the computer):
 *  - the words typed without the legal forms and the fillers ("Corneliani Srl", "il Corneliani");
 *  - a word that is in no name is corrected to the nearest word of the index ("Cornelliani",
 *    "mantva": see [corrected]);
 *  - every word as a prefix (FTS) on the name, the other names, the town and the kind in Italian
 *    ("gommista brescia" finds the tyre shops of Brescia), a category word with its synonyms;
 *  - a town closing the query ("Esselunga Mantova", "Scania Cremona") is taken out and the
 *    places are looked for around it (the town from the address book), since most places of
 *    OpenStreetMap carry no town of their own;
 *  - the hits are ranked by how well the NAME matches (the whole name, its start, all the words,
 *    or only the town/kind) and by the distance from where the driver is, the nearer first.
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
      val alt: String?,
  )

  /** Something on the tablet answers by name (at least one luoghi.sqlite). */
  fun available(): Boolean = dbs.all().isNotEmpty()

  fun search(query: String, near: GeographicCoordinate?, addresses: AddressIndex? = null, limit: Int = 12): List<Found> {
    val toks = Matching.clean(TextNorm.tokens(query))
    if (toks.isEmpty()) return emptyList()
    // (region, id) -> best score, the match of the name, the place
    val best = HashMap<String, Triple<Double, Double, Place>>()
    fun keep(p: Place, match: Double, s: Double) {
      if (match <= 0.0) return
      val k = "${p.region}:${p.id}"
      val old = best[k]
      if (old == null || old.first < s) best[k] = Triple(s, match, p)
    }
    // the match of a place against the words typed (the FTS may have found it through a synonym
    // or two words joined: the letters as typed decide, a joined form counts as typed)
    fun match(p: Place, typed: List<String>, alt: List<String>): Double {
      val m = Matching.score(p.name, p.alt, p.label, typed)
      return if (alt != typed && alt.joinToString("") == typed.joinToString("")) maxOf(m, Matching.score(p.name, p.alt, p.label, alt)) else m
    }
    // "<name> <town>": the town closing the query, taken out
    val (rest, towns) = if (addresses != null) Matching.splitTown(toks, near?.let { it.lat to it.lng }, addresses::townsStartingWith) else toks to emptyList()
    val townWords = toks.drop(rest.size)
    fun run(typed: List<String>, rest: List<String>) {
      // the words as typed (a category word with its synonyms), near the driver
      for (alt in Matching.expansions(typed)) {
        for (p in fts(alt, 2000)) {
          val m = match(p, typed, alt)
          keep(p, m, m - (near?.let { Matching.distancePenalty(Geo.dist(it.lat, it.lng, p.lat, p.lon) / 1000.0) } ?: 0.0) + if (p.src == "osm") 1.0 else 0.0)
        }
      }
      // the places around the town (the box of the town in the query: "officina" has tens of
      // thousands of hits, a plain limit would stop before the town's own)
      if (towns.isNotEmpty() && rest.isNotEmpty()) {
        for (alt in Matching.expansions(rest, 10)) {
          for (town in towns) {
            val radius = Matching.townRadius(town.kind) * 1.5
            // a town of that name far from the driver (one of the many San Giorgio) counts less
            val far = near?.let { 0.6 * Matching.distancePenalty(Geo.dist(it.lat, it.lng, town.lat, town.lon) / 1000.0) } ?: 0.0
            for (p in fts(alt, 3000, Geo.box(town.lat, town.lon, radius))) {
              val d = Geo.dist(town.lat, town.lon, p.lat, p.lon)
              if (d > radius) continue
              // ranked by the name and by the distance from the town, not from the driver
              val m = match(p, rest, alt)
              keep(p, m, m - Matching.distancePenalty(d / 1000.0) - far + if (p.src == "osm") 1.0 else 0.0)
            }
          }
        }
      }
    }
    run(toks, rest)
    // nothing answers every word: one word less ("ies raffineria mantova" -> "ies mantova",
    // "officina camion mantova" -> "officina mantova"), the town kept
    for (less in Matching.oneWordLess(rest)) {
      if (best.values.any { it.second >= 50.0 }) break
      run(less + townWords, less)
    }
    val hits = best.values.sortedByDescending { it.first }
    val out = ArrayList<Found>()
    for ((_, m, p) in hits) {
      // the same place from both sources, or twice in the map: once
      if (out.any { Matching.samePlace(it.title, it.coordinate.lat, it.coordinate.lng, p.name, p.lat, p.lon) }) continue
      out += found(p, near, m)
      if (out.size >= limit) break
    }
    return out
  }

  /** The places whose search form has every word as a prefix, in [box] (south, west, north, east) when given. */
  private fun fts(toks: List<String>, limit: Int, box: DoubleArray? = null): List<Place> {
    val fts = toks.joinToString(" ") { "$it*" }
    val out = ArrayList<Place>()
    val where = if (box == null) "" else " AND p.lat BETWEEN ${box[0]} AND ${box[2]} AND p.lon BETWEEN ${box[1]} AND ${box[3]}"
    for ((region, db) in dbs.all()) {
      runCatching {
        db.rawQuery(
            "SELECT p.id, p.name, p.kind, p.label, p.lat, p.lon, p.city, p.street, p.housenumber, p.phone, p.website, p.src, p.alt " +
                "FROM place_fts f JOIN place p ON p.id = f.docid WHERE f.norm MATCH ?$where LIMIT $limit", arrayOf(fts)).use { c ->
          while (c.moveToNext()) {
            out += Place(region.id, c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5),
                c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12))
          }
        }
      }.onFailure { Log.w(TAG, "places ${region.id}: $it") }
    }
    return out
  }

  private fun found(p: Place, near: GeographicCoordinate?, match: Double): Found {
    val where = listOfNotNull(p.label?.takeIf { it.isNotBlank() }, listOfNotNull(p.street, p.number).joinToString(" ").takeIf { it.isNotBlank() },
        p.city).joinToString(" · ")
    return Found(p.name, where, GeographicCoordinate(p.lat, p.lon), iconOf(p.kind), near?.let { Geo.dist(it.lat, it.lng, p.lat, p.lon) }, match = match)
  }

  // ---- typos -------------------------------------------------------------------------------

  /**
   * The query with every word that is in no name of the tablet (neither a place nor a town nor a
   * street) replaced by the nearest word of the index: "Cornelliani" -> "Corneliani", "Esselunga
   * mantva" -> "Esselunga mantova". A word that is the start of some name is left as typed (it
   * may be half typed). Words under five letters are never touched.
   */
  fun corrected(query: String, addresses: AddressIndex?): String {
    val toks = TextNorm.tokens(query)
    if (toks.isEmpty()) return query
    var changed = false
    val out = toks.map { t ->
      if (t in Matching.NOISE || Matching.allowedErrors(t) == 0 || TextNorm.isHouseNumber(t)) return@map t
      if (known(t) || addresses?.known(t) == true) return@map t
      val candidates = ArrayList<Pair<String, Int>>()
      for ((_, db) in dbs.all()) candidates += Terms.near(db, "place_fts", t)
      if (addresses != null) candidates += addresses.termsNear(t)
      val c = Matching.correct(t, candidates) ?: return@map t
      changed = true
      c
    }
    return if (changed) out.joinToString(" ") else query
  }

  /** Some name starts with this word. */
  private fun known(t: String): Boolean = dbs.all().any { (_, db) ->
    runCatching { db.rawQuery("SELECT 1 FROM place_fts WHERE norm MATCH ? LIMIT 1", arrayOf("$t*")).use { it.moveToFirst() } }.getOrDefault(false)
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

/**
 * The words of an FTS index, for the corrections: an fts4aux table over the index, made in the
 * temporary database of the connection (the file stays read only) the first time it is needed.
 * Without fts4aux (an unexpected SQLite) there is simply no correction.
 */
object Terms {
  private val made = HashSet<String>()

  /** The words of the index starting with the two letters of [token], with how many places use each. */
  fun near(db: SQLiteDatabase, fts: String, token: String): List<Pair<String, Int>> {
    if (token.length < 2) return emptyList()
    val aux = "terms_$fts"
    val key = "${System.identityHashCode(db)}:$aux"
    synchronized(made) {
      if (key !in made) {
        val ok = runCatching { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS temp.$aux USING fts4aux(main, $fts)") }
            .onFailure { Log.w("NavMasterSearch", "fts4aux $fts: $it") }.isSuccess
        if (!ok) return emptyList()
        made += key
      }
    }
    val from = token.take(2)
    val to = from.dropLast(1) + (from.last() + 1)
    val out = ArrayList<Pair<String, Int>>()
    runCatching {
      // the lengths in the SQL itself: Android binds every argument as text, and a number never
      // falls between two texts (nothing came back on the tablet)
      db.rawQuery("SELECT term, documents FROM temp.$aux WHERE col = '*' AND term >= ? AND term < ? " +
          "AND length(term) BETWEEN ${token.length - 2} AND ${token.length + 2}", arrayOf(from, to)).use { c ->
        while (c.moveToNext()) out += c.getString(0) to c.getInt(1)
      }
    }.onFailure { Log.w("NavMasterSearch", "terms $fts: $it") }
    return out
  }
}
