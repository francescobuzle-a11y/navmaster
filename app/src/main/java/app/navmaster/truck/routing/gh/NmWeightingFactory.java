package app.navmaster.truck.routing.gh;

import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.Hazmat;
import com.graphhopper.routing.ev.HazmatTunnel;
import com.graphhopper.routing.ev.HazmatWater;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.ev.Toll;
import com.graphhopper.routing.ev.TurnRestriction;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.DefaultTurnCostProvider;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.weighting.custom.CustomWeighting;
import com.graphhopper.routing.weighting.custom.CustomWeightingHelper;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PMap;
import com.graphhopper.util.TurnCostsConfig;
import com.graphhopper.util.shapes.Polygon;
import java.util.ArrayList;
import java.util.List;

/**
 * How GraphHopper weighs every road for our vehicle, written as plain code.
 *
 * GraphHopper normally turns a "custom model" (rules written as text) into Java bytecode at run time
 * with the Janino compiler; Android cannot load that bytecode, so here the same rules are written
 * directly in Java and nothing is compiled on the tablet.
 *
 * The roads are weighed as openrouteservice does: the graph carries, for every road, its access,
 * speed and (lorries) preference worked out with openrouteservice's own rules when it was built
 * (tools/gh/NmImport.java). Five profiles (GhEngine): lorries "fastest" (the time), "recommended"
 * (the time multiplied by 2^((4 - preference) / 3): motorways and main roads preferred, small
 * streets avoided) and "shortest" (the distance); cars and campers "fastest" and "shortest".
 * For each one:
 * - the BASE weighting, statement by statement the custom model the graph was built with
 *   (assets/gh/*.json): the landmarks (the precomputed data that make long routes fast) were
 *   prepared with it, and a vehicle without limits gets exactly it;
 * - the weighting of the TRIP (request hint {@link #SPEC} with a {@link TruckSpec}): the base one
 *   minus the roads the vehicle may not use, as openrouteservice's restrictions (lower, narrower,
 *   shorter, lighter than the vehicle, axle load, dangerous goods) and options (no tolls, no
 *   ferries, zones to avoid, its top speed).
 */
public final class NmWeightingFactory implements WeightingFactory {
  /** Request hint carrying the {@link TruckSpec}. */
  public static final String SPEC = "nm_spec";

  private final BaseGraph graph;
  private final EncodingManager em;

  private final BooleanEncodedValue hgvAccess, carAccess;
  private final DecimalEncodedValue hgvSpeed, carSpeed, hgvRecommended;
  private final EnumEncodedValue<RoadEnvironment> roadEnv;
  private final DecimalEncodedValue maxWidth, maxHeight, maxWeight, maxLength, maxAxleLoad;
  private final EnumEncodedValue<Hazmat> hazmat;
  private final EnumEncodedValue<HazmatTunnel> hazmatTunnel;
  private final EnumEncodedValue<HazmatWater> hazmatWater;
  private final EnumEncodedValue<Toll> toll;
  private final EnumEncodedValue<RoadClass> roadClass;
  private final BooleanEncodedValue roadClassLink;

  public NmWeightingFactory(BaseGraph graph, EncodingManager em) {
    this.graph = graph;
    this.em = em;
    hgvAccess = em.getBooleanEncodedValue("ors_hgv_access");
    carAccess = em.getBooleanEncodedValue("ors_car_access");
    hgvSpeed = em.getDecimalEncodedValue("ors_hgv_speed");
    carSpeed = em.getDecimalEncodedValue("ors_car_speed");
    hgvRecommended = em.getDecimalEncodedValue("ors_hgv_recommended");
    roadEnv = en(RoadEnvironment.KEY, RoadEnvironment.class);
    maxWidth = dec("max_width");
    maxHeight = dec("max_height");
    maxWeight = dec("max_weight");
    maxLength = dec("max_length");
    maxAxleLoad = dec("max_axle_load");
    hazmat = en(Hazmat.KEY, Hazmat.class);
    hazmatTunnel = en(HazmatTunnel.KEY, HazmatTunnel.class);
    hazmatWater = en(HazmatWater.KEY, HazmatWater.class);
    toll = en(Toll.KEY, Toll.class);
    roadClass = en(RoadClass.KEY, RoadClass.class);
    roadClassLink = em.hasEncodedValue("road_class_link") ? em.getBooleanEncodedValue("road_class_link") : null;
  }

