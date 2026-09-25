package app.navmaster.truck.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong
import uniffi.ferrostar.GeographicCoordinate

/** A point in metres on a local flat plane (x = east, y = north). */
data class XY(val x: Double, val y: Double) {
  operator fun plus(o: XY) = XY(x + o.x, y + o.y)

  operator fun minus(o: XY) = XY(x - o.x, y - o.y)

  operator fun times(k: Double) = XY(x * k, y * k)

  fun len(): Double = hypot(x, y)

  fun unit(): XY = len().let { if (it < 1e-9) XY(0.0, 0.0) else XY(x / it, y / it) }

  /** Left-hand normal. */
  fun normal(): XY = XY(-y, x)
}

/** Converts between coordinates and metres around a reference point (good for a few km). */
class LocalPlane(private val lat0: Double, private val lon0: Double) {
  private val kx = 111320.0 * cos(Math.toRadians(lat0))
  private val ky = 110540.0

  fun toXY(c: GeographicCoordinate) = XY((c.lng - lon0) * kx, (c.lat - lat0) * ky)

  fun toXY(lat: Double, lon: Double) = XY((lon - lon0) * kx, (lat - lat0) * ky)

  fun toCoord(p: XY) = GeographicCoordinate(lat0 + p.y / ky, lon0 + p.x / kx)
}

object Geo {
  const val CELL = 0.01

  fun cell(lat: Double, lon: Double): Long = floor(lat / CELL).toLong() * 100000L + floor(lon / CELL).toLong() + 50000L

  fun dist(a: GeographicCoordinate, b: GeographicCoordinate): Double = dist(a.lat, a.lng, b.lat, b.lng)

  fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val k = cos(Math.toRadians((lat1 + lat2) / 2))
    return hypot((lon2 - lon1) * 111320.0 * k, (lat2 - lat1) * 110540.0)
  }

  /** Compass bearing from a to b in degrees (0 = north, clockwise). */
  fun bearing(a: GeographicCoordinate, b: GeographicCoordinate): Double {
    val k = cos(Math.toRadians(a.lat))
    val d = Math.toDegrees(atan2((b.lng - a.lng) * k, b.lat - a.lat))
    return (d + 360) % 360
  }

  /** Signed smallest difference b - a in degrees (-180..180). */
  fun angleDiff(a: Double, b: Double): Double = ((b - a + 540) % 360) - 180

  fun cumulative(line: List<GeographicCoordinate>): DoubleArray {
    val cum = DoubleArray(line.size)
    for (i in 1 until line.size) cum[i] = cum[i - 1] + dist(line[i - 1], line[i])
    return cum
  }

  /** Distance from p to segment ab (metres) and the position t (0..1) of the closest point. */
  fun pointToSegment(p: GeographicCoordinate, a: GeographicCoordinate, b: GeographicCoordinate): Pair<Double, Double> {
    val k = cos(Math.toRadians(a.lat))
    val bx = (b.lng - a.lng) * 111320.0 * k
    val by = (b.lat - a.lat) * 110540.0
    val px = (p.lng - a.lng) * 111320.0 * k
    val py = (p.lat - a.lat) * 110540.0
    val len2 = bx * bx + by * by
    val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    return hypot(px - bx * t, py - by * t) to t
  }

  /** Point at distance [along] metres on the line. */
  fun pointAt(line: List<GeographicCoordinate>, cum: DoubleArray, along: Double): GeographicCoordinate {
    if (line.isEmpty()) return GeographicCoordinate(0.0, 0.0)
    if (along <= 0) return line.first()
    if (along >= cum.last()) return line.last()
    var lo = 0
    var hi = cum.size - 1
    while (hi - lo > 1) {
      val mid = (lo + hi) / 2
      if (cum[mid] <= along) lo = mid else hi = mid
    }
    val seg = cum[hi] - cum[lo]
    val t = if (seg <= 0) 0.0 else (along - cum[lo]) / seg
    val a = line[lo]
    val b = line[hi]
    return GeographicCoordinate(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
  }

  /** Part of the line between two distances along it. */
  fun slice(line: List<GeographicCoordinate>, cum: DoubleArray, from: Double, to: Double): List<GeographicCoordinate> {
    if (line.size < 2) return line
    val a = from.coerceIn(0.0, cum.last())
    val b = to.coerceIn(a, cum.last())
    val out = mutableListOf(pointAt(line, cum, a))
    for (i in line.indices) if (cum[i] > a && cum[i] < b) out += line[i]
    out += pointAt(line, cum, b)
    return out
  }

  /** Position along the line (metres) of the vertex nearest to p, searched from index [fromIdx]. */
  fun locate(line: List<GeographicCoordinate>, cum: DoubleArray, p: GeographicCoordinate, fromIdx: Int = 0): Pair<Double, Double> {
    var best = Double.MAX_VALUE
    var along = 0.0
    for (i in fromIdx.coerceAtLeast(0) until line.size - 1) {
      val (d, t) = pointToSegment(p, line[i], line[i + 1])
      if (d < best) {
        best = d
        along = cum[i] + (cum[i + 1] - cum[i]) * t
      }
    }
    return along to best
  }

  /** Ray casting on rings of [lon, lat]. */
  fun inPolygon(lat: Double, lon: Double, rings: List<List<List<Double>>>): Boolean {
    for (ring in rings) {
      var inside = false
      var j = ring.size - 1
      for (i in ring.indices) {
        val xi = ring[i][0]
        val yi = ring[i][1]
        val xj = ring[j][0]
        val yj = ring[j][1]
        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi) inside = !inside
        j = i
      }
      if (inside) return true
    }
    return false
  }

  /** Google encoded polyline with 6 decimals (what Valhalla expects). */
  fun encodePolyline6(points: List<GeographicCoordinate>): String {
    val sb = StringBuilder()
    var lastLat = 0L
    var lastLon = 0L
    for (p in points) {
      val lat = (p.lat * 1e6).roundToLong()
      val lon = (p.lng * 1e6).roundToLong()
      encodeValue(lat - lastLat, sb)
      encodeValue(lon - lastLon, sb)
      lastLat = lat
      lastLon = lon
    }
    return sb.toString()
  }

  private fun encodeValue(v: Long, sb: StringBuilder) {
    var value = if (v < 0) (v shl 1).inv() else v shl 1
    while (value >= 0x20) {
      sb.append(Char((0x20 or (value and 0x1f).toInt()) + 63))
      value = value shr 5
    }
    sb.append(Char((value + 63).toInt()))
  }

  fun decodePolyline6(encoded: String): List<GeographicCoordinate> {
    val out = ArrayList<GeographicCoordinate>()
    var index = 0
    var lat = 0L
    var lon = 0L
    while (index < encoded.length) {
      var result = 0L
      var shift = 0
      var b: Int
      do {
        b = encoded[index++].code - 63
        result = result or ((b and 0x1f).toLong() shl shift)
        shift += 5
      } while (b >= 0x20 && index < encoded.length)
      lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
      result = 0L
      shift = 0
      do {
        b = encoded[index++].code - 63
        result = result or ((b and 0x1f).toLong() shl shift)
        shift += 5
      } while (b >= 0x20 && index < encoded.length)
      lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
      out += GeographicCoordinate(lat / 1e6, lon / 1e6)
    }
    return out
  }

  /** A small square around a point, as Valhalla's exclude_polygons wants it ([lon, lat] ring). */
  fun squareAround(lat: Double, lon: Double, halfM: Double): List<List<Double>> {
    val dLat = halfM / 110540.0
    val dLon = halfM / (111320.0 * cos(Math.toRadians(lat)))
    return listOf(
        listOf(lon - dLon, lat - dLat),
        listOf(lon + dLon, lat - dLat),
        listOf(lon + dLon, lat + dLat),
        listOf(lon - dLon, lat + dLat),
        listOf(lon - dLon, lat - dLat),
    )
  }

  fun almostSame(a: Double, b: Double, rel: Double): Boolean = abs(a - b) <= rel * maxOf(abs(a), abs(b), 1.0)
}
