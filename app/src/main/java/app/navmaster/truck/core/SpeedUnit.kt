package app.navmaster.truck.core

/**
 * The unit speeds are shown and said in. Inside the app every speed and limit is km/h (the map,
 * the engine, SpeedRules, the speed cameras: data in miles per hour are converted when they are
 * read or built); only what the driver sees and hears goes through here, in the unit chosen in
 * Impostazioni › Guida › Velocità (km/h unless the driver asks for miles per hour).
 */
object SpeedUnit {
  const val KMH_PER_MPH = 1.609344

  /** The driver wants miles per hour. */
  val mph: Boolean
    get() = runCatching { app.navmaster.truck.AppGraph.settings.settings.value.speedMph }.getOrDefault(false)

  /** [kmh] in the unit to show: the same number, or miles per hour rounded to the unit (48 km/h → 30). */
  fun show(kmh: Int, inMph: Boolean = mph): Int = if (inMph) Math.round(kmh / KMH_PER_MPH).toInt() else kmh

  /** km/h from a number in miles per hour (30 mph → 48). */
  fun fromMph(mph: Int): Int = Math.round(mph * KMH_PER_MPH).toInt()

  /** The unit written beside a number: "km/h" or "mph". */
  fun label(inMph: Boolean = mph): String = if (inMph) "mph" else "km/h"

  /** A limit as the voice says it: "80" in km/h (as always), "50 miglia orarie" in miles per hour. */
  fun spoken(kmh: Int, inMph: Boolean = mph): String = if (inMph) "${show(kmh, true)} miglia orarie" else "$kmh"
}
