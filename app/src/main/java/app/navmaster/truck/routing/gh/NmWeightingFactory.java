package app.navmaster.truck.routing.gh;

import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.Hazmat;
import com.graphhopper.routing.ev.HazmatTunnel;
import com.graphhopper.routing.ev.HazmatWater;
import com.graphhopper.routing.ev.Hgv;
import com.graphhopper.routing.ev.MaxWeightExcept;
import com.graphhopper.routing.ev.RoadAccess;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.ev.Surface;
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
 * Two profiles (GhEngine): lorries and the other vehicles. For each one:
 * - the BASE weighting, statement by statement the custom model the graph was built with
 *   (assets/gh/nm_truck.json, nm_car.json): the landmarks (the precomputed data that make long
 *   routes fast) were prepared with it, and a vehicle with the default choices gets exactly it;
 * - the weighting of the TRIP (request hint {@link #SPEC} with a {@link TruckSpec}): the base one
 *   minus the roads the vehicle may not use (too low, narrow, short, heavy, axle load, dangerous
 *   goods, zones) and with the trip's own choices (tolls, ferries, unpaved roads, lorry roads).
 */
public final class NmWeightingFactory implements WeightingFactory {
  /** Request hint carrying the {@link TruckSpec}. */
  public static final String SPEC = "nm_spec";

  private final BaseGraph graph;
  private final EncodingManager em;

  private final BooleanEncodedValue carAccess;
  private final DecimalEncodedValue carSpeed;
  private final EnumEncodedValue<RoadAccess> roadAccess;
  private final EnumEncodedValue<RoadEnvironment> roadEnv;
  private final EnumEncodedValue<Hgv> hgv;
  private final DecimalEncodedValue maxWidth, maxHeight, maxWeight, maxLength, maxAxleLoad;
  private final EnumEncodedValue<MaxWeightExcept> maxWeightExcept;
  private final EnumEncodedValue<Hazmat> hazmat;
  private final EnumEncodedValue<HazmatTunnel> hazmatTunnel;
  private final EnumEncodedValue<HazmatWater> hazmatWater;
  private final EnumEncodedValue<Toll> toll;
  private final EnumEncodedValue<Surface> surface;

  public NmWeightingFactory(BaseGraph graph, EncodingManager em) {
    this.graph = graph;
    this.em = em;
    carAccess = em.getBooleanEncodedValue("car_access");
    carSpeed = em.getDecimalEncodedValue("car_average_speed");
    roadAccess = en(RoadAccess.KEY, RoadAccess.class);
    roadEnv = en(RoadEnvironment.KEY, RoadEnvironment.class);
    hgv = en(Hgv.KEY, Hgv.class);
    maxWidth = dec("max_width");
    maxHeight = dec("max_height");
    maxWeight = dec("max_weight");
    maxLength = dec("max_length");
    maxAxleLoad = dec("max_axle_load");
    maxWeightExcept = en(MaxWeightExcept.KEY, MaxWeightExcept.class);
    hazmat = en(Hazmat.KEY, Hazmat.class);
    hazmatTunnel = en(HazmatTunnel.KEY, HazmatTunnel.class);
    hazmatWater = en(HazmatWater.KEY, HazmatWater.class);
    toll = en(Toll.KEY, Toll.class);
    surface = en(Surface.KEY, Surface.class);
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
    final boolean truck = !GhEngine.PROFILE_CAR.equals(profile.getName());
    TurnCostProvider turns = TurnCostProvider.NO_TURN_COST_PROVIDER;
    if (profile.hasTurnCosts() && !disableTurnCosts) {
      BooleanEncodedValue restriction = em.getTurnBooleanEncodedValue(TurnRestriction.key(profile.getName()));
      TurnCostsConfig tc = new TurnCostsConfig(profile.getTurnCostsConfig());
      turns = new DefaultTurnCostProvider(restriction, graph, tc, null);
    }
    // the speeds of the class of vehicle (not of the single vehicle: its own top speed only
    // changes the arrival time, worked out afterwards), exactly as in assets/gh/nm_truck.json and
    // nm_car.json
    final double maxCar = carSpeed.getMaxOrMaxStorableDecimal();
    final double maxSpeed = truck ? Math.min(0.9 * maxCar, TRUCK_SPEED) : Math.min(maxCar, CAR_SPEED);
    final TruckSpec s = spec;
    final List<Polygon> zones = new ArrayList<>();
    if (s != null) {
      for (double[][] ring : s.avoidZones) {
        double[] lat = new double[ring.length], lon = new double[ring.length];
        for (int i = 0; i < ring.length; i++) { lat[i] = ring[i][0]; lon[i] = ring[i][1]; }
        if (ring.length >= 3) zones.add(new Polygon(lat, lon));
      }
    }
    CustomWeighting.Parameters p = new CustomWeighting.Parameters(
        (edge, reverse) -> speed(truck, edge, reverse),
        () -> maxSpeed,
        (edge, reverse) -> s == null ? basePriority(truck, edge, reverse) : priority(truck, s, zones, edge, reverse),
        () -> 1.0,
        null,
        // seconds of time a kilometre is worth: the fastest route with a small push towards the
        // shorter one (or mostly the shorter one when asked)
        s != null && s.shortest ? 150.0 : DISTANCE_INFLUENCE,
        300.0);
    return new CustomWeighting(turns, p);
  }

  /** As in the base models: lorries 90% of the car speed, at most 85 km/h; others at most 110 km/h. */
  static final double TRUCK_SPEED = 85.0;
  static final double CAR_SPEED = 110.0;
  static final double DISTANCE_INFLUENCE = 20.0;

  private double speed(boolean truck, EdgeIteratorState edge, boolean reverse) {
    double v = baseSpeed(edge, reverse);
    return truck ? Math.min(v * 0.9, TRUCK_SPEED) : Math.min(v, CAR_SPEED);
  }

  private double baseSpeed(EdgeIteratorState edge, boolean reverse) {
    return reverse ? edge.getReverse(carSpeed) : edge.get(carSpeed);
  }

  private boolean car(EdgeIteratorState edge, boolean reverse) {
    return reverse ? edge.getReverse(carAccess) : edge.get(carAccess);
  }

  private Hgv hgvOf(EdgeIteratorState edge) {
    return hgv == null ? Hgv.MISSING : edge.get(hgv);
  }

  private static boolean hgvAllowed(Hgv h) {
    return h == Hgv.YES || h == Hgv.DESIGNATED || h == Hgv.DESTINATION || h == Hgv.DELIVERY;
  }

  private static boolean rough(Surface su) {
    return su == Surface.UNPAVED || su == Surface.COMPACTED || su == Surface.FINE_GRAVEL || su == Surface.GRAVEL
        || su == Surface.GROUND || su == Surface.DIRT || su == Surface.GRASS || su == Surface.SAND;
  }

  private static boolean restricted(RoadAccess ra) {
    return ra == RoadAccess.PRIVATE || ra == RoadAccess.DESTINATION || ra == RoadAccess.CUSTOMERS
        || ra == RoadAccess.DELIVERY || ra == RoadAccess.AGRICULTURAL || ra == RoadAccess.FORESTRY;
  }

  /**
   * The base model of the profile, statement by statement as in assets/gh/nm_truck.json /
   * nm_car.json (the landmarks were prepared with it: the default vehicle gets exactly these
   * weights, so the search stays as fast as GraphHopper can be).
   */
  private double basePriority(boolean truck, EdgeIteratorState edge, boolean reverse) {
    double v = 1.0;
    boolean car = car(edge, reverse);
    RoadAccess ra = roadAccess == null ? RoadAccess.YES : edge.get(roadAccess);
    if (truck) {
      Hgv h = hgvOf(edge);
      if (h == Hgv.NO) return 0.0;
      if (!car && !hgvAllowed(h)) return 0.0;
      if (ra == RoadAccess.NO) return 0.0;
      if (h == Hgv.DESTINATION || h == Hgv.DELIVERY) v *= 0.1;
      if (h == Hgv.DISCOURAGED || h == Hgv.AGRICULTURAL) v *= 0.3;
      if (h != Hgv.DESIGNATED) v *= 0.97;
    } else {
      if (!car) return 0.0;
      if (ra == RoadAccess.NO) return 0.0;
    }
    if (restricted(ra)) v *= 0.1;
    if (roadEnv != null && edge.get(roadEnv) == RoadEnvironment.FERRY) v *= 0.02;
    if (surface != null && rough(edge.get(surface))) v *= 0.02;
    return v;
  }

  private static boolean below(EdgeIteratorState edge, DecimalEncodedValue enc, double value) {
    if (enc == null || value <= 0) return false;
    double limit = edge.get(enc);
    return !Double.isInfinite(limit) && limit > 0 && limit < value - 1e-6;
  }

  /**
   * The weight of the road for this vehicle: the base model (the default choices) changed where
   * the vehicle or the trip differ - roads closed by its measures, dangerous goods, zones; tolls
   * avoided; ferries, unpaved roads and roads for lorries as chosen.
   */
  private double priority(boolean truck, TruckSpec s, List<Polygon> zones, EdgeIteratorState edge, boolean reverse) {
    double v = basePriority(truck, edge, reverse);
    if (v == 0.0) return 0.0;
    // choices different from the base model (which avoids ferries and unpaved roads and prefers
    // roads signed for lorries)
    if (truck && !s.preferTruckRoutes && hgvOf(edge) != Hgv.DESIGNATED) v /= 0.97;
    if (!s.avoidFerries && roadEnv != null && edge.get(roadEnv) == RoadEnvironment.FERRY) v = v / 0.02 * 0.5;
    if (!s.avoidUnpaved && surface != null && rough(edge.get(surface))) v = v / 0.02 * 0.3;
    // the measures of the vehicle against the signed limits
    if (below(edge, maxHeight, s.heightM) || below(edge, maxWidth, s.widthM) || below(edge, maxLength, s.lengthM)
        || below(edge, maxAxleLoad, s.axleLoadT)) return 0.0;
    if (below(edge, maxWeight, s.weightT)) {
      MaxWeightExcept ex = maxWeightExcept == null ? MaxWeightExcept.MISSING : edge.get(maxWeightExcept);
      if (ex == MaxWeightExcept.MISSING) return 0.0;
      v *= 0.1; // except for delivery / destination: only to get there
    }
    if (s.hazmat && hazmat != null && edge.get(hazmat) == Hazmat.NO) return 0.0;
    if (s.tunnelCode >= 'B' && s.tunnelCode <= 'E' && hazmatTunnel != null) {
      // categories A..E are ordinals 0..4: code B (1) is barred from B, C, D and E
      if (edge.get(hazmatTunnel).ordinal() >= s.tunnelCode - 'A') return 0.0;
    }
    if (s.hazmatWater && hazmatWater != null && edge.get(hazmatWater) == HazmatWater.NO) return 0.0;
    if (s.avoidTolls && toll != null) {
      Toll t = edge.get(toll);
      if (t == Toll.ALL || (truck && t == Toll.HGV)) v *= 0.02;
    }
    for (Polygon z : zones) if (CustomWeightingHelper.in(z, edge)) return 0.0;
    if (s.penalized != null && s.penalized.contains(edge.getEdge())) v *= 0.5;
    return Math.min(v, 1.0);
  }
}
