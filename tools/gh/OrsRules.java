import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMReaderUtility;
import nmors.ConditionalOSMTagInspector;
import nmors.ConditionalParser;
import nmors.DateRangeParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * openrouteservice's rules for lorries ("driving-hgv") and cars ("driving-car"), as its graph is
 * built from the OSM tags (openrouteservice on GitHub, 10/2026, LGPL; its GraphHopper 4.16 fork,
 * Apache 2.0). Every rule below is the one of the class named in its comment, in the same order,
 * with the same quirks:
 *
 * - access (HeavyVehicleFlagEncoder / CarFlagEncoder.getAccess, VehicleFlagEncoder): the access
 *   tags are read in openrouteservice's order, for lorries "hgv" first (GraphHopper adds the
 *   restrictions of the transportation mode, then VehicleFlagEncoder the car ones again);
 *   "a;b" lists are split (ORS-GH ReaderElement); date ranges of the ":conditional" tags are
 *   evaluated on the day the graph is built, the conditions of the time of day are left out
 *   (ORS-GH ConditionalOSMTagInspector, copied in tools/gh/nmors);
 * - speed (VehicleFlagEncoder.handleWayTags, speed_limits/heavyvehicle.json and car.json): the
 *   speed of the road class, the signed limit (90% of it), zone limits, tracks, surfaces, the
 *   acceleration on short roads, residential streets with many nodes, roundabouts; ferries as
 *   FerrySpeedCalculator (ORS-GH) with the length of the whole ferry way;
 * - preference of lorries (HeavyVehicleFlagEncoder.collect, PriorityCode 0..7);
 * - barriers (ORSAbstractFlagEncoder.isBarrier with block_barriers and without block_fords);
 * - vehicle types (VehicleAccessParser for "hgv"), dangerous goods (HazmatAccessParser), signed
 *   limits (ORS-GH OSMMaxHeightParser… and OSMValueExtractor, the limits of the nodes inside a way
 *   given to the whole way as ORSOSMReader.applyNodeTagsToWay), destination/private access
 *   (ORS-GH OSMRoadAccessParser, used by LimitedAccessWeighting), service roads (road_class),
 *   tolls (ORS-GH OSMTollParser), ferries (WayTypeParser);
 * - turn restrictions (ORS-GH OSMReader.createTurnRelations / OSMTurnRelation): only via a node,
 *   "restriction" before "restriction:*", "except".
 */
final class OrsRules {
  private OrsRules() {
  }

  // ---------------------------------------------------------------- tags (ORS-GH ReaderElement)

  /** getTagValues: the value split at ";" (not trimmed), none if the tag is missing. */
  static String[] values(ReaderElement e, String key) {
    Object o = e.getTags().get(key);
    if (o == null) return new String[0];
    String v = o.toString();
    return v.contains(";") ? v.split(";") : new String[] {v};
  }

  /** hasTag(key, Collection): one of the ";" values of the tag is in [values]. */
  static boolean has(ReaderElement e, String key, Collection<String> values) {
    for (String v : values(e, key)) if (values.contains(v)) return true;
    return false;
  }

  /** hasTag(List, Collection): one of [keys] has one of [values]. */
  static boolean has(ReaderElement e, List<String> keys, Collection<String> values) {
    for (String k : keys) if (has(e, k, values)) return true;
    return false;
  }

  /** hasTag(key, String...): the whole value is one of [values] (no splitting). */
  static boolean is(ReaderElement e, String key, String... values) {
    Object o = e.getTags().get(key);
    if (o == null) return false;
    if (values.length == 0) return true;
    for (String v : values) if (v.equals(o)) return true;
    return false;
  }

  static String tag(ReaderElement e, String key) {
    Object o = e.getTags().get(key);
    return o == null ? null : o.toString();
  }

  /** getFirstPriorityTagValues: the values of the first of [keys] the element has. */
  static String[] firstValues(ReaderElement e, List<String> keys) {
    for (String k : keys) if (e.getTags().containsKey(k)) return values(e, k);
    return new String[0];
  }

  static boolean empty(String s) {
    return s == null || s.trim().isEmpty();
  }

  static Set<String> set(String... v) {
    return new HashSet<>(Arrays.asList(v));
  }

  static Map<String, Integer> map(Object... kv) {
    Map<String, Integer> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
    return m;
  }

  // ---------------------------------------------------------------- values of the vehicles

  /** GraphHopper's restrictions for the mode (hgv / car), then VehicleFlagEncoder's car ones. */
  static final List<String> HGV_RESTRICTIONS = Arrays.asList("hgv", "motor_vehicle", "vehicle", "access",
      "motorcar", "motor_vehicle", "vehicle", "access");
  static final List<String> CAR_RESTRICTIONS = Arrays.asList("motorcar", "motor_vehicle", "vehicle", "access",
      "motorcar", "motor_vehicle", "vehicle", "access");
  static final Set<String> HGV_RESTRICTED = set("private", "no", "restricted", "military");
  static final Set<String> CAR_RESTRICTED = set("private", "no", "restricted", "military", "agricultural", "forestry",
      "delivery", "emergency");
  static final Set<String> HGV_INTENDED = set("yes", "permissive", "destination", "permit", "designated", "agricultural",
      "forestry", "delivery", "bus", "hgv", "goods");
  static final Set<String> CAR_INTENDED = set("yes", "permissive", "destination", "permit");
  /** HeavyVehicleFlagEncoder.hgvAccess. */
  static final List<String> HGV_ACCESS_KEYS = Arrays.asList("hgv", "goods", "bus", "agricultural", "forestry", "delivery");
  static final Set<String> FERRIES = set("shuttle_train", "ferry");
  static final Set<String> ONEWAYS = set("yes", "true", "1", "-1");
  static final Set<String> HGV_BLOCK_BY_DEFAULT = set("bollard", "stile", "turnstile", "cycle_barrier", "motorcycle_barrier", "block");
  static final Set<String> CAR_BLOCK_BY_DEFAULT = set("bollard", "stile", "turnstile", "cycle_barrier", "motorcycle_barrier", "block",
      "bus_trap", "sump_buster");
  static final Set<String> PASS_BY_DEFAULT = set("gate", "lift_gate", "kissing_gate", "swing_gate");

