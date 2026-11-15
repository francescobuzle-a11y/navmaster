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
  /**
   * The kind of road the legal limits are given for (a road inside a town is not one of them):
   * RURAL a main road outside towns (primary), MINOR the other roads outside towns (secondary,
   * tertiary, unclassified). Only France tells the two apart; elsewhere both are "outside towns".
   */
  enum class Road { MOTORWAY, EXPRESS, RURAL, MINOR }

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
    "primary" -> if (signedKmh != null && signedKmh > 50) Road.RURAL else null
    "secondary", "tertiary", "unclassified" -> if (signedKmh != null && signedKmh > 50) Road.MINOR else null
    else -> null
  }

  /**
   * The legal limit (km/h) of [v] in [country] on a [road] at [hour] (0–23, local time), null where
   * there is none or it is not known.
   */
  fun legal(v: VehicleProfile, country: String?, road: Road, hour: Int = java.time.LocalTime.now().hour): Int? {
    // goods vehicles over 3.5 t only: cars, vans, campers, buses have no table here
    val goods = v.maxWeightT > 3.5 && v.type in setOf(VehicleType.AUTOARTICOLATO, VehicleType.AUTOTRENO, VehicleType.MOTRICE)
    if (!goods) return null
    val over12 = v.maxWeightT > 12.0
    val over75OrTrailer = v.maxWeightT > 7.5 || v.trailer
    val articulated = v.type == VehicleType.AUTOARTICOLATO || v.type == VehicleType.AUTOTRENO || v.trailer
    val outside = road == Road.RURAL || road == Road.MINOR
    return when (country?.uppercase()) {
      // Codice della Strada art. 142: over 12 t, and the articulated ones: 80 on motorways, 70 on
      // the other roads outside towns; 3.5 to 12 t: 100 on motorways, 80 on the others
      "IT" -> if (road == Road.MOTORWAY) (if (over12) 80 else 100) else (if (over12) 70 else 80)
      // StVO §20: over 3.5 t: 80 on motorways, 70 on the roads outside towns; over 7.5 t at night
      // (22–5) 60 on every road, motorways too, unless signs say otherwise (StVO §42(8), IG-L)
      "AT" -> if (v.maxWeightT > 7.5 && (hour >= 22 || hour < 5)) 60 else if (road == Road.MOTORWAY) 80 else 70
      // StVO §3: over 3.5 t: 80 on motorways and expressways; outside towns 60 over 7.5 t or with a trailer
      "DE" -> if (outside && over75OrTrailer) 60 else 80
      "CH", "CZ", "NL" -> 80
      // Code de la route R413-8: over 3.5 t 90 on motorways; on dual carriageways 90 up to 12 t, 80
      // over; on the other roads 80, lowered to 60 for articulated vehicles and those with a trailer
      // over 12 t except on priority roads (the sign of the priority road: taken as the main roads,
      // primary; secondary, tertiary and unclassified ones get the 60)
      "FR" -> when (road) {
        Road.MOTORWAY -> 90
        Road.EXPRESS -> if (over12) 80 else 90
        Road.RURAL -> 80
        Road.MINOR -> if (over12 && articulated) 60 else 80
      }
      "ES" -> if (outside) 80 else 90
      "PL" -> if (outside) 70 else 80
      else -> null
    }
  }

  /**
   * The limit to respect for [v] on a road of class [roadClass] in [country] where the signs say
   * [signedKmh] (null: the map has none). Null when no limit is known at all (the vehicle's own
   * maximum alone is not a limit to show). On a slip road ([ramp]) with no signed number nothing
   * is said: the law's number for the motorway would be too high there. [hour]: the local hour
   * (Austria's night limit for lorries).
   */
  fun at(v: VehicleProfile, country: String?, roadClass: String?, signedKmh: Int?, ramp: Boolean = false,
         hour: Int = java.time.LocalTime.now().hour): Limit? {
    val signed = signedKmh?.takeIf { it > 0 }
    val road = roadOf(roadClass, signed)
    val legal = if (ramp && signed == null) null else road?.let { legal(v, country, it, hour) }
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
