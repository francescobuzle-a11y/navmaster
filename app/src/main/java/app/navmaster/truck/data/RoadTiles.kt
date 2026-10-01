package app.navmaster.truck.data

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A road of the map (OpenMapTiles "transportation" layer): its kind and its line. */
class RoadLine(
    val cls: String,
    val ramp: Boolean,
    val oneway: Boolean,
    val bridge: Boolean,
    val tunnel: Boolean,
    val lat: DoubleArray,
    val lon: DoubleArray,
)

/** Reads single tiles of a PMTiles v3 archive (the offline map of a country) from the file. */
class PmTiles(file: File) : Closeable {
  private val raf = RandomAccessFile(file, "r")
  private val rootOff: Long
  private val rootLen: Long
  private val leafOff: Long
  private val dataOff: Long
  private val internalGzip: Boolean
  private val tileGzip: Boolean
  val maxZoom: Int

  private class Entry(val tileId: Long, var offset: Long, var length: Int, var runLength: Int)

  private val dirs = object : LinkedHashMap<Long, List<Entry>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 24
  }

  init {
    val h = ByteArray(127)
    raf.seek(0)
    raf.readFully(h)
    if (String(h, 0, 7) != "PMTiles" || h[7].toInt() != 3) throw IllegalStateException("not a PMTiles v3 file")
    fun u64(o: Int): Long {
      var v = 0L
      for (i in 7 downTo 0) v = (v shl 8) or (h[o + i].toLong() and 0xff)
      return v
    }
    rootOff = u64(8)
    rootLen = u64(16)
    leafOff = u64(40)
    dataOff = u64(56)
    internalGzip = h[97].toInt() == 2
    tileGzip = h[98].toInt() == 2
    maxZoom = h[101].toInt()
  }

  private fun read(off: Long, len: Int): ByteArray {
    val b = ByteArray(len)
    synchronized(raf) {
      raf.seek(off)
      raf.readFully(b)
    }
    return b
  }

  private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

  private fun directory(off: Long, len: Long): List<Entry> {
    synchronized(dirs) { dirs[off]?.let { return it } }
    val raw = read(off, len.toInt()).let { if (internalGzip) gunzip(it) else it }
    val p = Pb(raw)
    val n = p.varint().toInt()
    val list = ArrayList<Entry>(n)
    var last = 0L
    repeat(n) {
      last += p.varint()
      list += Entry(last, 0, 0, 1)
    }
    for (e in list) e.runLength = p.varint().toInt()
    for (e in list) e.length = p.varint().toInt()
    for ((i, e) in list.withIndex()) {
      val v = p.varint()
      e.offset = if (v == 0L && i > 0) list[i - 1].offset + list[i - 1].length else v - 1
    }
    synchronized(dirs) { dirs[off] = list }
    return list
  }

  private fun find(list: List<Entry>, id: Long): Entry? {
    var m = 0
    var n = list.size - 1
    while (m <= n) {
      val k = (n + m) ushr 1
      val c = id - list[k].tileId
      if (c > 0) m = k + 1 else if (c < 0) n = k - 1 else return list[k]
    }
    if (n >= 0) {
      if (list[n].runLength == 0) return list[n]
      if (id - list[n].tileId < list[n].runLength) return list[n]
    }
    return null
  }

  /** The decompressed vector tile, or null when the archive has none there. */
  fun tile(z: Int, x: Int, y: Int): ByteArray? {
    val id = tileId(z, x.toLong(), y.toLong())
    var off = rootOff
    var len = rootLen
    repeat(4) {
      val e = find(directory(off, len), id) ?: return null
      if (e.runLength > 0) return read(dataOff + e.offset, e.length).let { if (tileGzip) gunzip(it) else it }
      off = leafOff + e.offset
      len = e.length.toLong()
    }
    return null
  }

  override fun close() = raf.close()

  companion object {
    /** Hilbert tile id of the PMTiles format. */
    fun tileId(z: Int, x: Long, y: Long): Long {
      var acc = ((1L shl z) * (1L shl z) - 1) / 3
      var tx = x
      var ty = y
      var a = z - 1
      var s = 1L shl (z - 1).coerceAtLeast(0)
      if (z == 0) return 0
      while (s > 0) {
        val rx = tx and s
        val ry = ty and s
        acc += ((3 * rx) xor ry) * (1L shl a)
        if (ry == 0L) {
          if (rx != 0L) {
            val nx = s - 1 - ty
            ty = s - 1 - tx
            tx = nx
          } else {
            val t = tx
            tx = ty
            ty = t
          }
        }
        a--
        s = s shr 1
      }
      return acc
    }
  }
}