  // speed_limits/heavyvehicle.json and car.json ("default", "surface", "tracktype", "max_speeds")
  static final Map<String, Integer> HGV_DEFAULT = map("motorway", 85, "motorway_link", 50, "motorroad", 80, "trunk", 80,
      "trunk_link", 50, "primary", 60, "primary_link", 50, "secondary", 60, "secondary_link", 50, "tertiary", 50,
      "tertiary_link", 40, "unclassified", 30, "residential", 30, "living_street", 10, "service", 20, "road", 20, "track", 15);
  static final Map<String, Integer> CAR_DEFAULT = map("motorway", 100, "motorway_link", 60, "motorroad", 90, "trunk", 85,
      "trunk_link", 60, "primary", 65, "primary_link", 50, "secondary", 60, "secondary_link", 50, "tertiary", 50,
      "tertiary_link", 40, "unclassified", 30, "residential", 30, "living_street", 10, "service", 20, "road", 20, "track", 15);
  static final Map<String, Integer> HGV_SURFACE = surface(60, 60, 50);
  static final Map<String, Integer> CAR_SURFACE = surface(80, 80, 60);
  static final Map<String, Integer> TRACKTYPE = map("grade1", 40, "grade2", 30, "grade3", 20, "grade4", 15, "grade5", 10);
  static final Map<String, Integer> HGV_ZONES = zones(
      "AT:urban", 50, "AT:rural", 80, "AT:trunk", 80, "AT:motorway", 80, "CH:urban", 50, "CH:rural", 80, "CH:trunk", 80,
      "CH:motorway", 80, "CZ:urban", 50, "CZ:rural", 90, "CZ:trunk", 80, "CZ:motorway", 80, "DK:urban", 50, "DK:rural", 80,
      "DK:motorway", 80, "DE:living_street", 7, "DE:urban", 50, "DE:rural", 80, "DE:motorway", 80, "FI:urban", 50,
      "FI:rural", 80, "FI:trunk", 80, "FI:motorway", 80, "FR:urban", 50, "FR:rural", 80, "FR:trunk", 80, "FR:motorway", 80,
      "GR:urban", 50, "GR:rural", 80, "GR:trunk", 80, "GR:motorway", 80, "HU:urban", 50, "HU:rural", 80, "HU:trunk", 80,
      "HU:motorway", 80, "IT:urban", 50, "IT:rural", 80, "IT:trunk", 80, "IT:motorway", 80, "JP:national", 60,
      "JP:motorway", 80, "PL:living_street", 20, "PL:urban", 50, "PL:rural", 80, "PL:motorway", 80, "RO:urban", 50,
      "RO:rural", 80, "RO:trunk", 80, "RO:motorway", 80, "RU:living_street", 20, "RU:rural", 80, "RU:urban", 60,
      "RU:motorway", 80, "SK:urban", 50, "SK:rural", 80, "SK:trunk", 80, "SK:motorway", 80, "SI:urban", 50, "SI:rural", 80,
      "SI:trunk", 80, "SI:motorway", 80, "ES:urban", 50, "ES:rural", 80, "ES:trunk", 80, "ES:motorway", 80, "SE:urban", 50,
      "SE:rural", 70, "SE:trunk", 80, "SE:motorway", 80, "GB:nsl_single", 80, "GB:nsl_dual", 96, "GB:motorway", 96,
      "UA:urban", 60, "UA:rural", 80, "UA:trunk", 80, "UA:motorway", 80, "UZ:living_street", 30, "UZ:urban", 70,
      "UZ:rural", 90, "UZ:motorway", 90);
  static final Map<String, Integer> CAR_ZONES = zones(
      "AT:urban", 50, "AT:rural", 100, "AT:trunk", 100, "AT:motorway", 130, "CH:urban", 50, "CH:rural", 80, "CH:trunk", 100,
      "CH:motorway", 120, "CZ:urban", 50, "CZ:rural", 90, "CZ:trunk", 80, "CZ:motorway", 80, "DK:urban", 50, "DK:rural", 80,
      "DK:motorway", 130, "DE:living_street", 7, "DE:urban", 50, "DE:rural", 100, "DE:motorway", 130, "FI:urban", 50,
      "FI:rural", 80, "FI:trunk", 100, "FI:motorway", 120, "FR:urban", 50, "FR:rural", 80, "FR:trunk", 110,
      "FR:motorway", 130, "GR:urban", 50, "GR:rural", 90, "GR:trunk", 110, "GR:motorway", 130, "HU:urban", 50,
      "HU:rural", 90, "HU:trunk", 110, "HU:motorway", 130, "IT:urban", 50, "IT:rural", 90, "IT:trunk", 110,
      "IT:motorway", 130, "JP:national", 60, "JP:motorway", 100, "PL:living_street", 20, "PL:urban", 50, "PL:rural", 90,
      "PL:motorway", 140, "RO:urban", 50, "RO:rural", 90, "RO:trunk", 100, "RO:motorway", 130, "RU:living_street", 20,
      "RU:rural", 90, "RU:urban", 60, "RU:motorway", 110, "SK:urban", 50, "SK:rural", 90, "SK:trunk", 90,
      "SK:motorway", 90, "SI:urban", 50, "SI:rural", 90, "SI:trunk", 110, "SI:motorway", 130, "ES:urban", 50,
      "ES:rural", 90, "ES:trunk", 100, "ES:motorway", 120, "SE:urban", 50, "SE:rural", 70, "SE:trunk", 90,
      "SE:motorway", 110, "GB:nsl_single", 96, "GB:nsl_dual", 112, "GB:motorway", 112, "UA:urban", 60, "UA:rural", 90,
      "UA:trunk", 110, "UA:motorway", 130, "UZ:living_street", 30, "UZ:urban", 70, "UZ:rural", 100, "UZ:motorway", 110);

