package app.navmaster.truck.live

import java.io.Reader
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import uniffi.ferrostar.GeographicCoordinate

/**
 * Reads the traffic situations of a DATEX II publication, version 2 or 3 (the European standard of
 * the national access points: Spain DGT, NDW Netherlands, Bison Futé France, Flanders, Luxembourg
 * CITA...). Streaming: a file of some megabytes is read without keeping it in memory, and only the
 * situations inside [keep] (the boxes of the road ahead) are kept.
 *
 * Every situation record gives: its type (Accident, AbnormalTraffic, MaintenanceWorks...), the
 * detailed types, validity, the road, a comment and its coordinates (points, from/to, gml posList).
 * Records without coordinates (AlertC or OpenLR only) are left out.
 */
object Datex {
  /** Projection of the coordinates of a feed: WGS84, or Belgian Lambert 72 (Flanders). */
  enum class Crs { WGS84, LAMBERT72 }

  class Rec {
    var type = ""
    var id = ""
    val values = HashMap<String, MutableList<String>>()
    val pts = mutableListOf<GeographicCoordinate>()
    var from: GeographicCoordinate? = null
    var to: GeographicCoordinate? = null
    var bearing: Double? = null
    var comment: String? = null
    var road: String? = null
    var start: Long? = null
    var end: Long? = null
    var ended = false
    var bothWays = false

    fun v(name: String): List<String> = values[name] ?: emptyList()

    fun has(name: String, value: String) = values[name]?.any { it.equals(value, true) } == true
  }

