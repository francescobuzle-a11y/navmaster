package app.navmaster.truck.routing.gh;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.HazmatTunnel;
import com.graphhopper.routing.ev.HazmatWater;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * openrouteservice's weighting of a road, as its routing engine computes it (ORSWeightingFactory),
 * on the values the graph was built with (tools/gh/NmImport.java, OrsRules.java):
 *
 * - "fastest": the time on the road (ORSFastestWeighting: length / speed, plus the heading penalty
 *   of 300 s on the edges leaving a point the wrong way), the speed limited to the vehicle's
 *   "maximum_speed" (MaximumSpeedCalculator; openrouteservice does not accept less than 80 km/h);
 * - "recommended" (lorries): the time multiplied by 2^((4 - preference) / 3), preference 0..7
 *   (ORSPriorityWeighting: motorways 0.63, main roads 0.79, small streets 1.26, living streets 2…);
 *   for cars openrouteservice has no preference: "recommended" is "fastest"; for lorries and buses
 *   "fastest" is "recommended" (ProfileTools.setWeightingMethod);
 * - "shortest": the length (ShortestWeighting);
 * - all of them multiplied by LimitedAccessWeighting's factors for motor vehicles: roads with
 *   destination or private access ×10, customers ×1.5, service roads ×1.2;
 * - closed: roads the vehicle may not use (access, one-ways), and for the trip
 *   (HeavyVehicleEdgeFilter with vehicle type "hgv" or "bus", AvoidFeaturesEdgeFilter,
 *   AvoidAreasEdgeFilter): roads closed to lorries (or buses), a signed limit below the vehicle's measure
 *   (height, width, length, weight, axle load: "limit > 0 and limit < measure"), dangerous goods
 *   where forbidden (hazmat / hazmat:B..E = no), tolls and ferries when avoided, the zones to avoid
 *   (the road inside or crossing them);
 * - turn restrictions (DefaultTurnCostProvider) and u-turns: infinite in openrouteservice, 3600 s
 *   here (GhEngine.U_TURN_COSTS: GraphHopper 11's landmarks are not exact with infinite costs; a
 *   u-turn costs an hour, so it is taken only where no other way is less than an hour longer).
 *
 * Besides openrouteservice, for safety (online openrouteservice cannot do them): the measures of a
 * camper or van are checked on the car profile too (openrouteservice's "driving-car" has no
 * measures), the ADR tunnel category and goods dangerous for water when set in the app, and on a
 * motorway a height below 3.8 m or a width below 2.6 m is taken as a map mistake (e.g. the A1 at
 * Casalecchio carried the 3.5 m of the street under it).
 *
 * Graphs made before these values (10/2026) are still read, weighed as they were when their
 * landmarks were prepared (the time divided by the preference factor; "shortest" as the time plus
 * 10 s per metre), so that the landmarks stay right; their own limits stand in for the new ones.
 */
public final class OrsWeighting implements Weighting {
  public enum Kind { FASTEST, RECOMMENDED, SHORTEST }

  static final double SPEED_CONV = 3.6;
  /** Parameters.Routing.DEFAULT_HEADING_PENALTY. */
  static final double HEADING_PENALTY = 300;
  /** LimitedAccessWeighting's factors for motor vehicles. */
  static final double DESTINATION_FACTOR = 10, PRIVATE_FACTOR = 10, CUSTOMERS_FACTOR = 1.5, SERVICE_FACTOR = 1.2;
  /** openrouteservice does not accept a lower "maximum_speed" (maximum_speed_lower_bound). */
  public static final double MIN_TOP_SPEED = 80.0;
  static final double IMPLAUSIBLE_MOTORWAY_HEIGHT = 3.8, IMPLAUSIBLE_MOTORWAY_WIDTH = 2.6;
  // ORS-GH RoadAccess ordinals
  static final int RA_DESTINATION = 1, RA_CUSTOMERS = 2, RA_PRIVATE = 6;

  private final Kind kind;
  private final boolean truck;
  private final TurnCostProvider turns;
  private final TruckSpec spec;
  private final BooleanEncodedValue access;
  private final DecimalEncodedValue speed;
  private final IntEncodedValue priority, roadAccess, toll;
  /** Graphs before 10/2026: the preference as a factor (2^((code - 7) / 3)). */
  private final DecimalEncodedValue legacyRecommended;
  private final BooleanEncodedValue service, ferry, typeNo, hazmatNo;
  private final DecimalEncodedValue maxHeight, maxWidth, maxLength, maxWeight, maxAxleLoad;
  private final EnumEncodedValue<RoadClass> roadClass;
  private final BooleanEncodedValue roadClassLink;
  private final EnumEncodedValue<HazmatTunnel> hazmatTunnel;
  private final EnumEncodedValue<HazmatWater> hazmatWater;
  private final double topSpeed, maxSpeed;
  /** A graph made before openrouteservice's values (no ors_road_access). */
  private final boolean legacy;
  /** Legacy "shortest": 10 s per metre plus the time (distance_influence 10,000 s/km). */
  static final double LEGACY_DISTANCE_INFLUENCE = 10.0;
  private final List<Polygon> zones = new ArrayList<>();
  private final Envelope zonesBox = new Envelope();