  /** maxPossibleSpeed (HeavyVehicleFlagEncoder 90, VehicleFlagEncoder 140); minPossibleSpeed = speed factor 5. */
  static final double HGV_MAX = 90, CAR_MAX = 140, MIN_SPEED = 5, SPEED_FACTOR = 5, MAX_SPEED_FACTOR = 0.9;
  /** MaxSpeed.UNLIMITED_SIGN_SPEED ("none"). */
  static final double UNLIMITED = 150;

  // HeavyVehicleAttributes
  static final int GOODS = 1, HGV = 2, BUS = 4, AGRICULTURE = 8, FORESTRY = 16, DELIVERY = 32;
  static final int ANY = GOODS | HGV | BUS | AGRICULTURE | FORESTRY | DELIVERY;

  // PriorityCode
  static final int WORST = 0, AVOID_AT_ALL_COSTS = 1, REACH_DEST = 2, AVOID_IF_POSSIBLE = 3, UNCHANGED = 4, PREFER = 5,
      VERY_NICE = 6, BEST = 7;

  static Map<String, Integer> surface(int cement, int compacted, int fineGravel) {
    return map("asphalt", -1, "concrete", -1, "concrete:plates", -1, "concrete:lanes", -1, "paved", -1, "cement", cement,
        "compacted", compacted, "fine_gravel", fineGravel, "paving_stones", 40, "metal", 40, "bricks", 40, "grass", 30, "wood", 30,
        "sett", 30, "grass_paver", 30, "gravel", 30, "unpaved", 30, "ground", 30, "dirt", 30, "pebblestone", 30, "tartan", 30,
        "cobblestone", 20, "clay", 20, "earth", 15, "stone", 15, "rocky", 15, "sand", 15, "mud", 10, "unknown", 30);
  }

  /** SpeedLimitHandler: the keys of "max_speeds" are lower-cased. */
  static Map<String, Integer> zones(Object... kv) {
    Map<String, Integer> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put(((String) kv[i]).toLowerCase(), (Integer) kv[i + 1]);
    return m;
  }

  static boolean valid(double s) {
    return !Double.isNaN(s);
  }

  /** ORS-GH OSMValueExtractor.stringToKmh: a maxspeed in km/h (whole numbers only), NaN if not one. */
  static double kmh(String str) {
    if (str == null || str.isEmpty()) return Double.NaN;
    if ("none".equals(str)) return UNLIMITED;
    if (str.endsWith(":rural") || str.endsWith(":trunk")) return 80;
    if (str.endsWith(":urban")) return 50;
    if (str.equals("walk") || str.endsWith(":living_street")) return 6;
    int mp = str.indexOf("mp"), knot = str.indexOf("knots"), km = str.indexOf("km"), kph = str.indexOf("kph");
    double factor;
    if (mp > 0) {
      str = str.substring(0, mp).trim();
      factor = 1.609344;
    } else if (knot > 0) {
      str = str.substring(0, knot).trim();
      factor = 1.852;
    } else {
      if (km > 0) str = str.substring(0, km).trim();
      else if (kph > 0) str = str.substring(0, kph).trim();
      factor = 1;
    }
    double value;
    try {
      value = Integer.parseInt(str) * factor;
    } catch (Exception ex) {
      return Double.NaN;
    }
    return value <= 0 ? Double.NaN : value;
  }

  /** VehicleFlagEncoder.getHighway: "motorroad" for a motorroad=yes road that is not a motorway. */
  static String highway(ReaderWay way) {
    String h = tag(way, "highway");
    if (!empty(h) && is(way, "motorroad", "yes") && !h.equals("motorway") && !h.equals("motorway_link")) h = "motorroad";
    return h;
  }

  /** AbstractFlagEncoder.getMaxSpeed: the lowest of maxspeed, maxspeed:forward, maxspeed:backward. */
  static double genericMaxSpeed(ReaderWay way) {
    double m = kmh(tag(way, "maxspeed"));
    double f = kmh(tag(way, "maxspeed:forward"));
    if (valid(f) && (!valid(m) || f < m)) m = f;
    double b = kmh(tag(way, "maxspeed:backward"));
    if (valid(b) && (!valid(m) || b < m)) m = b;
    return m;
  }