  /** The kind of route of a profile: the fastest, more motorway, the shorter. */
  enum Mode { FAST, MOTORWAY, SHORT }

  static Mode modeOf(String profile) {
    if (profile.endsWith(GhEngine.SUFFIX_MOTORWAY)) return Mode.MOTORWAY;
    if (profile.endsWith(GhEngine.SUFFIX_SHORT)) return Mode.SHORT;
    return Mode.FAST;
  }

  private DecimalEncodedValue dec(String key) {
    return em.hasEncodedValue(key) ? em.getDecimalEncodedValue(key) : null;
  }

  private <T extends Enum<?>> EnumEncodedValue<T> en(String key, Class<T> c) {
    return em.hasEncodedValue(key) ? em.getEnumEncodedValue(key, c) : null;
  }

  @Override
  public Weighting createWeighting(Profile profile, PMap requestHints, boolean disableTurnCosts) {
    TruckSpec spec = requestHints.getObject(SPEC, null);
    final boolean truck = profile.getName().startsWith(GhEngine.PROFILE_TRUCK);
    final Mode mode = modeOf(profile.getName());
    TurnCostProvider turns = TurnCostProvider.NO_TURN_COST_PROVIDER;
    if (profile.hasTurnCosts() && !disableTurnCosts) {
      BooleanEncodedValue restriction = em.getTurnBooleanEncodedValue(TurnRestriction.key(profile.getName()));
      TurnCostsConfig tc = new TurnCostsConfig(profile.getTurnCostsConfig());
      turns = new DefaultTurnCostProvider(restriction, graph, tc, null);
    }
    final BooleanEncodedValue access = truck ? hgvAccess : carAccess;
    final DecimalEncodedValue speedEnc = truck ? hgvSpeed : carSpeed;
    final boolean recommended = truck && mode == Mode.MOTORWAY;
    // as GraphHopper works them out from the base model: the highest speed and preference stored
    final double maxSpeed = speedEnc.getMaxOrMaxStorableDecimal();
    final double maxPriority = recommended ? hgvRecommended.getMaxOrMaxStorableDecimal() : 1.0;
    final TruckSpec s = spec;
    // openrouteservice's "maximum_speed": the vehicle's top speed, not below 80 km/h
    final double top = s != null && s.topSpeedKmh > 0 ? Math.max(s.topSpeedKmh, MIN_TOP_SPEED) : Double.POSITIVE_INFINITY;
    final List<Polygon> zones = new ArrayList<>();
    if (s != null) {
      for (double[][] ring : s.avoidZones) {
        double[] lat = new double[ring.length], lon = new double[ring.length];
        for (int i = 0; i < ring.length; i++) { lat[i] = ring[i][0]; lon[i] = ring[i][1]; }
        if (ring.length >= 3) zones.add(new Polygon(lat, lon));
      }
    }
    CustomWeighting.Parameters p = new CustomWeighting.Parameters(
        (edge, reverse) -> Math.min(top, reverse ? edge.getReverse(speedEnc) : edge.get(speedEnc)),
        () -> maxSpeed,
        (edge, reverse) -> {
          double v = basePriority(access, recommended, edge, reverse);
          return s == null || v == 0.0 ? v : priority(truck, s, zones, v, edge);
        },
        () -> maxPriority,
        null,
        mode == Mode.SHORT ? DISTANCE_INFLUENCE_SHORT : 0.0,
        300.0);
    return new CustomWeighting(turns, p);
  }

