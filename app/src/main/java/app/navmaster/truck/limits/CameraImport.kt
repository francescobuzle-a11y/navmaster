package app.navmaster.truck.limits

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.navmaster.truck.AppGraph
import app.navmaster.truck.core.Geo
import java.io.File
import java.io.InputStream

/**
 * The owner's own list of fixed speed cameras, imported from a file (Impostazioni › Autovelox):
 * the lists sold by the speed-camera database providers (SCDB and the like) in the formats every
 * navigator reads, GPX (waypoints) or CSV (Garmin/TomTom: longitude, latitude, name), or a CSV of
 * his own. Kept in a small SQLite file with the tables of limiti.sqlite (limits + cells), so the
 * guidance reads it exactly as the countries' data (LimitsIndex), with ver = "importato".
 *
 * The name of each point gives what it is and the speed it checks when the file says so
 * ("SCDB_Fixed_130", "Autovelox 50", "Red light", "Section control start"...).
 */
object CameraImport {
  const val VER = "importato"
  private const val TAG = "NavMasterCameras"

  data class Cam(val lat: Double, val lon: Double, val speed: Double, val name: String?, val what: String?)

  data class Info(val count: Int, val file: String, val date: String)

  private fun file(): File = File(AppGraph.app.filesDir, "autovelox-importati.sqlite")

  @Volatile private var open: SQLiteDatabase? = null