  /** HeavyVehicleFlagEncoder.getMaxSpeed: maxspeed:hgv…, else maxspeed if not above the lorry's speed of the road. */
  static double hgvMaxSpeed(ReaderWay way) {
    double m = kmh(tag(way, "maxspeed:hgv"));
    double f = kmh(tag(way, "maxspeed:hgv:forward"));
    if (valid(f) && (!valid(m) || f < m)) m = f;
    double b = kmh(tag(way, "maxspeed:hgv:backward"));
    if (valid(b) && (!valid(m) || b < m)) m = b;
    if (!valid(m)) {
      m = genericMaxSpeed(way);
      if (valid(m)) {
        String h = highway(way);
        if (!empty(h)) {
          Integer d = HGV_DEFAULT.get(h);
          // defaultSpeed < maxSpeed (a missing default reads as NaN in openrouteservice: never smaller)
          if (d != null && d < m) m = Double.NaN;
        }
      }
    }
    return m;
  }

  /** VehicleFlagEncoder.getTrackGradeLevel. */
  static int trackGrade(String grade) {
    if (grade == null || grade.equals("unknown")) return 0;
    switch (grade) {
      case "grade1": return 1;
      case "grade2": return 2;
      case "grade3": return 3;
      case "grade4": return 4;
      case "grade5": return 5;
      default:
        int max = 0;
        for (String v : grade.split("[;-]")) {
          int p;
          try {
            p = Integer.parseInt(v.replace("grade", "").trim());
          } catch (NumberFormatException e) {
            return 0;
          }
          if (p > max) max = p;
        }
        return Math.min(5, max);
    }
  }

  // ---------------------------------------------------------------- access

  static final int SKIP = 0, WAY = 1, FERRY = 2;

  /**
   * The access of one vehicle, with its own conditional-tag inspector (it keeps the state of the
   * last way, as openrouteservice's): the date of today for date ranges, times of day left out.
   */
  static final class Vehicle {
    final boolean hgv;
    final List<String> restrictions;
    final Set<String> restricted, intended;
    final Map<String, Integer> defaults, surfaces, zones;
    final double maxPossible;
    final int maxTrackGrade;
    private final ConditionalOSMTagInspector inspector;

    Vehicle(boolean hgv, DateRangeParser today) {
      this.hgv = hgv;
      restrictions = hgv ? HGV_RESTRICTIONS : CAR_RESTRICTIONS;
      restricted = hgv ? HGV_RESTRICTED : CAR_RESTRICTED;
      intended = hgv ? HGV_INTENDED : CAR_INTENDED;
      defaults = hgv ? HGV_DEFAULT : CAR_DEFAULT;
      surfaces = hgv ? HGV_SURFACE : CAR_SURFACE;
      zones = hgv ? HGV_ZONES : CAR_ZONES;
      maxPossible = hgv ? HGV_MAX : CAR_MAX;
      // HeavyVehicleFlagEncoder: maximum_grade_level 1; VehicleFlagEncoder: 3
      maxTrackGrade = hgv ? 1 : 3;
      inspector = new ConditionalOSMTagInspector(Collections.singletonList(today), restrictions, restricted, intended, false);
      // added last = checked first (ORS-GH AbstractFlagEncoder.init)
      inspector.addValueParser(ConditionalParser.createDateTimeParser());
    }

    /** HeavyVehicleFlagEncoder.getAccess / CarFlagEncoder.getAccess. */
    int access(ReaderWay way) {
      String h = tag(way, "highway");
      String[] values = firstValues(way, restrictions);
      if (h == null) {
        if (has(way, "route", FERRIES)) {
          for (String v : values) {
            if (restricted.contains(v)) return SKIP;
            if (intended.contains(v)) return FERRY;
          }
          if (values.length == 0 && !way.getTags().containsKey("foot") && !way.getTags().containsKey("bicycle")) return FERRY;
        }
        return SKIP;
      }
      if ("track".equals(h)) {
        String tt = tag(way, "tracktype");
        if (hgv) {
          if (trackGrade(tt) > maxTrackGrade) return SKIP;
        } else if (tt != null && trackGrade(tt) > maxTrackGrade) return SKIP;
      }
      if (!defaults.containsKey(h)) return SKIP;
      if (is(way, "impassable", "yes") || is(way, "status", "impassable") || is(way, "smoothness", "impassable")) return SKIP;
      for (String v : values) {
        if (v.isEmpty()) continue;
        if (hgv) {
          if (restricted.contains(v) && !inspector.isRestrictedWayConditionallyPermitted(way)) return SKIP;
        } else if (restricted.contains(v)) {
          return conditionalAccess(way, true);
        }
        if (intended.contains(v)) return WAY;
      }
      // block_fords: false (not checked)
      if (hgv) {
        boolean carsAllowed = has(way, restrictions, intended);
        if (has(way, restrictions, restricted) && !carsAllowed && !has(way, HGV_ACCESS_KEYS, intended)) return SKIP;
      }
      String maxwidth = tag(way, "maxwidth");
      if (maxwidth != null) {
        try {
          if (Double.parseDouble(maxwidth) < 2.0) return SKIP;
        } catch (Exception ignored) {
          // not a plain number
        }
      }
      if (hgv) return inspector.isPermittedWayConditionallyRestricted(way) ? SKIP : WAY;
      return conditionalAccess(way, false);
    }

