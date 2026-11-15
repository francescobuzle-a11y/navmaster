package app.navmaster.truck.ui

import android.util.Log
import app.navmaster.truck.routing.RouteAnalysis
import app.navmaster.truck.settings.Settings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The driving camera, as TomTom GO draws it on the drivers' tablet (measured on 2026-11-15 from
 * its own route replay, see the report in "Claude outputs"):
 *  - an even view at every speed: tilted about 65° (68° across the screen), close (map zoom about
 *    18.6), the vehicle low on the screen (83% of its height upright, 73% across), the horizon
 *    visible;
 *  - at a roundabout, from about 120 m before it until just after the exit, the view rises over
 *    the roundabout (about 43°, zoom 17.6) and the map stops turning with the vehicle: it keeps the
 *    direction it had arriving, the vehicle goes round inside it; then it eases back;
 *  - here also at motorway junctions (exits, forks, slip roads), and if wanted at turns in town:
 *    the same kind of rise, the map still following the vehicle's direction.
 * Every value is in the settings (Mappa e vista).
 */
internal object DriveCamera {
  private const val TAG = "NmCamera"

  /** Where the vehicle sits on the screen, from the top, as TomTom GO puts it. */
  const val VEHICLE_UPRIGHT = 0.832
  const val VEHICLE_ACROSS = 0.729

  /** The map's own limit of tilt, unless the tablet's map allows more (see [allowTilt]). */
  const val MAP_MAX_TILT = 60.0

  /** Seconds to ease into and out of the view of a roundabout or a junction. */
  const val EASE_S = 1.4

  /**
   * A stretch of the route where the view changes: from [fromM] to [toM] metres along it, a
   * roundabout (the map keeps its direction) or a junction, with the view's tilt and zoom.
   */
  data class Window(val fromM: Double, val toM: Double, val roundabout: Boolean, val tilt: Double, val zoom: Double)

  private val NOT_A_JUNCTION = setOf("DEPART", "ARRIVE", "ROUNDABOUT", "ROTARY", "ROUNDABOUT_TURN", "EXIT_ROUNDABOUT", "EXIT_ROTARY",
      "NOTIFICATION", "NEW_NAME", "CONTINUE", "MERGE")
  private val MOTORWAY_JUNCTION = setOf("OFF_RAMP", "ON_RAMP", "FORK")
  private val FAST = setOf("motorway", "trunk")