/** Minimal protobuf reader for the vector tiles. */
private class Pb(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
  fun more() = p < end

  fun varint(): Long {
    var r = 0L
    var s = 0
    while (true) {
      val x = b[p++].toInt() and 0xff
      r = r or ((x and 0x7f).toLong() shl s)
      if (x < 0x80) return r
      s += 7
    }
  }

  fun skip(wire: Int) {
    when (wire) {
      0 -> varint()
      1 -> p += 8
      2 -> p += varint().toInt()
      5 -> p += 4
    }
  }
}

/**
 * The roads around a place, read from the offline map (the same tiles the map is drawn with), for
 * the junction view: it draws the real junction, not a picture of a standard one.
 */
object RoadTiles {
  private const val TAG = "NavMasterRoads"
  private const val Z = 14
  private val readers = HashMap<String, PmTiles>()
  private val cache = object : LinkedHashMap<String, List<RoadLine>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RoadLine>>?) = size > 24
  }

  private fun reader(file: File): PmTiles? = synchronized(readers) {
    readers.getOrPut(file.absolutePath) { runCatching { PmTiles(file) }.getOrElse { Log.w(TAG, "map $file: $it"); return null } }
  }

  /** The roads of the map tiles within about [radiusM] of the point. */
  fun around(file: File, lat: Double, lon: Double, radiusM: Double): List<RoadLine> {
    val pm = reader(file) ?: return emptyList()
    val z = minOf(Z, pm.maxZoom)
    val n = 1 shl z
    fun tx(lo: Double) = floor((lo + 180) / 360 * n).toInt().coerceIn(0, n - 1)
    fun ty(la: Double): Int {
      val r = Math.toRadians(la)
      return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
    }
    val dLat = radiusM / 110_540.0
    val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)))
    val out = ArrayList<RoadLine>()
    for (x in tx(lon - dLon)..tx(lon + dLon)) for (y in ty(lat + dLat)..ty(lat - dLat)) {
      val key = "${file.absolutePath}/$z/$x/$y"
      val roads = synchronized(cache) { cache[key] } ?: runCatching {
        pm.tile(z, x, y)?.let { decode(it, z, x, y) } ?: emptyList()
      }.getOrElse { Log.w(TAG, "tile $z/$x/$y: $it"); emptyList() }.also { synchronized(cache) { cache[key] = it } }
      out += roads
    }
    return out
  }

  private fun decode(tile: ByteArray, z: Int, x: Int, y: Int): List<RoadLine> {
    val out = ArrayList<RoadLine>()
    val r = Pb(tile)
    while (r.more()) {
      val key = r.varint().toInt()
      if (key ushr 3 == 3 && key and 7 == 2) {
        val len = r.varint().toInt()
        layer(tile, r.p, r.p + len, z, x, y, out)
        r.p += len
      } else r.skip(key and 7)
    }
    return out
  }

  private fun layer(b: ByteArray, start: Int, end: Int, z: Int, x: Int, y: Int, out: MutableList<RoadLine>) {
    var name = ""
    val keys = ArrayList<String>()
    val vals = ArrayList<Any?>()
    var extent = 4096
    val feats = ArrayList<IntArray>()
    val r = Pb(b, start, end)
    while (r.more()) {
      val key = r.varint().toInt()
      val f = key ushr 3
      val w = key and 7
      if (w != 2) {
        if (f == 5 && w == 0) extent = r.varint().toInt() else r.skip(w)
        continue
      }
      val len = r.varint().toInt()
      when (f) {
        1 -> name = String(b, r.p, len)
        2 -> feats += intArrayOf(r.p, r.p + len)
        3 -> keys += String(b, r.p, len)
        4 -> vals += value(b, r.p, r.p + len)
      }
      r.p += len
    }
    if (name != "transportation") return
    val n = 1 shl z
    for (fr in feats) {
      var tags: IntArray? = null
      var type = 0
      var geom: IntArray? = null
      val q = Pb(b, fr[0], fr[1])
      while (q.more()) {
        val key = q.varint().toInt()
        val f = key ushr 3
        val w = key and 7
        when {
          f == 2 && w == 2 -> tags = packed(q)
          f == 3 && w == 0 -> type = q.varint().toInt()
          f == 4 && w == 2 -> geom = packed(q)
          else -> q.skip(w)
        }
      }
      if (type != 2 || geom == null) continue
      val props = HashMap<String, Any?>()
      tags?.let { t -> for (i in 0 until t.size - 1 step 2) keys.getOrNull(t[i])?.let { k -> props[k] = vals.getOrNull(t[i + 1]) } }
      val cls = props["class"] as? String ?: continue
      if (cls in setOf("path", "track", "rail", "transit", "ferry", "aerialway", "busway", "bridge", "pier")) continue
      val brunnel = props["brunnel"] as? String
      val ramp = (props["ramp"] as? Number)?.toInt() == 1
      val oneway = (props["oneway"] as? Number)?.toInt()?.let { it != 0 } ?: false
      // geometry: MoveTo / LineTo with zigzag deltas
      var cx = 0
      var cy = 0
      var i = 0
      var la = ArrayList<Double>()
      var lo = ArrayList<Double>()
      fun flush() {
        if (la.size >= 2) out += RoadLine(cls, ramp, oneway, brunnel == "bridge", brunnel == "tunnel", la.toDoubleArray(), lo.toDoubleArray())
        la = ArrayList()
        lo = ArrayList()
      }
      while (i < geom.size) {
        val c = geom[i++]
        val id = c and 7
        val count = c ushr 3
        if (id == 1 || id == 2) {
          if (id == 1) flush()
          repeat(count) {
            val dx = geom[i++].let { (it ushr 1) xor -(it and 1) }
            val dy = geom[i++].let { (it ushr 1) xor -(it and 1) }
            cx += dx
            cy += dy
            lo += (x + cx.toDouble() / extent) / n * 360.0 - 180.0
            la += Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + cy.toDouble() / extent) / n))))
          }
        }
      }
      flush()
    }
  }

  private fun packed(q: Pb): IntArray {
    val len = q.varint().toInt()
    val e = q.p + len
    val list = ArrayList<Int>()
    while (q.p < e) list += q.varint().toInt()
    return list.toIntArray()
  }

  private fun value(b: ByteArray, start: Int, end: Int): Any? {
    val r = Pb(b, start, end)
    while (r.more()) {
      val key = r.varint().toInt()
      val f = key ushr 3
      val w = key and 7
      when (f) {
        1 -> {
          val len = r.varint().toInt()
          return String(b, r.p, len)
        }
        2 -> {
          val bits = (b[r.p].toInt() and 0xff) or ((b[r.p + 1].toInt() and 0xff) shl 8) or ((b[r.p + 2].toInt() and 0xff) shl 16) or
              ((b[r.p + 3].toInt() and 0xff) shl 24)
          return java.lang.Float.intBitsToFloat(bits).toDouble()
        }
        3 -> {
          var v = 0L
          for (k in 7 downTo 0) v = (v shl 8) or (b[r.p + k].toLong() and 0xff)
          return java.lang.Double.longBitsToDouble(v)
        }
        4, 5 -> return r.varint()
        6 -> return r.varint().let { (it ushr 1) xor -(it and 1) }
        7 -> return r.varint() != 0L
        else -> r.skip(w)
      }
    }
    return null
  }
}