  public OrsWeighting(EncodedValueLookup ev, boolean truck, Kind kind, TurnCostProvider turns, TruckSpec spec) {
    this.truck = truck;
    // openrouteservice's ProfileTools.setWeightingMethod: for cars "recommended" is the fastest,
    // for heavy vehicles "fastest" is "recommended" (the same weighting); graphs made before the
    // openrouteservice values keep the weighting their landmarks were prepared with
    boolean old = !ev.hasEncodedValue(GhEngine.ORS_ROAD_ACCESS);
    this.kind = !truck && kind == Kind.RECOMMENDED ? Kind.FASTEST
        : truck && kind == Kind.FASTEST && !old ? Kind.RECOMMENDED : kind;
    this.turns = turns;
    this.spec = spec;
    access = ev.getBooleanEncodedValue(truck ? GhEngine.ORS_HGV_ACCESS : GhEngine.ORS_CAR_ACCESS);
    speed = ev.getDecimalEncodedValue(truck ? GhEngine.ORS_HGV_SPEED : GhEngine.ORS_CAR_SPEED);
    priority = ev.hasEncodedValue(GhEngine.ORS_HGV_PRIORITY) ? ev.getIntEncodedValue(GhEngine.ORS_HGV_PRIORITY) : null;
    legacyRecommended = priority == null && ev.hasEncodedValue("ors_hgv_recommended") ? ev.getDecimalEncodedValue("ors_hgv_recommended") : null;
    roadAccess = ev.hasEncodedValue(GhEngine.ORS_ROAD_ACCESS) ? ev.getIntEncodedValue(GhEngine.ORS_ROAD_ACCESS) : null;
    legacy = roadAccess == null;
    toll = ev.hasEncodedValue(GhEngine.ORS_TOLL) ? ev.getIntEncodedValue(GhEngine.ORS_TOLL) : null;
    service = bool(ev, GhEngine.ORS_SERVICE);
    ferry = bool(ev, GhEngine.ORS_FERRY);
    // HeavyVehicleEdgeFilter: the roads closed to the vehicle type of the request
    typeNo = spec != null && "bus".equals(spec.vehicleType) ? bool(ev, GhEngine.ORS_BUS_TYPE_NO) : bool(ev, GhEngine.ORS_HGV_TYPE_NO);
    hazmatNo = bool(ev, GhEngine.ORS_HAZMAT_NO);
    maxHeight = dec(ev, GhEngine.ORS_MAX_HEIGHT, "max_height");
    maxWidth = dec(ev, GhEngine.ORS_MAX_WIDTH, "max_width");
    maxLength = dec(ev, GhEngine.ORS_MAX_LENGTH, "max_length");
    maxWeight = dec(ev, GhEngine.ORS_MAX_WEIGHT, "max_weight");
    maxAxleLoad = dec(ev, GhEngine.ORS_MAX_AXLE_LOAD, "max_axle_load");
    roadClass = ev.hasEncodedValue(RoadClass.KEY) ? ev.getEnumEncodedValue(RoadClass.KEY, RoadClass.class) : null;
    roadClassLink = bool(ev, "road_class_link");
    hazmatTunnel = ev.hasEncodedValue(HazmatTunnel.KEY) ? ev.getEnumEncodedValue(HazmatTunnel.KEY, HazmatTunnel.class) : null;
    hazmatWater = ev.hasEncodedValue(HazmatWater.KEY) ? ev.getEnumEncodedValue(HazmatWater.KEY, HazmatWater.class) : null;
    maxSpeed = speed.getMaxOrMaxStorableDecimal();
    topSpeed = spec != null && spec.topSpeedKmh > 0 ? Math.max(spec.topSpeedKmh, MIN_TOP_SPEED) : Double.POSITIVE_INFINITY;
    if (spec != null) {
      GeometryFactory gf = new GeometryFactory();
      for (double[][] ring : spec.avoidZones) {
        if (ring.length < 3) continue;
        Coordinate[] c = new Coordinate[ring.length + 1];
        for (int i = 0; i < ring.length; i++) c[i] = new Coordinate(ring[i][1], ring[i][0]);
        c[ring.length] = c[0];
        try {
          Polygon p = gf.createPolygon(c);
          zones.add(p);
          zonesBox.expandToInclude(p.getEnvelopeInternal());
        } catch (Exception ignored) {
          // not a ring
        }
      }
    }
  }