  /** openrouteservice does not accept a lower "maximum_speed". */
  static final double MIN_TOP_SPEED = 80.0;
  /**
   * "Shortest": each km worth 10,000 s, the time is only a tie-breaker (a motorway km takes 42 s:
   * less than 0.5%), as openrouteservice's weighting by distance alone.
   */
  static final double DISTANCE_INFLUENCE_SHORT = 10_000.0;
  /** Signed limits on a motorway that cannot be true (map mistakes), not applied. */
  static final double IMPLAUSIBLE_MOTORWAY_HEIGHT = 3.8, IMPLAUSIBLE_MOTORWAY_WIDTH = 2.6;

  /**
   * The base model of the profile, statement by statement as in assets/gh/*.json: closed where
   * openrouteservice closes the road to the class of vehicle; lorries "recommended" multiplied by
   * the road's preference.
   */
  private double basePriority(BooleanEncodedValue access, boolean recommended, EdgeIteratorState edge, boolean reverse) {
    if (!(reverse ? edge.getReverse(access) : edge.get(access))) return 0.0;
    return recommended ? edge.get(hgvRecommended) : 1.0;
  }

  private static boolean below(EdgeIteratorState edge, DecimalEncodedValue enc, double value) {
    if (enc == null || value <= 0) return false;
    double limit = edge.get(enc);
    return !Double.isInfinite(limit) && limit > 0 && limit < value - 1e-6;
  }

  /**
   * The weight of the road for this vehicle: as openrouteservice's restrictions and options, a
   * road whose signed limit is below the vehicle's measure is closed (height, width, length,
   * weight, axle load), dangerous goods where they are forbidden, tolls and ferries when avoided,
   * the zones to avoid.
   */
  private double priority(boolean truck, TruckSpec s, List<Polygon> zones, double v, EdgeIteratorState edge) {
    // on a motorway a height below 3.8 m or a width below 2.6 m is a mistake of the map (e.g. the
    // A1 at Casalecchio carried the 3.5 m of the street passing under it): not a limit for lorries
    boolean motorway = roadClass != null && edge.get(roadClass) == RoadClass.MOTORWAY
        && !(roadClassLink != null && edge.get(roadClassLink));
    if ((below(edge, maxHeight, s.heightM) && !(motorway && edge.get(maxHeight) < IMPLAUSIBLE_MOTORWAY_HEIGHT))
        || (below(edge, maxWidth, s.widthM) && !(motorway && edge.get(maxWidth) < IMPLAUSIBLE_MOTORWAY_WIDTH))
        || below(edge, maxLength, s.lengthM)
        || below(edge, maxWeight, s.weightT)
        || below(edge, maxAxleLoad, s.axleLoadT)) return 0.0;
    if (s.hazmat && hazmat != null && edge.get(hazmat) == Hazmat.NO) return 0.0;
    if (s.tunnelCode >= 'B' && s.tunnelCode <= 'E' && hazmatTunnel != null) {
      // categories A..E are ordinals 0..4: code B (1) is barred from B, C, D and E
      if (edge.get(hazmatTunnel).ordinal() >= s.tunnelCode - 'A') return 0.0;
    }
    if (s.hazmatWater && hazmatWater != null && edge.get(hazmatWater) == HazmatWater.NO) return 0.0;
    // openrouteservice's avoid_features "tollways" and "ferries": the roads are left out
    if (s.avoidTolls && toll != null) {
      Toll t = edge.get(toll);
      if (t == Toll.ALL || (truck && t == Toll.HGV)) return 0.0;
    }
    if (s.avoidFerries && roadEnv != null && edge.get(roadEnv) == RoadEnvironment.FERRY) return 0.0;
    for (Polygon z : zones) if (CustomWeightingHelper.in(z, edge)) return 0.0;
    if (s.penalized != null && s.penalized.contains(edge.getEdge())) v *= 0.5;
    return v;
  }
}
