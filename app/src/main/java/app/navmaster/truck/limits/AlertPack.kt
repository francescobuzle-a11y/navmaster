package app.navmaster.truck.limits

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.navmaster.truck.AppGraph
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner's pack of verified alerts (autovelox fissi e mobili, Tutor, semafori, varchi ZTL, zone
 * ambientali, controlli camion, dossi, pericoli) made on the computer by data/allerte.py from the
 * folder «allerts» (Radarbot: data for personal use, never published by the app or its releases).
 *
 * The file has the tables of limiti.sqlite (limits + cells), so the guidance reads it like the other
 * camera data (LimitsIndex), and the columns the map needs (cc, grp, type, dir, road, lat, lon).
 * It comes to the tablet from Impostazioni › Mappa e vista (a file chosen on the tablet), or by
 * cable: a .sqlite put in /sdcard/Android/data/app.navmaster.truck/files/allerte/ is taken in at the
 * next start (copied into the app's own storage, where reading is fast, and removed from there).
 */
object AlertPack {
  const val FORMAT = "navmaster-allerte"
  private const val TAG = "NavMasterAlerts"

  data class Info(val count: Int, val source: String, val file: String, val built: String, val countries: Map<String, Int>)

  private fun dir(): File = File(AppGraph.app.filesDir, "allerte").apply { mkdirs() }
  private fun file(): File = File(dir(), "allerte.sqlite")

  /** Where a pack copied by cable is looked for. */
  fun cableDir(): File? = runCatching { AppGraph.app.getExternalFilesDir("allerte") }.getOrNull()

  private val _version = MutableStateFlow(0)
  /** Changes when the pack is imported or removed (the map reads it again). */
  val version: StateFlow<Int> = _version.asStateFlow()

  @Volatile private var open: SQLiteDatabase? = null

  /** The pack as a read-only database, null when there is none. */
  @Synchronized
  fun db(): SQLiteDatabase? {
    open?.let { return it }
    val f = file()
    if (!f.exists()) return null
    return runCatching { SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
        .onFailure { Log.w(TAG, "alert pack: $it") }.getOrNull()?.also { open = it }
  }

  /** What the pack holds, null when there is none. */
  fun info(): Info? = db()?.let { readInfo(it) }

  /** Removes the pack. */
  @Synchronized
  fun clear() {
    open?.close()
    open = null
    file().delete()
    _version.value++
  }

  /**
   * Keeps the pack read from [input] (file [name]): the number of alerts, 0 when it is not a pack
   * of NavMaster alerts (the one in use stays).
   */
  @Synchronized
  fun import(input: InputStream, name: String): Int {
    val tmp = File(dir(), "allerte.sqlite.new")
    tmp.delete()
    tmp.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
    val count = runCatching {
      SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db -> readInfo(db)?.count ?: 0 }
    }.onFailure { Log.w(TAG, "not an alert pack ($name): $it") }.getOrDefault(0)
    if (count <= 0) {
      tmp.delete()
      return 0
    }
    open?.close()
    open = null
    val f = file()
    f.delete()
    if (!tmp.renameTo(f)) {
      tmp.delete()
      return 0
    }
    Log.i(TAG, "alert pack from $name: $count alerts")
    _version.value++
    return count
  }

  /** A pack copied by cable (see the top): taken in. The number of alerts, null when there was none. IO thread. */
  fun takeFromCable(): Int? {
    val d = cableDir() ?: return null
    val f = d.listFiles { x -> x.isFile && x.name.endsWith(".sqlite") }?.maxByOrNull { it.lastModified() } ?: return null
    val n = runCatching { f.inputStream().use { import(it, f.name) } }.onFailure { Log.w(TAG, "cable pack: $it") }.getOrDefault(0)
    // taken in (or unreadable): not read again at every start
    if (n > 0) f.delete() else f.renameTo(File(d, f.name + ".illeggibile"))
    return n
  }

  /** The pack's description (meta), null when [db] is not a pack of alerts. */
  fun readInfo(db: SQLiteDatabase): Info? {
    val meta = HashMap<String, String>()
    db.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
    if (meta["format"] != FORMAT) return null
    val countries = HashMap<String, Int>()
    meta["countries"]?.let { s ->
      // {"IT":10909,"FR":3645}: read by hand, the keys are two letters
      Regex("\"([A-Z]{2})\":(\\d+)").findAll(s).forEach { m -> countries[m.groupValues[1]] = m.groupValues[2].toInt() }
    }
    return Info(meta["count"]?.toIntOrNull() ?: 0, meta["source"] ?: "", meta["file"] ?: "", meta["built"] ?: "", countries)
  }

  /**
   * Radarbot's own limits for kinds of vehicles at a camera (column veh, "hgv=80;van=70;bus=60", km/h,
   * only where they differ from the camera's limit): key to km/h, empty when none.
   */
  fun vehicleSpeeds(veh: String?): Map<String, Int> {
    if (veh.isNullOrBlank()) return emptyMap()
    val out = HashMap<String, Int>()
    for (part in veh.split(';')) {
      val k = part.substringBefore('=', "").trim()
      val v = part.substringAfter('=', "").trim().toIntOrNull() ?: continue
      if (k.isNotEmpty() && v in 5..200) out[k] = v
    }
    return out
  }

  /** The key of [vehicleSpeeds] for a vehicle: lorries over 3.5 t "hgv", vans "van", buses "bus"; campers have none (the camera's own limit). */
  fun vehicleKey(v: app.navmaster.truck.vehicle.VehicleProfile): String? = when (v.type) {
    app.navmaster.truck.vehicle.VehicleType.AUTOARTICOLATO, app.navmaster.truck.vehicle.VehicleType.AUTOTRENO,
    app.navmaster.truck.vehicle.VehicleType.MOTRICE -> if (v.maxWeightT > 3.5) "hgv" else "van"
    app.navmaster.truck.vehicle.VehicleType.FURGONE -> "van"
    app.navmaster.truck.vehicle.VehicleType.AUTOBUS -> "bus"
    app.navmaster.truck.vehicle.VehicleType.CAMPER -> null
  }

  /** Radarbot's limit at a camera for [v] (km/h), null when it has none of its own for that kind of vehicle. */
  fun vehicleSpeed(veh: String?, v: app.navmaster.truck.vehicle.VehicleProfile): Int? = vehicleKey(v)?.let { vehicleSpeeds(veh)[it] }

  private val vehColumn = java.util.WeakHashMap<SQLiteDatabase, Boolean>()

  /** The table limits of [db] has the column veh (packs made before 2026-11-16 do not). */
  fun hasVehicleColumn(db: SQLiteDatabase): Boolean = synchronized(vehColumn) {
    vehColumn.getOrPut(db) {
      runCatching { db.rawQuery("PRAGMA table_info(limits)", null).use { c -> var f = false; while (c.moveToNext()) if (c.getString(1) == "veh") f = true; f } }
          .getOrDefault(false)
    }
  }

  /** The alerts of the pack in [countries] (ISO codes, all of them when null). */
  fun points(db: SQLiteDatabase, countries: Collection<String>?): List<AlertPoint> {
    val out = ArrayList<AlertPoint>()
    val where = if (countries == null) "" else {
      val ok = countries.map { it.uppercase() }.filter { Regex("[A-Z]{2}").matches(it) }
      if (ok.isEmpty()) return out
      " WHERE cc IN (" + ok.joinToString(",") { "'$it'" } + ")"
    }
    val veh = if (hasVehicleColumn(db)) "veh" else "NULL"
    db.rawQuery("SELECT lat, lon, grp, type, kind, name, value, road, dir, cc, $veh FROM limits$where", null).use { c ->
      while (c.moveToNext()) {
        out += Alerts.fromPack(c.getDouble(0), c.getDouble(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
            c.getDouble(6), c.getString(7), c.getString(8), c.getString(9), vehicleSpeeds(c.getString(10))) ?: continue
      }
    }
    return out
  }
}
