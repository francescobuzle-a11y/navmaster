package app.navmaster.truck.search

import android.database.sqlite.SQLiteDatabase
import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.InstalledRegion
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import java.text.Normalizer
import uniffi.ferrostar.GeographicCoordinate

/** Same normalisation as data/nmcommon.py: lower case, no accents, letters and digits only. */
object TextNorm {
  private val special = mapOf('ß' to "ss", 'æ' to "ae", 'ø' to "o", 'œ' to "oe", 'đ' to "d", 'ł' to "l", 'ı' to "i", 'þ' to "th", 'ð' to "d")
  private val marks = Regex("\\p{Mn}+")
  private val keep = Regex("[^a-z0-9 ]+")
  private val spaces = Regex("\\s+")

  fun norm(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val lower = buildString { for (ch in text.lowercase()) append(special[ch] ?: ch.toString()) }
    val stripped = marks.replace(Normalizer.normalize(lower, Normalizer.Form.NFKD), "")
    return spaces.replace(keep.replace(stripped, " "), " ").trim()
  }

  fun tokens(text: String?): List<String> = norm(text).split(' ').filter { it.isNotEmpty() }

  /** "str mihai emin" -> "str* mihai* emin*" for FTS4 prefix search; null when nothing to search. */
  fun ftsQuery(text: String?): String? = tokens(text).takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }

  fun isHouseNumber(token: String): Boolean = Regex("^\\d{1,5}[a-z]?$").matches(token)
}

data class PlaceHit(
    val region: String,
    val id: Long,
    val name: String,
    val kind: String,
    val county: String?,
    val lat: Double,
    val lon: Double,
    val parentId: Long?,
    val parentName: String?,
) {
  val kindLabel: String
    get() =
        when (kind) {
          "city" -> "Città"
          "town" -> "Cittadina"
          "village" -> "Paese"
          "hamlet" -> "Frazione"
          "suburb", "quarter" -> "Quartiere"
          else -> "Località"
        }
}

data class StreetHit(
    val region: String,
    val id: Long,
    val name: String,
    val placeName: String?,
    val county: String?,
    val lat: Double,
    val lon: Double,
    val noStreet: Boolean,
)

data class HouseHit(val num: String, val lat: Double, val lon: Double)

/** A result of the free search, ready to show and to navigate to. */
data class Found(
    val title: String,
    val detail: String,
    val coordinate: GeographicCoordinate,
    val icon: String,
    val distanceM: Double? = null,
    val offline: Boolean = true,
)

/**
 * The offline address book (indirizzi.sqlite of each installed country), searched the iGO/Garmin
 * way (country -> town -> street -> number) or with free text the Google way.
 */
class AddressIndex(private val regions: RegionManager) {
  private val dbs = RegionDbs(regions) { it.addresses }

  private fun db(region: String): SQLiteDatabase? = dbs.all().firstOrNull { it.first.id == region }?.second

  fun regionsWithAddresses(): List<InstalledRegion> = dbs.all().map { it.first }