    /**
     * ORS-GH AbstractFlagEncoder.getConditionalAccess: a condition that can only be told at the time
     * of the trip (PERMITTED / RESTRICTED) leaves the road open (no conditional access stored).
     */
    private int conditionalAccess(ReaderWay way, boolean permissive) {
      boolean access = permissive ? inspector.isRestrictedWayConditionallyPermitted(way)
          : !inspector.isPermittedWayConditionallyRestricted(way);
      if (inspector.hasLazyEvaluatedConditions()) return WAY;
      return access ? WAY : SKIP;
    }

    /** One way per direction: [forward, backward] (VehicleFlagEncoder.handleWayTags). */
    boolean[] directions(ReaderWay way, int access) {
      if (access == FERRY) return new boolean[] {true, true};
      boolean roundabout = is(way, "junction", "roundabout");
      boolean oneway = has(way, "oneway", ONEWAYS) || way.getTags().containsKey("vehicle:backward")
          || way.getTags().containsKey("vehicle:forward") || way.getTags().containsKey("motor_vehicle:backward")
          || way.getTags().containsKey("motor_vehicle:forward");
      if (oneway || roundabout) {
        boolean backward = is(way, "oneway", "-1") || is(way, "vehicle:forward", "no") || is(way, "motor_vehicle:forward", "no");
        return new boolean[] {!backward, backward};
      }
      return new boolean[] {true, true};
    }

    /** ORSAbstractFlagEncoder.isBarrier (block_barriers: true, block_fords: false). */
    boolean barrier(ReaderNode node) {
      boolean blockByDefault = has(node, "barrier", hgv ? HGV_BLOCK_BY_DEFAULT : CAR_BLOCK_BY_DEFAULT);
      if (blockByDefault || has(node, "barrier", PASS_BY_DEFAULT)) {
        boolean locked = is(node, "locked", "yes");
        for (String res : restrictions) {
          if (!locked && has(node, res, intended)) return false;
          if (has(node, res, restricted)) return true;
        }
        return true;
      }
      return (is(node, "highway", "ford") || is(node, "ford", "yes")) && has(node, restrictions, restricted);
    }

    /** The speed of the road, km/h, before it is stored (VehicleFlagEncoder.handleWayTags). */
    double speed(ReaderWay way, int access) {
      double speed;
      if (access == FERRY) {
        speed = ferrySpeed(way);
      } else {
        String h = highway(way);
        speed = roadSpeed(way, h);
        // applyMaxSpeed
        double tagged = hgv ? hgvMaxSpeed(way) : genericMaxSpeed(way);
        if (valid(tagged)) speed = tagged * MAX_SPEED_FACTOR;
        // cars: the conditional maxspeed is never applied (ConditionalOSMSpeedInspector with the
        // time parser only: the value stays "speed @ (condition)", which is not a speed)
        // getSurfaceSpeed
        String surface = tag(way, "surface");
        if (surface != null) {
          Integer ss = surfaces.get(surface);
          int sv = ss == null ? -1 : ss;
          if (speed > sv && sv != -1) speed = sv;
        }
        Object est = way.getTags().get(ESTIMATED_DISTANCE);
        if (est instanceof Number) {
          double distance = ((Number) est).doubleValue();
          if ("residential".equals(h)) {
            speed = residentialPenalty(speed, way, distance);
          } else if (!"motorway".equals(h) && !"motorroad".equals(h)) {
            speed = new Acceleration().adjust(distance, speed);
          }
        }
        if (is(way, "junction", "roundabout")) {
          if (is(way, "highway", "mini_roundabout")) speed = Math.min(speed, 25);
          String lanes = tag(way, "lanes");
          if (lanes != null) {
            try {
              speed = Integer.parseInt(lanes) >= 2 ? Math.min(speed, 40) : Math.min(speed, 35);
            } catch (Exception ignored) {
              // "3; 2" and the like
            }
          }
        }
      }
      // setSpeed
      if (speed < MIN_SPEED) speed = MIN_SPEED;
      else if (speed > maxPossible) speed = maxPossible;
      return speed;
    }

    /** VehicleFlagEncoder.getSpeed. */
    private double roadSpeed(ReaderWay way, String h) {
      Integer s = defaults.get(h);
      double speed = s == null ? MIN_SPEED : s;
      double tagged = hgv ? hgvMaxSpeed(way) : genericMaxSpeed(way);
      int maxSpeed = valid(tagged) ? (int) Math.round(tagged) : 0;
      if (maxSpeed <= 0) maxSpeed = zoneSpeed(way);
      if (maxSpeed > 0) speed = maxSpeed;
      if ("track".equals(h)) {
        String tt = tag(way, "tracktype");
        if (!empty(tt)) {
          Integer t = TRACKTYPE.get(tt);
          if (t != null && t != -1) speed = t;
        }
      }
      return speed;
    }

    /** SpeedLimitHandler.getMaxSpeed: zone:maxspeed / zone:traffic, only without a maxspeed tag. */
    private int zoneSpeed(ReaderWay way) {
      if (way.getTags().containsKey("maxspeed")) return -1;
      String key = tag(way, "zone:maxspeed");
      if (key == null) key = tag(way, "zone:traffic");
      if (key == null) return -1;
      Integer r = zones.get(key.toLowerCase());
      return r == null ? -1 : r;
    }