  private fun time(s: String?): Long? = s?.trim()?.let { t ->
    runCatching { OffsetDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull()
  }

  private fun local(n: String) = n.substringAfter(':')

  /** The events of a DATEX II publication. [source] is shown to the driver ("DGT"). */
  fun parse(reader: Reader, source: String, idPrefix: String, keep: List<GeoBox>?, crs: Crs = Crs.WGS84,
            now: Long = System.currentTimeMillis()): List<LiveEvent> {
    val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
    p.setInput(reader)
    val out = mutableListOf<LiveEvent>()
    var rec: Rec? = null
    val stack = ArrayList<String>()
    var lat: Double? = null
    var lon: Double? = null
    var lineCrs = crs
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
      when (ev) {
        XmlPullParser.START_TAG -> {
          val name = local(p.name)
          stack += name
          if (name == "situationRecord") {
            rec = Rec().also { r ->
              for (i in 0 until p.attributeCount) {
                val an = p.getAttributeName(i)
                if (an == "xsi:type" || an.endsWith(":type") || an == "type") r.type = local(p.getAttributeValue(i))
                if (an == "id") r.id = p.getAttributeValue(i)
              }
            }
          } else if (name == "gmlLineString" || name == "posList") {
            val srs = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it) == "srsName" }?.let { p.getAttributeValue(it) }
            if (srs != null) lineCrs = if ("31370" in srs) Crs.LAMBERT72 else if ("4326" in srs || "84" in srs) Crs.WGS84 else crs
          }
        }
        XmlPullParser.TEXT -> {
          val r = rec
          val name = stack.lastOrNull()
          val text = p.text?.trim()
          if (r != null && name != null && !text.isNullOrEmpty()) {
            when {
              name == "latitude" -> lat = text.toDoubleOrNull()
              name == "longitude" -> lon = text.toDoubleOrNull()
              name == "posList" -> {
                val n = text.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
                for (i in 0 until n.size / 2) point(n[2 * i], n[2 * i + 1], lineCrs, posList = true)?.let { r.pts += it }
              }
              name == "bearing" -> r.bearing = text.toDoubleOrNull()
              name == "overallStartTime" -> r.start = time(text)
              name == "overallEndTime" -> r.end = time(text)
              name == "end" || name == "cancel" -> if (text == "true") r.ended = true
              name == "validityStatus" -> if (text == "suspended") r.ended = true
              name == "roadName" || name == "roadNumber" -> if (r.road == null) r.road = text
              name == "value" && "generalPublicComment" in stack -> if (r.comment == null) r.comment = text
              name == "tpegDirection" || name == "tpegDirectionRoad" || name == "alertCAffectedDirection" || name == "alertCDirectionCoded" ->
                if (text.equals("unknown", true) || text.startsWith("both", true)) r.bothWays = true
              name.endsWith("Type") || name == "genericSituationRecordName" || name == "severity" || name == "vehicleType" ->
                r.values.getOrPut(name) { mutableListOf() } += text
            }
            if (lat != null && lon != null) {
              point(lat!!, lon!!, crs, posList = false)?.let { c ->
                when {
                  "from" in stack -> r.from = r.from ?: c
                  "to" in stack -> r.to = r.to ?: c
                  else -> r.pts += c
                }
              }
              lat = null
              lon = null
            }
          }
        }
        XmlPullParser.END_TAG -> {
          val name = local(p.name)
          if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
          if (name == "situationRecord") {
            rec?.let { r -> event(r, source, idPrefix, now)?.let { e -> if (keep == null || keep.any { inBox(e, it) }) out += e } }
            rec = null
            lineCrs = crs
          }
        }
      }
      ev = p.next()
    }
    // one event per kind and place (a situation often has two records at the same point)
    return out.distinctBy { "${it.kind}:${"%.3f".format(java.util.Locale.ROOT, it.lat)},${"%.3f".format(java.util.Locale.ROOT, it.lon)}" }
  }

  fun inBox(e: LiveEvent, b: GeoBox): Boolean {
    fun inside(la: Double, lo: Double) = la in b.s..b.n && lo in b.w..b.e
    return inside(e.lat, e.lon) || e.line.any { inside(it.lat, it.lng) }
  }

  /** WGS84 from the numbers of a coordinate. A posList in WGS84 is "lat lon"; in Lambert 72 "x y". */
  private fun point(a: Double, b: Double, crs: Crs, posList: Boolean): GeographicCoordinate? = when (crs) {
    Crs.WGS84 -> if (a in -90.0..90.0 && b in -180.0..180.0) GeographicCoordinate(a, b) else null
    // the point of a Flemish record has the northing in <latitude> and the easting in <longitude>
    Crs.LAMBERT72 -> if (posList) Lambert72.toWgs84(a, b) else Lambert72.toWgs84(b, a)
  }

  /** What a record means for the driver; null for records that are only management or advice. */
  fun event(r: Rec, source: String, idPrefix: String, now: Long): LiveEvent? {
    if (r.ended) return null
    if (r.end != null && r.end!! < now) return null
    // planned for later (more than half an hour from now): not yet on the road
    if (r.start != null && r.start!! > now + 30 * 60_000L) return null
    val kind = kindOf(r) ?: return null
    val line = when {
      r.from != null && r.to != null -> listOf(r.from!!, r.to!!)
      r.pts.size >= 2 -> r.pts.toList()
      else -> emptyList()
    }
    val first = r.pts.firstOrNull() ?: r.from ?: r.to ?: return null
    return LiveEvent(
        id = "$idPrefix:${r.id.ifBlank { "${first.lat},${first.lng}" }}",
        source = source, kind = kind,
        title = kind.label + (r.road?.let { " · $it" } ?: ""),
        detail = r.comment,
        lat = first.lat, lon = first.lng, line = line,
        timeMs = r.start ?: now, official = true,
        headingDeg = r.bearing?.takeIf { line.isEmpty() && !r.bothWays },
        bothWays = r.bothWays,
    )
  }

  private val HEAVY = setOf("lorry", "heavyvehicle", "heavygoodsvehicle", "articulatedvehicle", "vehiclewithtrailer", "tanker")

  fun kindOf(r: Rec): LiveKind? {
    val cause = r.v("causeType").firstOrNull()?.lowercase() ?: ""
    val all = r.values.values.flatten().map { it.lowercase() }
    fun any(vararg s: String) = all.any { v -> s.any { v.contains(it.lowercase()) } }
    val vehicles = r.v("vehicleType").map { it.lowercase() }
    val heavyOnly = vehicles.isNotEmpty() && vehicles.all { it in HEAVY || it == "bus" } && vehicles.any { it in HEAVY }
    return when (r.type) {
      "Accident" -> LiveKind.ACCIDENT
      "AbnormalTraffic" -> if (any("freeFlow")) null else LiveKind.JAM
      "VehicleObstruction" -> if (any("wrongCarriageway", "wrongWay")) LiveKind.HAZARD else LiveKind.BROKEN_VEHICLE
      "GeneralObstruction", "AnimalPresenceObstruction", "InfrastructureDamageObstruction", "EnvironmentalObstruction",
      "NonWeatherRelatedRoadConditions" -> if (any("flooding", "snow", "ice", "fog")) LiveKind.WEATHER else LiveKind.HAZARD
      "PoorEnvironmentConditions", "WeatherRelatedRoadConditions" -> LiveKind.WEATHER
      "MaintenanceWorks", "ConstructionWorks", "Roadworks" -> LiveKind.ROADWORKS
      "RoadOrCarriagewayOrLaneManagement" -> when {
        heavyOnly -> LiveKind.TRUCK_BAN
        any("roadClosed", "carriagewayClosures", "closedPermanently") -> LiveKind.CLOSED
        cause == "accident" -> LiveKind.ACCIDENT
        cause == "roadmaintenance" || any("roadworks", "maintenance") -> LiveKind.ROADWORKS
        cause == "abnormaltraffic" -> LiveKind.JAM
        cause.contains("obstruction") -> LiveKind.HAZARD
        cause.contains("environment") || cause.contains("weather") -> LiveKind.WEATHER
        any("laneClosures", "narrowLanes", "singleAlternateLineTraffic", "contraflow", "doNotUseSpecifiedLanes") -> LiveKind.ROADWORKS
        else -> null
      }
      "GenericSituationRecord" -> when {
        cause == "accident" -> LiveKind.ACCIDENT
        cause == "roadmaintenance" -> LiveKind.ROADWORKS
        cause == "abnormaltraffic" -> LiveKind.JAM
        cause.contains("environment") && any("flooding", "snow", "ice", "fog", "weather") -> LiveKind.WEATHER
        cause.contains("obstruction") || cause.contains("environment") -> LiveKind.HAZARD
        cause.contains("poorenvironment") || cause.contains("weather") -> LiveKind.WEATHER
        else -> null
      }
      else -> if (cause == "accident") LiveKind.ACCIDENT else null
    }
  }
}

