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
 * with the Janino compiler; Android cannot load that bytecode, so here the same kind of rules are
 * written directly in Java and nothing is compiled on the tablet.
 *
 * Two weightings:
 * - the BASE one, identical to the custom model the graph was built with (assets/gh/nm_base.json):
 *   every road a car or a lorry may use, at the car's speed. The landmarks (the precomputed data that
 *   make long routes fast) were prepared with it;
 * - the one of the TRIP (request hint {@link #SPEC} with a {@link TruckSpec}): the same roads minus
 *   those the vehicle may not use (too low, narrow, short, light, axle load, dangerous goods, lorries
 *   forbidden), with penalties (private / destination only, tolls and ferries when avoided...) and the
 *   vehicle's own speed. Its weights are never lower than the base ones, which keeps the landmarks
 *   valid for any vehicle.
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
    TurnCostProvider turns = TurnCostProvider.NO_TURN_COST_PROVIDER;
    if (profile.hasTurnCosts() && !disableTurnCosts) {
      BooleanEncodedValue restriction = em.getTurnBooleanEncodedValue(TurnRestriction.key(profile.getName()));
      TurnCostsConfig tc = new TurnCostsConfig(profile.getTurnCostsConfig());
      turns = new DefaultTurnCostProvider(restriction, graph, tc, null);
    }
    double maxBase = carSpeed.getMaxOrMaxStorableDecimal();
    if (spec == null) {
      // the base model: { if: "!car_access && hgv not in (YES, DESIGNATED, DESTINATION, DELIVERY)", multiply_by: 0 },
      // speed limited to car_average_speed, distance_influence 0
      CustomWeighting.Parameters p = new CustomWeighting.Parameters(
          this::baseSpeed, () -> maxBase, this::basePriority, () -> 1.0, null, 0.0, 300.0);
      return new CustomWeighting(turns, p);
    }
    final TruckSpec s = spec;
    final List<Polygon> zones = new ArrayList<>();
    for (double[][] ring : s.avoidZones) {
      double[] lat = new double[ring.length], lon = new double[ring.length];
      for (int i = 0; i < ring.length; i++) { lat[i] = ring[i][0]; lon[i] = ring[i][1]; }
      if (ring.length >= 3) zones.add(new Polygon(lat, lon));
    }
    final double top = Math.max(10.0, Math.min(s.topSpeedKmh, maxBase));
    CustomWeighting.Parameters p = new CustomWeighting.Parameters(
        (edge, reverse) -> Math.min(baseSpeed(edge, reverse) * (s.hgv ? 0.9 : 1.0), top),
        () -> top,
        (edge, reverse) -> priority(s, zones, edge, reverse),
        () -> 1.0,
        null,
        // seconds of time a kilometre is worth: the fastest route with a small push towards the
        // shorter one (or mostly the shorter one when asked)
        s.shortest ? 150.0 : 20.0,
        300.0);
    return new CustomWeighting(turns, p);
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

  private double basePriority(EdgeIteratorState edge, boolean reverse) {
    if (!car(edge, reverse) && !hgvAllowed(hgvOf(edge))) return 0.0;
    return 1.0;
  }

  private static boolean below(EdgeIteratorState edge, DecimalEncodedValue enc, double value) {
    if (enc == null || value <= 0) return false;
    double limit = edge.get(enc);
    return !Double.isInfinite(limit) && limit > 0 && limit < value - 1e-6;
  }

  private double priority(TruckSpec s, List<Polygon> zones, EdgeIteratorState edge, boolean reverse) {
    if (basePriority(edge, reverse) == 0.0) return 0.0;
    double v = 1.0;
    Hgv h = hgvOf(edge);
    boolean car = car(edge, reverse);
    if (s.hgv) {
      if (h == Hgv.NO) return 0.0;
      if (h == Hgv.DESTINATION || h == Hgv.DELIVERY) v *= 0.1;
      if (h == Hgv.DISCOURAGED || h == Hgv.AGRICULTURAL) v *= 0.3;
      if (s.preferTruckRoutes && h != Hgv.DESIGNATED) v *= 0.9;
    } else if (!car) {
      return 0.0;
    }
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
    if (roadAccess != null) {
      RoadAccess ra = edge.get(roadAccess);
      if (ra == RoadAccess.NO) return 0.0;
      if (ra == RoadAccess.PRIVATE || ra == RoadAccess.DESTINATION || ra == RoadAccess.CUSTOMERS
          || ra == RoadAccess.DELIVERY || ra == RoadAccess.AGRICULTURAL || ra == RoadAccess.FORESTRY) v *= 0.1;
    }
    if (s.avoidTolls && toll != null) {
      Toll t = edge.get(toll);
      if (t == Toll.ALL || (s.hgv && t == Toll.HGV)) v *= 0.02;
    }
    if (roadEnv != null && edge.get(roadEnv) == RoadEnvironment.FERRY) v *= s.avoidFerries ? 0.02 : 0.5;
    if (surface != null) {
      Surface su = edge.get(surface);
      boolean rough = su == Surface.UNPAVED || su == Surface.COMPACTED || su == Surface.FINE_GRAVEL || su == Surface.GRAVEL
          || su == Surface.GROUND || su == Surface.DIRT || su == Surface.GRASS || su == Surface.SAND;
      if (rough) {
        // never closed outright: a destination on a dirt road must stay reachable
        v *= s.avoidUnpaved ? 0.02 : 0.3;
      }
    }
    for (Polygon z : zones) if (CustomWeightingHelper.in(z, edge)) return 0.0;
    return v;
  }
}