  private static BooleanEncodedValue bool(EncodedValueLookup ev, String key) {
    return ev.hasEncodedValue(key) ? ev.getBooleanEncodedValue(key) : null;
  }

  private static DecimalEncodedValue dec(EncodedValueLookup ev, String key, String legacy) {
    if (ev.hasEncodedValue(key)) return ev.getDecimalEncodedValue(key);
    return ev.hasEncodedValue(legacy) ? ev.getDecimalEncodedValue(legacy) : null;
  }

  @Override
  public double calcMinWeightPerDistance() {
    if (legacy) {
      double perM = SPEED_CONV / maxSpeed / (legacyRecommended != null && kind == Kind.RECOMMENDED ? legacyRecommended.getMaxOrMaxStorableDecimal() : 1);
      return kind == Kind.SHORTEST ? perM + LEGACY_DISTANCE_INFLUENCE : perM;
    }
    if (kind == Kind.SHORTEST) return 1;
    // the best preference halves the time (2^((4 - 7) / 3)); the other factors are >= 1
    return SPEED_CONV / maxSpeed * (kind == Kind.RECOMMENDED ? 0.5 : 1);
  }

  @Override
  public double calcEdgeWeight(EdgeIteratorState edge, boolean reverse) {
    if (!(reverse ? edge.getReverse(access) : edge.get(access))) return Double.POSITIVE_INFINITY;
    if (spec != null && !accept(edge)) return Double.POSITIVE_INFINITY;
    double v = speed(edge, reverse);
    if (v == 0) return Double.POSITIVE_INFINITY;
    double distance = edge.getDistance();
    double w;
    if (legacy) {
      w = distance / v * SPEED_CONV;
      if (edge.get(EdgeIteratorState.UNFAVORED_EDGE)) w += HEADING_PENALTY;
      if (kind == Kind.RECOMMENDED && legacyRecommended != null) w /= edge.get(legacyRecommended);
      if (kind == Kind.SHORTEST) w += distance * LEGACY_DISTANCE_INFLUENCE;
    } else if (kind == Kind.SHORTEST) {
      w = distance;
    } else {
      w = distance / v * SPEED_CONV;
      if (edge.get(EdgeIteratorState.UNFAVORED_EDGE)) w += HEADING_PENALTY;
      if (kind == Kind.RECOMMENDED) w *= preferenceFactor(edge);
    }
    w *= limitedAccess(edge);
    if (spec != null && spec.penalized != null && spec.penalized.contains(edge.getEdge())) w *= 2;
    return w;
  }

  /** ORSPriorityWeighting: 2^((4 - preference) / 3). */
  private double preferenceFactor(EdgeIteratorState edge) {
    if (priority != null) return Math.pow(2, (4 - edge.get(priority)) / 3.0);
    return 1;
  }

  /** LimitedAccessWeighting (motor vehicles). */
  private double limitedAccess(EdgeIteratorState edge) {
    double f = 1;
    if (roadAccess != null) {
      int a = edge.get(roadAccess);
      if (a == RA_DESTINATION) f *= DESTINATION_FACTOR;
      else if (a == RA_PRIVATE) f *= PRIVATE_FACTOR;
      else if (a == RA_CUSTOMERS) f *= CUSTOMERS_FACTOR;
    }
    if (service != null && edge.get(service)) f *= SERVICE_FACTOR;
    return f;
  }

  private double speed(EdgeIteratorState edge, boolean reverse) {
    return Math.min(topSpeed, reverse ? edge.getReverse(speed) : edge.get(speed));
  }

  private static boolean below(EdgeIteratorState edge, DecimalEncodedValue enc, double measure) {
    if (enc == null || measure <= 0) return false;
    double limit = edge.get(enc);
    return !Double.isInfinite(limit) && limit > 0.0 && limit < measure;
  }

  /** HeavyVehicleEdgeFilter, AvoidFeaturesEdgeFilter, AvoidAreasEdgeFilter (and the safety additions). */
  private boolean accept(EdgeIteratorState edge) {
    TruckSpec s = spec;
    if (truck && typeNo != null && edge.get(typeNo)) return false;
    if (s.hazmat && hazmatNo != null && edge.get(hazmatNo)) return false;
    boolean motorway = roadClass != null && edge.get(roadClass) == RoadClass.MOTORWAY
        && !(roadClassLink != null && edge.get(roadClassLink));
    if (below(edge, maxHeight, s.heightM) && !(motorway && edge.get(maxHeight) < IMPLAUSIBLE_MOTORWAY_HEIGHT)) return false;
    if (below(edge, maxWidth, s.widthM) && !(motorway && edge.get(maxWidth) < IMPLAUSIBLE_MOTORWAY_WIDTH)) return false;
    if (below(edge, maxLength, s.lengthM) || below(edge, maxWeight, s.weightT) || below(edge, maxAxleLoad, s.axleLoadT)) return false;
    // ADR tunnel categories A..E are ordinals 0..4: code B (1) is barred from B, C, D and E
    if (s.tunnelCode >= 'B' && s.tunnelCode <= 'E' && hazmatTunnel != null
        && edge.get(hazmatTunnel).ordinal() >= s.tunnelCode - 'A') return false;
    if (s.hazmatWater && hazmatWater != null && edge.get(hazmatWater) == HazmatWater.NO) return false;
    if (s.avoidTolls && toll != null) {
      int t = edge.get(toll);
      if (t == 1 || (truck && t == 2)) return false;
    }
    if (s.avoidFerries && ferry != null && edge.get(ferry)) return false;
    if (!zones.isEmpty() && inZone(edge)) return false;
    return true;
  }