/**
 * Belgian Lambert 72 (EPSG:31370) to WGS84: inverse Lambert conformal conic on the International
 * 1924 ellipsoid, then the BD72 → WGS84 Helmert shift (the parameters PROJ uses for EPSG:31370).
 */
object Lambert72 {
  private const val A = 6378388.0
  private const val F = 1 / 297.0
  private val E2 = 2 * F - F * F
  private val E = sqrt(E2)
  private const val LAT1 = 51.16666723333333
  private const val LAT2 = 49.8333339
  private const val LAT0 = 90.0
  private const val LON0 = 4.367486666666666
  private const val X0 = 150000.013
  private const val Y0 = 5400088.438

  private fun rad(d: Double) = Math.toRadians(d)
  private fun m(phi: Double) = cos(phi) / sqrt(1 - E2 * sin(phi).pow(2))
  private fun t(phi: Double) = tan(Math.PI / 4 - phi / 2) / ((1 - E * sin(phi)) / (1 + E * sin(phi))).pow(E / 2)

  private val n: Double
  private val fF: Double
  private val rho0: Double

  init {
    val p1 = rad(LAT1)
    val p2 = rad(LAT2)
    n = (ln(m(p1)) - ln(m(p2))) / (ln(t(p1)) - ln(t(p2)))
    fF = m(p1) / (n * t(p1).pow(n))
    rho0 = A * fF * t(rad(LAT0)).pow(n)
  }

  fun toWgs84(x: Double, y: Double): GeographicCoordinate? {
    if (x !in 0.0..400_000.0 || y !in 0.0..400_000.0) return null
    val dx = x - X0
    val dy = rho0 - (y - Y0)
    val rho = sqrt(dx * dx + dy * dy) * (if (n < 0) -1 else 1)
    val theta = kotlin.math.atan2(dx, dy)
    val tt = (rho / (A * fF)).pow(1 / n)
    var phi = Math.PI / 2 - 2 * atan(tt)
    repeat(8) {
      val es = E * sin(phi)
      phi = Math.PI / 2 - 2 * atan(tt * ((1 - es) / (1 + es)).pow(E / 2))
    }
    val lam = theta / n + rad(LON0)
    // BD72 geodetic -> geocentric -> WGS84 (position vector 7 parameters) -> geodetic
    val nu = A / sqrt(1 - E2 * sin(phi).pow(2))
    val X = nu * cos(phi) * cos(lam)
    val Y = nu * cos(phi) * sin(lam)
    val Z = nu * (1 - E2) * sin(phi)
    val sec = Math.PI / (180 * 3600)
    val rx = 0.3366 * sec
    val ry = -0.457 * sec
    val rz = 1.8422 * sec
    val s = 1 + (-1.2747e-6)
    val X2 = -106.8686 + s * (X - rz * Y + ry * Z)
    val Y2 = 52.2978 + s * (rz * X + Y - rx * Z)
    val Z2 = -103.7239 + s * (-ry * X + rx * Y + Z)
    val a = 6378137.0
    val f = 1 / 298.257223563
    val e2 = 2 * f - f * f
    val lon = kotlin.math.atan2(Y2, X2)
    val pp = sqrt(X2 * X2 + Y2 * Y2)
    var lat = kotlin.math.atan2(Z2, pp * (1 - e2))
    repeat(6) {
      val nn = a / sqrt(1 - e2 * sin(lat).pow(2))
      lat = kotlin.math.atan2(Z2 + e2 * nn * sin(lat), pp)
    }
    return GeographicCoordinate(Math.toDegrees(lat), Math.toDegrees(lon))
  }
}
