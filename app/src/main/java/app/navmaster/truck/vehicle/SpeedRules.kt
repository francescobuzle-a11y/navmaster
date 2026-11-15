package app.navmaster.truck.vehicle

/**
 * The speed to keep and respect: ONE rule for everything that shows or says a limit (the sign at the
 * side of the map, the speedometer, the trip bar, the speed cameras, the Tutor sections, the voice),
 * so that two places never give two numbers for the same road.
 *
 * Three limits apply to a vehicle on a road and the lowest is the one to respect:
 *  1. SIGNED: what the road's signs say (the map's maxspeed; for lorries and buses the map's
 *     maxspeed:hgv when it has one, which the route already carries);
 *  2. LEGAL: the limit of the vehicle's kind in that country on that kind of road, which no sign
 *     repeats (a lorry over 12 t in Italy: 80 on a motorway, 70 on the other roads outside towns,
 *     where the signs say 130 and 90);
 *  3. OWN: the maximum set in the vehicle's profile (a speed limiter, the speed the driver keeps).
 *
 * Only what is certain is applied. The legal limits below are for goods vehicles over 3.5 t, in the
 * countries listed; a country or a kind of vehicle that is not listed has no legal limit here and
 * the signed one stands. A road inside a town (signed 50 or less, or unknown) has none either: the
 * sign is the limit there. Check a country against its current law before adding it.
 */
object SpeedRules {
  /** The kind of road the legal limits are given for (a road inside a town is not one of them). */
  enum class Road { MOTORWAY, EXPRESS, RURAL }

  /** Where the limit to respect comes from. */
  enum class Why { SIGNED, LEGAL, OWN }

  /**
   * The limit to respect: [kmh], where it comes from, and the three limits it was the lowest of
   * (null where not known): what the signs say, what the law says for this vehicle, the vehicle's own.
   */
  data class Limit(val kmh: Int, val why: Why, val signed: Int?, val legal: Int?, val own: Int) {
    /** The signed limit when it is higher than the one to respect: the number to show beside it ("cartello 130"). */
    val signedAbove: Int? get() = signed?.takeIf { it > kmh }
  }

  /** The kind of road for the legal limits, or null for a road inside a town or one with no rule. */
  fun roadOf(roadClass: String?, signedKmh: Int?): Road? = when (roadClass) {
    "motorway" -> Road.MOTORWAY
    "trunk" -> Road.EXPRESS
    // outside towns a road is signed above 50; at 50 or less, or with no number, it may be a town street
    "primary", "secondary", "tertiary", "unclassified" -> if (signedKmh != null && signedKmh > 50) Road.RURAL else null
    else -> null
  }

  /** The legal limit (km/h) of [v] in [country] on a [road], null where there is none or it is not known. */
  fun legal(v: VehicleProfile, country: String?, road: Road): Int? {
    // goods vehicles over 3.5 t only: cars, vans, campers, buses have no table here
    val goods = v.maxWeightT > 3.5 && v.type in setOf(VehicleType.AUTOARTICOLATO, VehicleType.AUTOTRENO, VehicleType.MOTRICE)
    if (!goods) return null
    val over12 = v.maxWeightT > 12.0
    val over75OrTrailer = v.maxWeightT > 7.5 || v.trailer
    return when (country?.uppercase()) {
      // Codice della Strada art. 142: over 12 t, and the articulated ones: 80 on motorways, 70 on
      // the other roads outside towns; 3.5 to 12 t: 100 on motorways, 80 on the others
      "IT" -> if (road == Road.MOTORWAY) (if (over12) 80 else 100) else (if (over12) 70 else 80)
      // StVO §20: over 3.5 t: 80 on motorways, 70 on the roads outside towns
      "AT" -> if (road == Road.MOTORWAY) 80 else 70
      // StVO §3: over 3.5 t: 80 on motorways and expressways; outside towns 60 over 7.5 t or with a trailer
      "DE" -> if (road == Road.RURAL && over75OrTrailer) 60 else 80
      "CH", "CZ", "NL" -> 80
      "FR", "ES" -> if (road == Road.RURAL) 80 else 90
      "PL" -> if (road == Road.RURAL) 70 else 80
      else -> null
    }
  }

  /**
   * The limit to respect for [v] on a road of class [roadClass] in [country] where the signs say
   * [signedKmh] (null: the map has none). Null when no limit is known at all (the vehicle's own
   * maximum alone is not a limit to show). On a slip road ([ramp]) with no signed number nothing
   * is said: the law's number for the motorway would be too high there.
   */
  fun at(v: VehicleProfile, country: String?, roadClass: String?, signedKmh: Int?, ramp: Boolean = false): Limit? {
    val signed = signedKmh?.takeIf { it > 0 }
    val road = roadOf(roadClass, signed)
    val legal = if (ramp && signed == null) null else road?.let { legal(v, country, it) }
    val known = listOfNotNull(signed, legal)
    if (known.isEmpty()) return null
    val own = v.topSpeedKmh.takeIf { it > 0 } ?: Int.MAX_VALUE
    val kmh = minOf(known.min(), own)
    val why = when {
      signed != null && signed <= kmh -> Why.SIGNED
      legal != null && legal <= kmh -> Why.LEGAL
      else -> Why.OWN
    }
    return Limit(kmh, why, signed, legal, if (own == Int.MAX_VALUE) kmh else own)
  }
}
