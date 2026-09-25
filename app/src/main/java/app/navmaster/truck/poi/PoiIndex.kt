package app.navmaster.truck.poi

import android.database.Cursor
import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.RegionDbs
import app.navmaster.truck.data.RegionManager
import app.navmaster.truck.data.RouteMatcher
import app.navmaster.truck.search.TextNorm
import uniffi.ferrostar.GeographicCoordinate

data class Poi(
    val key: String,
    val cat: String,
    val name: String?,
    val brand: String?,
    val lat: Double,
    val lon: Double,
    val flags: Set<String>,
    val hours: String?,
    val phone: String?,
    val extra: String?,
) {
  val coordinate: GeographicCoordinate
    get() = GeographicCoordinate(lat, lon)

  val title: String
    get() = name ?: brand ?: ""
}

/** A place along the route: how far ahead and how far from the road. */
data class RoutePoi(val poi: Poi, val alongM: Double, val offRouteM: Double)

/** poi.sqlite of every installed country: places along the route, near a point, by name. */
class PoiIndex(regions: RegionManager) {
  private val dbs = RegionDbs(regions) { it.poi }

  private fun read(c: Cursor, region: String): Poi =
      Poi(
          key = "$region:${c.getLong(0)}",
          cat = c.getString(1),
          name = c.getString(2),
          brand = c.getString(3),
          lat = c.getDouble(4),
          lon = c.getDouble(5),
          flags = c.getString(6)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
          hours = c.getString(7),
          phone = c.getString(8),
          extra = c.getString(9),
      )

  private val cols = "p.id, p.cat, p.name, p.brand, p.lat, p.lon, p.flags, p.hours, p.phone, p.extra"

  fun alongRoute(m: RouteMatcher, categories: Set<String>, truckOnly: Boolean, maxOffM: Int): List<RoutePoi> {
    if (categories.isEmpty() || m.route.size < 2) return emptyList()
    val inCats = categories.joinToString(",") { "'${it.replace("'", "")}'" }
    val out = HashMap<String, RoutePoi>()
    for ((region, db) in dbs.all()) {
      for (chunk in m.cells.chunked(400)) {
        db.rawQuery("SELECT DISTINCT $cols FROM poi_cells c JOIN poi p ON p.id = c.pid WHERE c.cell IN (${chunk.joinToString(",")}) " +
            "AND p.cat IN ($inCats)", null).use { c ->
          while (c.moveToNext()) {
            val p = read(c, region.id)
            if (truckOnly && p.cat == "fuel" && "hgv" !in p.flags) continue
            val (d, along) = m.nearest(p.coordinate) ?: continue
            val limit = if (p.cat in setOf("services", "rest_area")) maxOf(maxOffM, 400) else maxOffM
            if (d <= limit) out.putIfAbsent(p.key, RoutePoi(p, along, d))
          }
        }
      }
    }
    return out.values.sortedBy { it.alongM }
  }

  fun near(lat: Double, lon: Double, radiusM: Double, categories: Set<String>? = null, limit: Int = 50): List<Pair<Poi, Double>> {
    val reach = (radiusM / 1100.0).toInt() + 1
    val cells = buildList { for (dy in -reach..reach) for (dx in -reach..reach) add(Geo.cell(lat + dy * Geo.CELL, lon + dx * Geo.CELL)) }
    val out = mutableListOf<Pair<Poi, Double>>()
    val catFilter = categories?.joinToString(",") { "'${it.replace("'", "")}'" }?.let { " AND p.cat IN ($it)" } ?: ""
    for ((region, db) in dbs.all()) {
      db.rawQuery("SELECT DISTINCT $cols FROM poi_cells c JOIN poi p ON p.id = c.pid WHERE c.cell IN (${cells.joinToString(",")})$catFilter", null).use { c ->
        while (c.moveToNext()) {
          val p = read(c, region.id)
          val d = Geo.dist(lat, lon, p.lat, p.lon)
          if (d <= radiusM) out += p to d
        }
      }
    }
    return out.sortedBy { it.second }.take(limit)
  }

  fun search(query: String, limit: Int = 20): List<Poi> {
    val fts = TextNorm.ftsQuery(query) ?: return emptyList()
    val out = mutableListOf<Poi>()
    for ((region, db) in dbs.all()) {
      runCatching {
        db.rawQuery("SELECT $cols FROM poi_fts f JOIN poi p ON p.id = f.docid WHERE f.norm MATCH ? LIMIT $limit", arrayOf(fts)).use { c ->
          while (c.moveToNext()) out += read(c, region.id)
        }
      }
    }
    return out
  }
}
