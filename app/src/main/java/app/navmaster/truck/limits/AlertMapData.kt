package app.navmaster.truck.limits

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.navmaster.truck.AppGraph
import app.navmaster.truck.data.InstalledRegion
import app.navmaster.truck.live.CameraFiles
import app.navmaster.truck.live.CameraVotes
import java.io.File

/**
 * The alerts of the map, always on (not only while driving), like on the dedicated navigators:
 * the owner's alert pack (AlertPack), the checked camera data of the countries on the tablet
 * (CameraFiles: OpenStreetMap, the official lists, Italy's census, the Tutor sections) and the
 * imported list (CameraImport), merged without doubles (Alerts.merge).
 *
 * Read once when the data change (a few thousand rows per country, off the main thread), then
 * written as two GeoJSON files the map loads itself ([render]): nothing is done when the map
 * moves or zooms, MapLibre groups and draws the points on its own.
 */
object AlertMapData {
  private const val TAG = "NavMasterAlerts"

  /** What the map draws: the alerts shown, and the GeoJSON files of the grouped and of the other ones. */
  data class Layer(val points: List<AlertPoint>, val clusteredUri: String?, val plainUri: String?)

  /** Every alert of the countries on the tablet ([regions]), merged, before the driver's choices. IO thread. */
  fun load(regions: List<InstalledRegion>, withImported: Boolean): List<AlertPoint> {
    val t0 = System.currentTimeMillis()
    val countries = regions.mapNotNull { r -> AppGraph.catalog.country(r.id) }
    val isos = countries.map { it.iso.uppercase() }.toSet()
    val pack = AlertPack.db()?.let { db ->
      runCatching { AlertPack.points(db, isos) }.onFailure { Log.w(TAG, "pack: $it") }.getOrDefault(emptyList())
    } ?: emptyList()
    // with the owner's pack on the tablet, the pack alone (his choice: no other camera list)
    val packOnly = AlertPack.db() != null
    val data = ArrayList<AlertPoint>()
    for (r in if (packOnly) emptyList() else regions) {
      val f = CameraFiles.fileFor(r.id) ?: r.limits.takeIf { it.exists() } ?: continue
      runCatching {
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
          data += limitPoints(db, AlertSource.DATA)
          data += sectionPoints(db)
        }
      }.onFailure { Log.w(TAG, "camera data of ${r.id}: $it") }
    }
    val imported = if (!withImported || packOnly) emptyList() else CameraImport.db()?.let { db ->
      val boxes = countries.mapNotNull { it.bbox }
      runCatching { limitPoints(db, AlertSource.IMPORTED) }.getOrDefault(emptyList()).filter { p ->
        // the list may cover the whole world: only the countries on the tablet
        boxes.isEmpty() || boxes.any { b -> p.lon >= b[0] && p.lat >= b[1] && p.lon <= b[2] && p.lat <= b[3] }
      }
    } ?: emptyList()
    val merged = Alerts.merge(listOf(pack, data, imported))
    Log.i(TAG, "alerts for the map: ${pack.size} from the pack, ${data.size} from the checked data, ${imported.size} imported → " +
        "${merged.size} (${System.currentTimeMillis() - t0} ms, ${isos.sorted().joinToString(",")})")
    return merged
  }

  /** The alerts of a table «limits» (checked data or imported list). */
  private fun limitPoints(db: SQLiteDatabase, source: AlertSource): List<AlertPoint> {
    val out = ArrayList<AlertPoint>()
    val ver = hasColumn(db, "limits", "ver")
    // the checked data have only cameras; "access" is a ZTL only in the imported list (ways of the maps are bans, not alerts)
    val kinds = "'speed_camera','speed_camera_mobile','ztl','low_emission_zone','truck_control','speed_bump','danger'" +
        if (source == AlertSource.IMPORTED) ",'access'" else ""
    db.rawQuery("SELECT kind, value, raw, name, pts, ${if (ver) "ver" else "NULL"} FROM limits WHERE kind IN ($kinds)", null).use { c ->
      while (c.moveToNext()) {
        val (lat, lon) = firstPoint(c.getString(4)) ?: continue
        out += Alerts.fromLimit(lat, lon, c.getString(0), c.getString(3), c.getDouble(1), c.getString(2), c.getString(5), source) ?: continue
      }
    }
    return out
  }

  /** The start and the end of each Tutor section (table «sections», absent in older data). */
  private fun sectionPoints(db: SQLiteDatabase): List<AlertPoint> {
    if (!hasColumn(db, "sections", "pts")) return emptyList()
    val out = ArrayList<AlertPoint>()
    db.rawQuery("SELECT value, name, pts, ver FROM sections", null).use { c ->
      while (c.moveToNext()) {
        val pts = c.getString(2) ?: continue
        val a = firstPoint(pts) ?: continue
        val b = firstPoint(pts.substringAfterLast(';')) ?: continue
        val speed = c.getDouble(0).let { if (it in 5.0..150.0) it.toInt() else 0 }
        val road = c.getString(1)?.ifBlank { null }
        out += AlertPoint(a.first, a.second, AlertGroup.TUTOR, "Inizio Tutor", speed, road, ver = c.getString(3), sources = setOf(AlertSource.DATA))
        out += AlertPoint(b.first, b.second, AlertGroup.TUTOR, "Fine Tutor", speed, road, ver = c.getString(3), sources = setOf(AlertSource.DATA))
      }
    }
    return out
  }

  private fun firstPoint(pts: String?): Pair<Double, Double>? {
    if (pts.isNullOrEmpty()) return null
    val first = pts.substringBefore(';')
    val k = first.indexOf(',')
    if (k <= 0) return null
    val lat = first.substring(0, k).toDoubleOrNull() ?: return null
    val lon = first.substring(k + 1).toDoubleOrNull() ?: return null
    return lat to lon
  }

  private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
      runCatching {
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> var f = false; while (c.moveToNext()) if (c.getString(1) == column) f = true; f }
      }.getOrDefault(false)

  /**
   * The alerts the driver wants on the map ([groups], the unconfirmed ones or not, none the drivers
   * said are gone) written as two GeoJSON files in [dir]: the cameras, grouped from far away, and the
   * others, shown only from close by. New names each time, so the map loads them again. IO thread.
   */
  fun render(all: List<AlertPoint>, groups: Set<String>, unverified: Boolean, device: String, dir: File): Layer {
    val gone = runCatching { CameraVotes.goneSpots(device) }.getOrDefault(emptyList())
    val shown = Alerts.shown(all, groups, unverified, gone)
    val out = File(dir, "allerte-mappa").apply { mkdirs() }
    val stamp = System.nanoTime().toString(36)
    fun write(name: String, idx: List<Int>): String? {
      if (idx.isEmpty()) return null
      val f = File(out, "$name-$stamp.geojson")
      f.bufferedWriter(Charsets.UTF_8, 1 shl 16).use { w -> Alerts.writeGeoJson(shown, idx, w) }
      // MapLibre reads local files from "file://" + the path (File.toURI() gives "file:/…")
      return "file://" + f.absolutePath
    }
    val clustered = shown.indices.filter { shown[it].group.cluster }
    val plain = shown.indices.filter { !shown[it].group.cluster }
    val a = write("gruppi", clustered)
    val b = write("altre", plain)
    // the files of before: the map has already read them (it keeps its own copy)
    out.listFiles { f -> !f.name.endsWith("-$stamp.geojson") }?.forEach { it.delete() }
    return Layer(shown, a, b)
  }
}