  /** The roundabouts of the route: stretches of its roundabout roads, in order (metres along it). */
  fun roundabouts(a: RouteAnalysis): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    for (e in a.edges.sortedBy { it.startM }) {
      if (!e.roundabout) continue
      val last = out.lastOrNull()
      if (last != null && e.startM - last.second < 8.0) out[out.size - 1] = last.first to max(last.second, e.endM)
      else out += e.startM to e.endM
    }
    return out
  }

  /** The stretches of the route where the view changes, from the settings [s]. */
  fun windows(a: RouteAnalysis, s: Settings): List<Window> {
    val out = ArrayList<Window>()
    if (s.roundaboutView) {
      for ((from, to) in roundabouts(a)) {
        out += Window(from - s.roundaboutViewFromM, to + 25.0, true, s.roundaboutTilt.toDouble(), s.roundaboutZoom)
      }
    }
    if (s.junctionZoomView || s.junctionZoomInTown) {
      for ((m, type) in a.maneuvers) {
        if (type in NOT_A_JUNCTION) continue
        // not the instructions of a roundabout: its own view covers them
        if (out.any { it.roundabout && m >= it.fromM - 30 && m <= it.toM + 30 }) continue
        val before = a.edgeAt((m - 20).coerceAtLeast(0.0))
        val after = a.edgeAt((m + 20).coerceAtMost(a.length))
        val motorway = type in MOTORWAY_JUNCTION || listOfNotNull(before, after).any { it.isRamp || it.roadClass in FAST }
        if (motorway && s.junctionZoomView) {
          out += Window(m - s.junctionZoomFromM, m + 60.0, false, s.junctionTilt.toDouble(), s.junctionZoom)
        } else if (!motorway && s.junctionZoomInTown) {
          out += Window(m - s.junctionZoomTownFromM, m + 30.0, false, s.junctionTilt.toDouble(), s.junctionZoom)
        }
      }
    }
    return out.sortedBy { it.fromM }
  }

  /** The window the vehicle is in at [alongM] metres, a roundabout first. */
  fun windowAt(ws: List<Window>, alongM: Double): Window? {
    var found: Window? = null
    for (w in ws) {
      if (w.fromM > alongM) break
      if (alongM <= w.toM) {
        if (w.roundabout) return w
        if (found == null) found = w
      }
    }
    return found
  }

  /** The driving view's tilt and zoom for the screen's orientation, with the tilt the map allows. */
  fun plainView(s: Settings, landscape: Boolean, maxTilt: Double): Pair<Double, Double> {
    val wanted = s.driveTilt + (if (landscape) 3.0 else 0.0)
    val tilt = min(wanted, maxTilt)
    // a lower tilt than TomTom's: a little less close keeps the road of the first 100 m where
    // TomTom has it (fitted on its pictures: 0.39 of zoom upright, 0.76 across for 5° and 8.5°)
    val zoom = s.driveZoom - (if (landscape) 0.2 else 0.0) - max(0.0, wanted - tilt) * (if (landscape) 0.09 else 0.075)
    return tilt to zoom
  }

  /** How far right of the road's middle the vehicle drives, metres: the middle of a lane of the driving side. */
  const val LANE_SHIFT_M = 2.0

  /** [l] moved [metres] to the right of its course (negative: to the left); as it is without a course. */
  fun laneShift(l: uniffi.ferrostar.UserLocation, metres: Double): uniffi.ferrostar.UserLocation {
    if (metres == 0.0) return l
    val course = l.courseOverGround?.degrees?.toDouble() ?: return l
    val b = Math.toRadians(course + 90.0)
    val lat = l.coordinates.lat + metres * kotlin.math.cos(b) / 110_540.0
    val lng = l.coordinates.lng + metres * kotlin.math.sin(b) / (111_320.0 * kotlin.math.cos(Math.toRadians(l.coordinates.lat)))
    return l.copy(coordinates = uniffi.ferrostar.GeographicCoordinate(lat, lng))
  }

  /** The angle from [a] to [b] by the short way, eased by [t] (degrees). */
  fun lerpAngle(a: Double, b: Double, t: Double): Double {
    val d = ((b - a + 540.0) % 360.0) - 180.0
    return ((a + d * t) % 360.0 + 360.0) % 360.0
  }

  /** Smooth start and end of an easing (0 … 1). */
  fun ease(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

  /**
   * Asks the tablet's map to allow a tilt up to [wanted] degrees (the map's library stops at 60°
   * in its own checks, the map underneath may allow more): the tilt it allows now. Reaches the map
   * through the library's own objects; anything unexpected: 60°.
   */
  fun allowTilt(cameraState: Any, wanted: Double): Double = runCatching {
    val adapter = cameraState.javaClass.getMethod("getMap\$maplibre_compose").invoke(cameraState) ?: return MAP_MAX_TILT
    val mapField = adapter.javaClass.getDeclaredField("map").apply { isAccessible = true }
    val map = mapField.get(adapter) ?: return MAP_MAX_TILT
    val nativeField = map.javaClass.getDeclaredField("nativeMapView").apply { isAccessible = true }
    val native = nativeField.get(map) ?: return MAP_MAX_TILT
    val nativeMap = Class.forName("org.maplibre.android.maps.NativeMap")
    val current = (nativeMap.getMethod("getMaxPitch").apply { isAccessible = true }.invoke(native) as Double)
    if (abs(current - wanted) > 0.1) {
      nativeMap.getMethod("setMaxPitch", Double::class.javaPrimitiveType).apply { isAccessible = true }.invoke(native, wanted)
    }
    (nativeMap.getMethod("getMaxPitch").invoke(native) as Double).also {
      if (abs(it - current) > 0.1) Log.i(TAG, "inclinazione massima della mappa: $current → $it (chiesti $wanted)")
    }
  }.onFailure { Log.w(TAG, "inclinazione oltre 60°: ${it.javaClass.simpleName} ${it.message}") }.getOrDefault(MAP_MAX_TILT)

  /**
   * The camera is driven here now (a roundabout or a junction): the map still follows the vehicle,
   * though Ferrostar's own tracking is off (no "centre" button meanwhile).
   */
  val driving = androidx.compose.runtime.mutableStateOf(false)

  /** The camera of a roundabout or a junction while the vehicle is in its window (state of one drive). */
  class Driver {
    /** The camera is driven here (Ferrostar's own tracking is off meanwhile). */
    var active: Boolean
      get() = driving.value
      set(v) { if (driving.value != v) driving.value = v }
    /** 0: the plain view; 1: the view of the window. */
    var blend = 0.0
    var window: Window? = null
    /** The map's direction held through a roundabout. */
    var heldBearing = Double.NaN
    var lastNanos = 0L

    fun reset() {
      active = false; blend = 0.0; window = null; heldBearing = Double.NaN; lastNanos = 0L
    }
  }
}
