package app.navmaster.truck.live

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh
import uniffi.ferrostar.GeographicCoordinate

/**
 * A small reader of Mapbox Vector Tiles (protobuf), enough for TomTom's traffic incident tiles:
 * layers, features, their properties and their geometry turned into coordinates.
 */
object Mvt {
  data class Feature(val layer: String, val type: Int, val props: Map<String, Any?>, val lines: List<List<GeographicCoordinate>>)

  private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
    fun more() = pos < end

    fun varint(): Long {
      var shift = 0
      var result = 0L
      while (pos < end) {
        val x = b[pos++].toInt() and 0xFF
        result = result or ((x and 0x7F).toLong() shl shift)
        if (x and 0x80 == 0) return result
        shift += 7
        if (shift > 63) break
      }
      return result
    }

    fun fixed32(): Int {
      var v = 0
      for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i))
      pos += 4
      return v
    }

    fun fixed64(): Long {
      var v = 0L
      for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
      pos += 8
      return v
    }

    fun sub(): Reader {
      val len = varint().toInt()
      val r = Reader(b, pos, pos + len)
      pos += len
      return r
    }

    fun string(): String {
      val len = varint().toInt()
      val s = String(b, pos, len, Charsets.UTF_8)
      pos += len
      return s
    }

    fun packed(): List<Long> {
      val r = sub()
      val out = ArrayList<Long>()
      while (r.more()) out += r.varint()
      return out
    }

    fun skip(wire: Int) {
      when (wire) {
        0 -> varint()
        1 -> pos += 8
        2 -> pos += varint().toInt()
        5 -> pos += 4
        else -> pos = end
      }
    }
  }

  private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

  fun decode(bytes: ByteArray, z: Int, x: Int, y: Int): List<Feature> {
    val out = mutableListOf<Feature>()
    val tile = Reader(bytes, 0, bytes.size)
    while (tile.more()) {
      val key = tile.varint().toInt()
      if (key ushr 3 == 3 && key and 7 == 2) out += layer(tile.sub(), z, x, y) else tile.skip(key and 7)
    }
    return out
  }

  private class RawFeature(val type: Int, val tags: List<Long>, val geometry: List<Long>)

  private fun layer(r: Reader, z: Int, x: Int, y: Int): List<Feature> {
    var name = ""
    var extent = 4096
    val keys = mutableListOf<String>()
    val values = mutableListOf<Any?>()
    val raw = mutableListOf<RawFeature>()
    while (r.more()) {
      val key = r.varint().toInt()
      when (key ushr 3) {
        1 -> name = r.string()
        2 -> raw += feature(r.sub())
        3 -> keys += r.string()
        4 -> values += value(r.sub())
        5 -> extent = r.varint().toInt()
        else -> r.skip(key and 7)
      }
    }
    return raw.map { f ->
      val props = HashMap<String, Any?>()
      var i = 0
      while (i + 1 < f.tags.size) {
        val k = keys.getOrNull(f.tags[i].toInt())
        if (k != null) props[k] = values.getOrNull(f.tags[i + 1].toInt())
        i += 2
      }
      Feature(name, f.type, props, geometry(f.geometry, extent, z, x, y))
    }
  }

  private fun feature(r: Reader): RawFeature {
    var type = 0
    var tags: List<Long> = emptyList()
    var geometry: List<Long> = emptyList()
    while (r.more()) {
      val key = r.varint().toInt()
      when (key ushr 3) {
        2 -> tags = r.packed()
        3 -> type = r.varint().toInt()
        4 -> geometry = r.packed()
        else -> r.skip(key and 7)
      }
    }
    return RawFeature(type, tags, geometry)
  }

  private fun value(r: Reader): Any? {
    var v: Any? = null
    while (r.more()) {
      val key = r.varint().toInt()
      v = when (key ushr 3) {
        1 -> r.string()
        2 -> java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
        3 -> java.lang.Double.longBitsToDouble(r.fixed64())
        4 -> r.varint()
        5 -> r.varint()
        6 -> zigzag(r.varint())
        7 -> r.varint() != 0L
        else -> { r.skip(key and 7); v }
      }
    }
    return v
  }

  /** Tile pixels (0..extent) to longitude / latitude. */
  private fun toCoord(px: Long, py: Long, extent: Int, z: Int, x: Int, y: Int): GeographicCoordinate {
    val n = (1 shl z).toDouble()
    val lon = (x + px.toDouble() / extent) / n * 360.0 - 180.0
    val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + py.toDouble() / extent) / n))))
    return GeographicCoordinate(lat, lon)
  }

  private fun geometry(g: List<Long>, extent: Int, z: Int, x: Int, y: Int): List<List<GeographicCoordinate>> {
    val lines = mutableListOf<MutableList<GeographicCoordinate>>()
    var cx = 0L
    var cy = 0L
    var i = 0
    while (i < g.size) {
      val cmd = g[i].toInt()
      i++
      val id = cmd and 7
      val count = cmd ushr 3
      when (id) {
        1, 2 -> for (k in 0 until count) {
          if (i + 1 >= g.size) return lines
          cx += zigzag(g[i])
          cy += zigzag(g[i + 1])
          i += 2
          val c = toCoord(cx, cy, extent, z, x, y)
          if (id == 1 || lines.isEmpty()) lines.add(mutableListOf(c)) else lines.last().add(c)
        }
        7 -> {}
        else -> return lines
      }
    }
    return lines
  }

  /** The tile of zoom [z] containing a point. */
  fun tileOf(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
    val n = 1 shl z
    val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    val r = Math.toRadians(lat)
    val y = ((1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
    return x to y
  }
}
