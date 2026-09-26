package app.navmaster.truck.live

import app.navmaster.truck.core.Geo
import app.navmaster.truck.data.RouteMatcher
import uniffi.ferrostar.GeographicCoordinate

/**
 * What can happen on the road right now. [ttlMin] is how long a report from a driver stays valid
 * after its last confirmation; [enforcement] marks the police checks that some countries forbid
 * to announce (Germany, Switzerland) or allow only as a "control zone" (France).
 */
enum class LiveKind(val label: String, val icon: String, val ttlMin: Int, val color: Long, val enforcement: Boolean = false) {
  POLICE("Polizia", "👮", 60, 0xFF1E6FD9, true),
  MOBILE_CAMERA("Autovelox mobile", "📷", 120, 0xFF1E6FD9, true),
  TRUCK_CHECK("Controllo mezzi pesanti", "⚖", 120, 0xFF1E6FD9, true),
  ACCIDENT("Incidente", "💥", 120, 0xFFE03131),
  JAM("Coda", "🚗", 45, 0xFFE03131),
  CLOSED("Strada chiusa", "⛔", 360, 0xFFE03131),
  TRUCK_BAN("Divieto per camion", "🚛", 720, 0xFFE03131),
  ROADWORKS("Lavori", "🚧", 480, 0xFFFFB300),
  HAZARD("Pericolo sulla strada", "⚠", 90, 0xFFFFB300),
  BROKEN_VEHICLE("Veicolo fermo", "🚨", 60, 0xFFFFB300),
  WEATHER("Nebbia, neve o ghiaccio", "🌫", 180, 0xFFFFB300);

  companion object {
    fun of(name: String?): LiveKind? = entries.firstOrNull { it.name == name }
  }
}

/**
 * One event: from an official feed (traffic information of the road operators, TomTom, HERE) or
 * reported by a NavMaster driver. [line] is the stretch concerned, in the direction of travel when
 * the source gives it (a queue, a closure); [lat]/[lon] its first point.
 */
data class LiveEvent(
    val id: String,
    val source: String,
    val kind: LiveKind,
    val title: String,
    val detail: String? = null,
    val lat: Double,
    val lon: Double,
    val line: List<GeographicCoordinate> = emptyList(),
    val timeMs: Long,
    val delayS: Int = 0,
    val official: Boolean,
    val confirms: Int = 0,
    val denies: Int = 0,
    val mine: Boolean = false,
    val headingDeg: Double? = null,
    /** The stretch concerns both directions (or the source does not say which): no order check. */
    val bothWays: Boolean = false,
) {
  val ageMin: Long
    get() = ((System.currentTimeMillis() - timeMs) / 60_000).coerceAtLeast(0)
}

/** An event that lies on the route being driven, with where. */
data class RouteLiveEvent(val e: LiveEvent, val startM: Double, val endM: Double)

/** Which events are on the route, in its direction. */
object LiveMatch {
  fun onRoute(events: List<LiveEvent>, m: RouteMatcher): List<RouteLiveEvent> =
      events.mapNotNull { e -> match(e, m) }.sortedBy { it.startM }

  private fun match(e: LiveEvent, m: RouteMatcher): RouteLiveEvent? {
    val tol = if (e.official) 60.0 else 45.0
    if (e.line.size >= 2) {
      // a stretch: its points on the route, in the same order as the route (the other carriageway
      // of a motorway has the points in the opposite order)
      val hits = e.line.mapNotNull { p -> m.nearest(p)?.takeIf { it.first <= tol }?.second }
      if (hits.isEmpty()) return null
      if (!e.bothWays && hits.size >= 2 && hits.first() > hits.last() + 60) return null
      return RouteLiveEvent(e, hits.min(), hits.max().coerceAtLeast(hits.min()))
    }
    val (d, along) = m.nearest(GeographicCoordinate(e.lat, e.lon)) ?: return null
    if (d > tol) return null
    // a report made while driving has the direction of the driver: not the other carriageway
    val h = e.headingDeg
    if (h != null) {
      val a = Geo.pointAt(m.route, m.cum, (along - 20).coerceAtLeast(0.0))
      val b = Geo.pointAt(m.route, m.cum, along + 20)
      if (Geo.dist(a, b) > 5 && kotlin.math.abs(Geo.angleDiff(Geo.bearing(a, b), h)) > 70) return null
    }
    return RouteLiveEvent(e, along, along)
  }
}

/**
 * Countries where the police checks may not be announced, and where only a "control zone" may.
 * The driver chose to see them everywhere (Settings › Traffic and reports): then nothing is hidden.
 */
object LiveRules {
  val ENFORCEMENT_BANNED = setOf("DE", "CH")
  val ENFORCEMENT_ZONE_ONLY = setOf("FR")

  private fun everywhere() = app.navmaster.truck.AppGraph.settings.settings.value.enforcementEverywhere

  fun allowed(kind: LiveKind, country: String?): Boolean =
      !kind.enforcement || everywhere() || country?.uppercase() !in ENFORCEMENT_BANNED

  /** What the driver sees for an event in this country. */
  fun label(kind: LiveKind, country: String?): String =
      if (kind.enforcement && !everywhere() && country?.uppercase() in ENFORCEMENT_ZONE_ONLY) "Zona di controllo" else kind.label
}