    /** ORS-GH FerrySpeedCalculator(speedFactor 5, maxPossibleSpeed, 30, 20, 5). */
    private double ferrySpeed(ReaderWay way) {
      long duration = 0;
      try {
        duration = OSMReaderUtility.parseDuration(tag(way, "duration"));
      } catch (Exception ignored) {
        // no duration
      }
      double hours = duration / 60d / 60d;
      Object o = way.getTags().get(EXACT_DISTANCE);
      Number length = o instanceof Number ? (Number) o : null;
      if (hours > 0 && length != null) {
        double tripSpeed = length.doubleValue() / 1000 / hours / 1.4;
        if (tripSpeed > 0.01d) {
          if (tripSpeed > maxPossible) return maxPossible;
          if (Math.round(tripSpeed) < SPEED_FACTOR / 2) return SPEED_FACTOR / 2;
          return Math.round(tripSpeed);
        }
        hours = 0;
      }
      if (hours == 0) {
        if (length != null && length.doubleValue() <= 300) return SPEED_FACTOR / 2;
        if (way.getTags().containsKey("maxspeed")) {
          double average = kmh(tag(way, "maxspeed")) / 1.4;
          if (average < SPEED_FACTOR / 2) return SPEED_FACTOR / 2;
          if (average > maxPossible) return maxPossible;
          if (Double.isFinite(average)) return average;
        }
        return 5;
      }
      return hours > 1 ? 30 : 20;
    }
  }

  /** VehicleFlagEncoder.addResedentialPenalty. */
  static double residentialPenalty(double speed, ReaderWay way, double distance) {
    if (speed == 0) return 0;
    double interim = distance;
    int interimNodes = way.getNodes().size() - 2;
    if (interimNodes > 0) interim = distance / (interimNodes + 1);
    return interim < 100 ? speed * 0.5 : speed;
  }

  /** VehicleFlagEncoder.adjustSpeedForAcceleration (10 s to 100 km/h, slower on slow roads). */
  static final class Acceleration {
    private double modifier = 0;

    double secsTo100() {
      return 10 + modifier * 10;
    }

    double acc() {
      return 100.0 / secsTo100();
    }

    double adjust(double distance, double max) {
      if (max >= 80.0) return max;
      if (distance <= 0) return max;
      double n = Math.max(max, 20.0);
      n = (n - 20.0) / (80.0 - 20.0);
      modifier = Math.pow(0.01, n);
      double timeToMax = max / acc();
      double accDistance = distanceIn(max, timeToMax);
      double atMax = distance - accDistance * 2;
      if (atMax < 0) {
        double duration = durationFor(max, distance / 2);
        if (duration == 0) duration = 1;
        return mpsToKmh(distance / (duration * 2));
      }
      double timeAtMax = atMax / kmhToMps(max);
      return mpsToKmh(distance / (timeToMax * 2 + timeAtMax));
    }

    private double durationFor(double max, double dist) {
      double secs = 0, travelled = 0, v = 0;
      while (v < max && travelled < dist) {
        v += acc();
        secs += 1;
        travelled += kmhToMps(v);
      }
      double rest = dist - travelled;
      if (rest > 0) secs += rest / kmhToMps(max);
      return secs;
    }

    private double distanceIn(double max, double duration) {
      double secs = 0, travelled = 0, v = 0;
      while (v < max && secs < duration) {
        v += acc();
        secs += 1;
        travelled += kmhToMps(v);
      }
      double rest = duration - secs;
      if (rest > 0) travelled += rest * kmhToMps(max);
      return travelled;
    }

    private static double kmhToMps(double v) {
      return v * 1000 / 3600;
    }

    private static double mpsToKmh(double v) {
      return 3600 * v / 1000;
    }
  }

  // ---------------------------------------------------------------- preference of lorries

  /** HeavyVehicleFlagEncoder.handlePriority / collect: 0 (worst) .. 7 (best). */
  static int hgvPriority(ReaderWay way) {
    TreeMap<Double, Integer> m = new TreeMap<>();
    if (is(way, "hgv", "designated") || (is(way, "access", "designated") && (is(way, "goods", "yes")
        || is(way, "hgv", "yes") || is(way, "bus", "yes") || is(way, "agricultural", "yes") || is(way, "forestry", "yes")))) {
      m.put(100d, BEST);
    } else {
      String h = highway(way);
      double ms = hgvMaxSpeed(way);
      if (!empty(h)) {
        switch (h) {
          case "motorway": case "motorway_link": case "motorroad": case "trunk": case "trunk_link":
            m.put(100d, VERY_NICE);
            break;
          case "primary": case "primary_link": case "secondary": case "secondary_link":
            m.put(100d, PREFER);
            break;
          case "tertiary": case "tertiary_link":
            m.put(100d, UNCHANGED);
            break;
          case "residential": case "service": case "road": case "unclassified":
            if (valid(ms) && ms <= 30) m.put(120d, REACH_DEST);
            else m.put(100d, AVOID_IF_POSSIBLE);
            break;
          case "living_street":
            m.put(100d, AVOID_AT_ALL_COSTS);
            break;
          case "track":
            m.put(100d, WORST);
            break;
          default:
            m.put(40d, AVOID_IF_POSSIBLE);
        }
      } else {
        m.put(100d, UNCHANGED);
      }
      if (valid(ms)) {
        if (ms <= 40) m.put(110d, AVOID_IF_POSSIBLE);
        else if (ms <= 50) m.put(110d, UNCHANGED);
      }
    }
    return m.lastEntry().getValue();
  }