  /**
   * Why the trip closes [edge] to the vehicle, in words for the driver ("altezza massima 3,20 m"),
   * or null when the vehicle may use it (a route computed elsewhere is checked with it).
   */
  public String whyClosed(EdgeIteratorState edge, boolean reverse) {
    if (!(reverse ? edge.getReverse(access) : edge.get(access))) return "strada chiusa a questo mezzo";
    if (spec == null) return null;
    TruckSpec s = spec;
    if (truck && typeNo != null && edge.get(typeNo)) return "bus".equals(s.vehicleType) ? "divieto per gli autobus" : "divieto per i mezzi pesanti";
    if (s.hazmat && hazmatNo != null && edge.get(hazmatNo)) return "divieto per le merci pericolose";
    boolean motorway = roadClass != null && edge.get(roadClass) == RoadClass.MOTORWAY
        && !(roadClassLink != null && edge.get(roadClassLink));
    java.util.Locale it = java.util.Locale.ITALIAN;
    if (below(edge, maxHeight, s.heightM) && !(motorway && edge.get(maxHeight) < IMPLAUSIBLE_MOTORWAY_HEIGHT))
      return String.format(it, "altezza massima %.2f m", edge.get(maxHeight));
    if (below(edge, maxWidth, s.widthM) && !(motorway && edge.get(maxWidth) < IMPLAUSIBLE_MOTORWAY_WIDTH))
      return String.format(it, "larghezza massima %.2f m", edge.get(maxWidth));
    if (below(edge, maxLength, s.lengthM)) return String.format(it, "lunghezza massima %.1f m", edge.get(maxLength));
    if (below(edge, maxWeight, s.weightT)) return String.format(it, "peso massimo %.1f t", edge.get(maxWeight));
    if (below(edge, maxAxleLoad, s.axleLoadT)) return String.format(it, "peso per asse massimo %.1f t", edge.get(maxAxleLoad));
    if (s.tunnelCode >= 'B' && s.tunnelCode <= 'E' && hazmatTunnel != null
        && edge.get(hazmatTunnel).ordinal() >= s.tunnelCode - 'A') return "galleria vietata al codice ADR " + s.tunnelCode;
    if (s.hazmatWater && hazmatWater != null && edge.get(hazmatWater) == HazmatWater.NO) return "divieto per le merci pericolose per le acque";
    return null;
  }

  /** AvoidAreasEdgeFilter: the road lies inside a zone or crosses its border. */
  private boolean inZone(EdgeIteratorState edge) {
    PointList pl = edge.fetchWayGeometry(FetchMode.ALL);
    Envelope box = new Envelope();
    for (int i = 0; i < pl.size(); i++) box.expandToInclude(pl.getLon(i), pl.getLat(i));
    if (!box.intersects(zonesBox)) return false;
    if (pl.size() < 2) return true;
    Coordinate[] c = new Coordinate[pl.size()];
    for (int i = 0; i < pl.size(); i++) c[i] = new Coordinate(pl.getLon(i), pl.getLat(i));
    LineString ls = new GeometryFactory().createLineString(c);
    for (Polygon p : zones) if (p.contains(ls) || ls.crosses(p)) return true;
    return false;
  }

  @Override
  public long calcEdgeMillis(EdgeIteratorState edge, boolean reverse) {
    double v = speed(edge, reverse);
    if (v == 0) return Long.MAX_VALUE;
    return Math.round(edge.getDistance() / v * SPEED_CONV * 1000);
  }

  @Override
  public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
    return turns.calcTurnWeight(inEdge, viaNode, outEdge);
  }

  @Override
  public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
    return turns.calcTurnMillis(inEdge, viaNode, outEdge);
  }

  @Override
  public boolean hasTurnCosts() {
    return turns != TurnCostProvider.NO_TURN_COST_PROVIDER;
  }

  @Override
  public String getName() {
    return "custom";
  }
}