  fun places(region: String, query: String, limit: Int = 40): List<PlaceHit> {
    val db = db(region) ?: return emptyList()
    val fts = TextNorm.ftsQuery(query)
    val sql =
        if (fts == null) "SELECT p.id, p.name, p.kind, p.county, p.lat, p.lon, p.parent, q.name FROM places p LEFT JOIN places q ON q.id = p.parent " +
            "WHERE p.rank <= 2 ORDER BY p.rank, p.pop DESC LIMIT $limit"
        else "SELECT p.id, p.name, p.kind, p.county, p.lat, p.lon, p.parent, q.name FROM place_fts f JOIN places p ON p.id = f.docid " +
            "LEFT JOIN places q ON q.id = p.parent WHERE f.norm MATCH ? ORDER BY p.rank, p.pop DESC LIMIT $limit"
    val out = mutableListOf<PlaceHit>()
    db.rawQuery(sql, if (fts == null) null else arrayOf(fts)).use { c ->
      while (c.moveToNext()) {
        out += PlaceHit(region, c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4), c.getDouble(5),
            if (c.isNull(6)) null else c.getLong(6), c.getString(7))
      }
    }
    // the name starting with what was typed first
    val q = TextNorm.norm(query)
    return out.sortedWith(compareBy({ !TextNorm.norm(it.name).startsWith(q) }, { rankOf(it.kind) }))
  }

  private fun rankOf(kind: String) = when (kind) { "city" -> 1; "town" -> 2; "village", "suburb" -> 3; else -> 4 }

  fun streets(region: String, place: PlaceHit, query: String, limit: Int = 120): List<StreetHit> {
    val db = db(region) ?: return emptyList()
    val placeId = place.parentId ?: place.id
    val toks = TextNorm.tokens(query)
    val where = toks.joinToString("") { " AND norm LIKE ?" }
    val args = arrayOf(placeId.toString()) + toks.map { "%$it%" }
    val out = mutableListOf<StreetHit>()
    db.rawQuery("SELECT id, name, lat, lon, nostreet FROM streets WHERE place_id = ?$where ORDER BY name LIMIT $limit", args).use { c ->
      while (c.moveToNext()) {
        out += StreetHit(region, c.getLong(0), c.getString(1), place.parentName ?: place.name, place.county, c.getDouble(2), c.getDouble(3),
            c.getInt(4) == 1)
      }
    }
    val q = TextNorm.norm(query)
    return out.sortedWith(compareBy({ s -> !TextNorm.tokens(s.name).any { it.startsWith(q.substringBefore(' ')) } }, { it.name }))
  }

  fun houses(region: String, streetId: Long): List<HouseHit> {
    val db = db(region) ?: return emptyList()
    val out = mutableListOf<HouseHit>()
    db.rawQuery("SELECT num, lat, lon FROM houses WHERE street_id = ?", arrayOf(streetId.toString())).use { c ->
      while (c.moveToNext()) out += HouseHit(c.getString(0), c.getDouble(1), c.getDouble(2))
    }
    return out.distinctBy { it.num }.sortedWith(compareBy({ it.num.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it.num }))
  }

  /** Free text: streets (with the number when typed), towns, across every installed country. */
  fun free(query: String, near: GeographicCoordinate?, limit: Int = 30): List<Found> {
    val toks = TextNorm.tokens(query)
    if (toks.isEmpty()) return emptyList()
    val number = toks.lastOrNull { TextNorm.isHouseNumber(it) }
    val words = toks.filter { it != number }
    if (words.isEmpty()) return emptyList()
    val fts = words.joinToString(" ") { "$it*" }
    val out = mutableListOf<Found>()
    for ((region, db) in dbs.all()) {
      // towns
      db.rawQuery("SELECT p.name, p.kind, p.county, p.lat, p.lon, q.name FROM place_fts f JOIN places p ON p.id = f.docid " +
          "LEFT JOIN places q ON q.id = p.parent WHERE f.norm MATCH ? ORDER BY p.rank, p.pop DESC LIMIT 15", arrayOf(fts)).use { c ->
        while (c.moveToNext()) {
          val lat = c.getDouble(3)
          val lon = c.getDouble(4)
          val detail = listOfNotNull(c.getString(5), c.getString(2), region.label).distinct().joinToString(" · ")
          out += Found(c.getString(0), detail, GeographicCoordinate(lat, lon), "🏙", near?.let { Geo.dist(it.lat, it.lng, lat, lon) })
        }
      }
      // streets ("mihai eminescu brasov" matches street words and town words together)
      db.rawQuery("SELECT s.id, s.name, s.lat, s.lon, p.name, p.county FROM street_fts f JOIN streets s ON s.id = f.docid " +
          "JOIN places p ON p.id = s.place_id WHERE f.norm MATCH ? LIMIT 60", arrayOf(fts)).use { c ->
        while (c.moveToNext()) {
          var lat = c.getDouble(2)
          var lon = c.getDouble(3)
          var title = c.getString(1)
          if (number != null) {
            val h = houses(region.id, c.getLong(0)).firstOrNull { it.num.equals(number, true) }
                ?: houses(region.id, c.getLong(0)).firstOrNull { it.num.takeWhile { ch -> ch.isDigit() } == number.takeWhile { ch -> ch.isDigit() } }
            if (h != null) {
              lat = h.lat
              lon = h.lon
              title = "$title ${h.num}"
            }
          }
          val detail = listOfNotNull(c.getString(4), c.getString(5), region.label).distinct().joinToString(" · ")
          out += Found(title, detail, GeographicCoordinate(lat, lon), "📍", near?.let { Geo.dist(it.lat, it.lng, lat, lon) })
        }
      }
    }
    // with a house number the matching address first; then by distance
    return out.sortedWith(compareBy<Found>({ number != null && !it.title.endsWith(" $number", true) }, { it.icon != "🏙" || words.size > 1 },
        { it.distanceM ?: 0.0 })).distinctBy { "${it.title}|${it.detail}" }.take(limit)
  }
}