  // ---------------------------------------------------------------- vehicle types, dangerous goods

  private static final List<String> MOTOR_RESTRICTIONS = Arrays.asList("motorcar", "motor_vehicle", "vehicle", "access");
  private static final Set<String> MOTOR_RESTRICTED = set("private", "no", "restricted", "military");
  private static final Set<String> HGV_VALUES = set("hgv", "goods", "bus", "agricultural", "forestry", "delivery");

  static int type(String v) {
    switch (v.toLowerCase(Locale.ROOT)) {
      case "goods": return GOODS;
      case "hgv": return HGV;
      case "bus": return BUS;
      case "agricultural": return AGRICULTURE;
      case "forestry": return FORESTRY;
      case "delivery": return DELIVERY;
      default: return 0;
    }
  }

  /** VehicleAccessParser (vehicle type "hgv"): the road is closed to lorries. False without a highway tag. */
  static boolean hgvTypeBlocked(ReaderWay way) {
    return typeBlocked(way, HGV);
  }

  /** VehicleAccessParser (vehicle type "bus"). */
  static boolean busTypeBlocked(ReaderWay way) {
    return typeBlocked(way, BUS);
  }

  static boolean typeBlocked(ReaderWay way, int target) {
    if (!way.getTags().containsKey("highway")) return false;
    int blocked = 0;
    if (has(way, MOTOR_RESTRICTIONS, MOTOR_RESTRICTED)) blocked = ANY;
    if (has(way, MOTOR_RESTRICTIONS, HGV_VALUES)) {
      int allowed = 0;
      for (String k : MOTOR_RESTRICTIONS)
        for (String v : values(way, k)) if (HGV_VALUES.contains(v)) allowed |= type(v);
      blocked = ANY & ~allowed;
    }
    for (Map.Entry<String, Object> e : way.getTags().entrySet()) {
      String key = e.getKey();
      if (!HGV_VALUES.contains(key)) continue;
      String value = String.valueOf(e.getValue());
      String vehicle = HGV_VALUES.contains(value) ? value : key;
      String acc = vehicle.equals(value) || value.equals("yes") || value.equals("designated") ? "yes"
          : value.equals("no") || value.equals("private") ? "no" : null;
      blocked = flags(blocked, vehicle, acc);
      if (vehicle.equals(value)) blocked = flags(blocked, key, "no");
    }
    return (blocked & target) == target;
  }

  static int flags(int blocked, String vehicle, String access) {
    int f = type(vehicle);
    if ("no".equals(access)) return blocked | f;
    if ("yes".equals(access)) return blocked & ~f;
    return blocked;
  }

  private static final Pattern HAZMAT = Pattern.compile("^hazmat(:[B-E])?$");

  /** HazmatAccessParser: hazmat=no or hazmat:B..E=no closes the road to dangerous goods. */
  static boolean hazmatBlocked(ReaderWay way) {
    for (Map.Entry<String, Object> e : way.getTags().entrySet())
      if (HAZMAT.matcher(e.getKey()).matches() && "no".equals(String.valueOf(e.getValue()))) return true;
    return false;
  }

  // ---------------------------------------------------------------- other values of the roads

  /** RoadAccess ordinals of ORS-GH: YES, DESTINATION, CUSTOMERS, DELIVERY, FORESTRY, AGRICULTURAL, PRIVATE, OTHER, NO. */
  static final List<String> ROAD_ACCESS = Arrays.asList("yes", "destination", "customers", "delivery", "forestry",
      "agricultural", "private", "other", "no");
  static final int RA_DESTINATION = 1, RA_CUSTOMERS = 2, RA_PRIVATE = 6;

  /** ORS-GH OSMRoadAccessParser (car restrictions, no country rules): the most restrictive value. */
  static int roadAccess(ReaderWay way) {
    int best = 0;
    for (String r : MOTOR_RESTRICTIONS) {
      Object o = way.getTags().get(r);
      String v = o == null ? "yes" : o.toString();
      // RoadAccess.find: the enum name, anything else (permissive, "a;b"…) is "yes"
      int i = ROAD_ACCESS.indexOf(v.toLowerCase(Locale.ROOT));
      if (i < 0) i = 0;
      if (i > best) best = i;
    }
    return best;
  }

  /** ORS-GH OSMTollParser: 0 none, 1 all vehicles, 2 lorries (toll:hgv / N2 / N3). */
  static int toll(ReaderWay way) {
    if (is(way, "toll", "yes")) return 1;
    for (String k : new String[] {"toll:hgv", "toll:N2", "toll:N3"}) if (has(way, k, Collections.singletonList("yes"))) return 2;
    return 0;
  }

  /** WayTypeParser: FERRY. */
  static boolean ferry(ReaderWay way) {
    return is(way, "route", "ferry", "shuttle_train");
  }

  /** road_class SERVICE (ORS-GH OSMRoadClassParser, not for ferries). */
  static boolean service(ReaderWay way, boolean ferry) {
    return !ferry && "service".equals(tag(way, "highway"));
  }

  // ---------------------------------------------------------------- signed limits (ORS-GH)