  /** The list as a read-only database, null when none was imported. */
  @Synchronized
  fun db(): SQLiteDatabase? {
    val f = file()
    if (!f.exists()) return null
    open?.let { return it }
    return runCatching { SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
        .onFailure { Log.w(TAG, "imported cameras: $it") }.getOrNull()?.also { open = it }
  }

  /** What was imported (count, file name, date), null when nothing. */
  fun info(): Info? {
    val db = db() ?: return null
    return runCatching {
      val meta = HashMap<String, String>()
      db.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
      Info(meta["count"]?.toIntOrNull() ?: 0, meta["file"] ?: "", meta["date"] ?: "")
    }.getOrNull()
  }

  /** Forgets the list. */
  @Synchronized
  fun clear() {
    open?.close()
    open = null
    file().delete()
  }

  /** Reads [input] (file [name]) and keeps its cameras; the number kept (0: nothing readable). */
  @Synchronized
  fun import(input: InputStream, name: String): Int {
    val text = input.bufferedReader(Charsets.UTF_8).readText()
    val cams = parse(text, name)
    if (cams.isEmpty()) return 0
    open?.close()
    open = null
    val f = file()
    val tmp = File(f.parentFile, f.name + ".new")
    tmp.delete()
    val db = SQLiteDatabase.openOrCreateDatabase(tmp, null)
    try {
      db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
      db.execSQL("CREATE TABLE limits(id INTEGER PRIMARY KEY, osm TEXT, kind TEXT, value REAL, raw TEXT, cond TEXT, name TEXT, pts TEXT, ver TEXT)")
      db.execSQL("CREATE TABLE cells(cell INTEGER NOT NULL, lid INTEGER NOT NULL)")
      db.beginTransaction()
      val ins = db.compileStatement("INSERT INTO limits(osm, kind, value, raw, cond, name, pts, ver) VALUES (?,?,?,?,?,?,?,?)")
      val cell = db.compileStatement("INSERT INTO cells(cell, lid) VALUES (?,?)")
      for (c in cams) {
        ins.clearBindings()
        ins.bindString(1, "")
        ins.bindString(2, "speed_camera")
        ins.bindDouble(3, c.speed)
        ins.bindNull(4)
        ins.bindNull(5)
        val label = c.what ?: c.name
        if (label != null) ins.bindString(6, label) else ins.bindNull(6)
        ins.bindString(7, String.format(java.util.Locale.ROOT, "%.6f,%.6f", c.lat, c.lon))
        ins.bindString(8, VER)
        val id = ins.executeInsert()
        cell.clearBindings()
        cell.bindLong(1, Geo.cell(c.lat, c.lon))
        cell.bindLong(2, id)
        cell.executeInsert()
      }
      db.execSQL("CREATE INDEX cells_cell ON cells(cell)")
      db.execSQL("INSERT INTO meta(key, value) VALUES ('count', ?)", arrayOf(cams.size.toString()))
      db.execSQL("INSERT INTO meta(key, value) VALUES ('file', ?)", arrayOf(name))
      db.execSQL("INSERT INTO meta(key, value) VALUES ('date', ?)", arrayOf(java.time.LocalDate.now().toString()))
      db.setTransactionSuccessful()
      db.endTransaction()
    } finally {
      db.close()
    }
    f.delete()
    tmp.renameTo(f)
    Log.i(TAG, "imported ${cams.size} cameras from $name")
    return cams.size
  }

  /**
   * The cameras of a file: GPX waypoints, or CSV lines "lon,lat,name[,comment]" (Garmin, TomTom
   * ov2 exports, SCDB) or "lat,lon,name" when the file says so in a header line. Mobile-camera
   * hotspots ("mobile", "mobil", "hotspot") are not fixed cameras and are left out.
   */
  fun parse(text: String, name: String): List<Cam> {
    val t = text.trimStart('﻿')
    return if (t.contains("<gpx", true) || name.endsWith(".gpx", true)) parseGpx(t) else parseCsv(t)
  }

  private val WPT = Regex("<wpt([^>]*)>(.*?)</wpt>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
  private val LAT = Regex("\\blat=\"([^\"]+)\"")
  private val LON = Regex("\\blon=\"([^\"]+)\"")
  private val NAME = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
  private val DESC = Regex("<(?:desc|cmt|type)>(.*?)</(?:desc|cmt|type)>", RegexOption.DOT_MATCHES_ALL)

  private fun parseGpx(t: String): List<Cam> {
    val out = ArrayList<Cam>()
    for (m in WPT.findAll(t)) {
      val attrs = m.groupValues[1]
      out += wpt(LAT.find(attrs)?.groupValues?.get(1), LON.find(attrs)?.groupValues?.get(1), m.groupValues[2]) ?: continue
    }
    return out
  }

  private fun wpt(lat: String?, lon: String?, body: String): Cam? {
    val la = lat?.toDoubleOrNull() ?: return null
    val lo = lon?.toDoubleOrNull() ?: return null
    val name = NAME.find(body)?.groupValues?.get(1)?.let { unescape(it) }
    val desc = DESC.find(body)?.groupValues?.get(1)?.let { unescape(it) }
    return cam(la, lo, listOfNotNull(name, desc).joinToString(" · ").ifBlank { null })
  }

  private fun unescape(s: String): String =
      s.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
          .replace("&quot;", "\"").replace("&apos;", "'").trim()

  private fun parseCsv(t: String): List<Cam> {
    val out = ArrayList<Cam>()
    var latFirst: Boolean? = null
    for (raw in t.lineSequence()) {
      val line = raw.trim()
      if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
      val sep = when {
        line.count { it == ';' } >= 2 -> ';'
        line.count { it == '\t' } >= 2 -> '\t'
        else -> ','
      }
      val f = split(line, sep)
      if (f.size < 2) continue
      val a = f[0].trim().replace(',', '.').toDoubleOrNull()
      val b = f[1].trim().replace(',', '.').toDoubleOrNull()
      if (a == null || b == null) {
        // a header line: which column is the latitude
        val h0 = f[0].lowercase()
        if (h0.startsWith("lat")) latFirst = true else if (h0.startsWith("lon") || h0.startsWith("lng") || h0 == "x") latFirst = false
        continue
      }
      // Garmin/TomTom CSV: longitude first; a latitude is never beyond 90
      val lf = latFirst ?: ((abs(a) <= 90 && abs(b) > 90) || (abs(a) <= 90 && abs(b) <= 90 && a > b && a > 30))
      val lat = if (lf) a else b
      val lon = if (lf) b else a
      if (abs(lat) > 90 || abs(lon) > 180) continue
      val name = f.drop(2).joinToString(" · ") { it.trim().trim('"') }.ifBlank { null }
      out += cam(lat, lon, name) ?: continue
    }
    return out
  }

  private fun abs(v: Double) = kotlin.math.abs(v)

  private fun split(line: String, sep: Char): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    for (ch in line) {
      when {
        ch == '"' -> quoted = !quoted
        ch == sep && !quoted -> { out += cur.toString(); cur.setLength(0) }
        else -> cur.append(ch)
      }
    }
    out += cur.toString()
    return out
  }

  private val SPEED = Regex("(?<![\\d.])(\\d{2,3})(?![\\d.])")

  /** The camera of a point: its kind and speed from the words of its name; null for a mobile hotspot. */
  private fun cam(lat: Double, lon: Double, name: String?): Cam? {
    val n = name?.lowercase() ?: ""
    if (n.contains("mobil") || n.contains("hotspot") || n.contains("possible") || n.contains("probab")) return null
    val what = when {
      n.contains("red") || n.contains("rosso") || n.contains("feu") || n.contains("ampel") || n.contains("semaf") -> "semaforo rosso"
      n.contains("section") || n.contains("tratto") || n.contains("tutor") || n.contains("average") || n.contains("traject") ||
          n.contains("tronçon") || n.contains("abschnitt") -> "controllo della velocità media"
      n.contains("rail") || n.contains("level") || n.contains("passaggio") -> "passaggio a livello"
      else -> null
    }
    val speed = SPEED.findAll(n).map { it.groupValues[1].toInt() }.firstOrNull { it in 20..140 && it % 5 == 0 }?.toDouble() ?: 0.0
    return Cam(lat, lon, speed, name?.take(60), what)
  }
}