  static final List<String> HEIGHT_TAGS = Arrays.asList("maxheight", "maxheight:physical");
  static final List<String> WIDTH_TAGS = Arrays.asList("maxwidth", "maxwidth:physical", "width");
  static final List<String> LENGTH_TAGS = Collections.singletonList("maxlength");
  static final List<String> WEIGHT_TAGS = Arrays.asList("maxweight", "maxgcweight");
  static final List<String> AXLE_TAGS = Collections.singletonList("maxaxleload");
  /** ORSOSMReader.nodeTagsToStore: limits of a node inside a way, given to the whole way. */
  static final Set<String> NODE_LIMIT_TAGS = set("maxheight", "maxweight", "maxweight:hgv", "maxwidth", "maxlength",
      "maxlength:hgv", "maxaxleload");

  /** getFirstPriorityTag: the value of the first of [keys] the way has, "" if none. */
  static String firstTag(ReaderWay way, List<String> keys) {
    for (String k : keys) if (way.getTags().containsKey(k)) return tag(way, k);
    return "";
  }

  private static final Pattern TON = Pattern.compile("tons?"), MGW = Pattern.compile("mgw"), WSPACE = Pattern.compile("\\s"),
      METER = Pattern.compile("meters?|mtrs?|mt|m\\."), INCH = Pattern.compile("\"|''"), FEET = Pattern.compile("'|feet"),
      APPROX = Pattern.compile("~|approx");

  static boolean invalid(String value) {
    value = value.toLowerCase(Locale.ROOT);
    return value.isEmpty() || value.startsWith("default") || value.equals("none") || value.equals("unknown")
        || value.contains("unrestricted") || value.startsWith("〜")
        || value.contains("narrow") || value.equals("unsigned") || value.equals("fixme") || value.equals("small")
        || value.contains(";") || value.contains(":") || value.contains("(")
        || value.contains(">") || value.contains("<") || value.contains("-")
        || value.contains(",");
  }

  /** OSMValueExtractor.stringToTons. */
  static double tons(String value) {
    value = TON.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("t");
    value = MGW.matcher(value).replaceAll("").trim();
    if (invalid(value)) return Double.NaN;
    double factor = 1;
    if (value.endsWith("st")) {
      value = value.substring(0, value.length() - 2);
      factor = 0.907194048807;
    } else if (value.endsWith("t")) {
      value = value.substring(0, value.length() - 1);
    } else if (value.endsWith("lbs")) {
      value = value.substring(0, value.length() - 3);
      factor = 0.00045359237;
    } else if (value.endsWith("kg")) {
      value = value.substring(0, value.length() - 2);
      factor = 0.001;
    }
    try {
      return Double.parseDouble(value) * factor;
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  /** OSMValueExtractor.stringToMeter. */
  static double meters(String value) {
    value = WSPACE.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    value = METER.matcher(value).replaceAll("m");
    value = INCH.matcher(value).replaceAll("in");
    value = FEET.matcher(value).replaceAll("ft");
    if (invalid(value)) return Double.NaN;
    double factor = 1, offset = 0;
    if (value.startsWith("~") || value.contains("approx")) {
      value = APPROX.matcher(value).replaceAll("").trim();
      factor = 0.8;
    }
    if (value.endsWith("in")) {
      int start = value.indexOf("ft");
      if (start < 0) start = 0;
      else start += 2;
      String inch = value.substring(start, value.length() - 2);
      value = value.substring(0, start);
      try {
        offset = Double.parseDouble(inch) * 0.0254;
      } catch (NumberFormatException e) {
        return Double.NaN;
      }
    }
    if (value.endsWith("ft")) {
      value = value.substring(0, value.length() - 2);
      factor *= 0.3048;
    } else if (value.endsWith("cm")) {
      value = value.substring(0, value.length() - 2);
      factor *= 0.01;
    } else if (value.endsWith("m")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.isEmpty()) return offset;
    try {
      return Double.parseDouble(value) * factor + offset;
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  // ---------------------------------------------------------------- turn restrictions

  /** OSMTurnRelation.Type: the values openrouteservice follows (NOT / ONLY), the others are left out. */
  static final Set<String> TURN_VALUES = set("no_left_turn", "no_right_turn", "no_straight_on", "no_u_turn", "no_entry",
      "only_right_turn", "only_left_turn", "only_straight_on");

  /**
   * The restriction value openrouteservice follows for a vehicle (its restrictions list), or null:
   * ORS-GH OSMReader.createTurnRelations + OSMTurnRelation.isVehicleTypeConcernedByTurnRestriction.
   */
  static String turnValue(Map<String, Object> tags, List<String> vehicleTypes) {
    List<String> except = new ArrayList<>();
    Object ex = tags.get("except");
    if (ex != null && !empty(ex.toString())) for (String t : ex.toString().split(";")) except.add(t.trim());
    if (!Collections.disjoint(vehicleTypes, except)) return null;
    Object r = tags.get("restriction");
    if (r != null) return TURN_VALUES.contains(r.toString()) ? r.toString() : null;
    for (Map.Entry<String, Object> e : tags.entrySet()) {
      if (!e.getKey().startsWith("restriction:")) continue;
      String type = e.getKey().replace("restriction:", "").trim();
      if (!vehicleTypes.contains(type)) continue;
      String v = String.valueOf(e.getValue());
      if (TURN_VALUES.contains(v)) return v;
    }
    return null;
  }

  // ---------------------------------------------------------------- artificial tags (ORSOSMReader)

  static final String ESTIMATED_DISTANCE = "estimated_way_distance";
  static final String EXACT_DISTANCE = "exact_distance";
}
