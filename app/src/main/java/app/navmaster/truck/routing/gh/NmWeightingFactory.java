package app.navmaster.truck.routing.gh;

import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.TurnRestriction;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.DefaultTurnCostProvider;
import com.graphhopper.routing.weighting.TurnCostProvider;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.PMap;
import com.graphhopper.util.TurnCostsConfig;

/**
 * The weighting of every profile: openrouteservice's (OrsWeighting), written as plain code.
 *
 * GraphHopper normally turns a "custom model" (rules written as text) into Java bytecode at run time
 * with the Janino compiler; Android cannot load that bytecode, and openrouteservice's weighting is
 * not a custom model anyway: the graph is built and its landmarks prepared with this same factory
 * (tools/gh/NmImport.java), the custom model files of the profiles (assets/gh) are only there
 * because GraphHopper wants one per profile.
 *
 * Five profiles (GhEngine): lorries "fastest", "recommended" (more motorway) and "shortest", cars
 * and campers "fastest" and "shortest". Without the request hint {@link #SPEC} the weighting is the
 * one the landmarks were prepared with; with a {@link TruckSpec} the trip's restrictions and options
 * are added (they only make roads dearer or close them, so the landmarks stay right).
 */
public final class NmWeightingFactory implements WeightingFactory {
  /** Request hint carrying the {@link TruckSpec}. */
  public static final String SPEC = "nm_spec";
  /** openrouteservice does not accept a lower "maximum_speed". */
  static final double MIN_TOP_SPEED = OrsWeighting.MIN_TOP_SPEED;

  private final BaseGraph graph;
  private final EncodingManager em;

  public NmWeightingFactory(BaseGraph graph, EncodingManager em) {
    this.graph = graph;
    this.em = em;
  }

  /** The kind of route of a profile: the fastest, more motorway ("recommended"), the shortest. */
  static OrsWeighting.Kind kindOf(String profile) {
    if (profile.endsWith(GhEngine.SUFFIX_MOTORWAY)) return OrsWeighting.Kind.RECOMMENDED;
    if (profile.endsWith(GhEngine.SUFFIX_SHORT)) return OrsWeighting.Kind.SHORTEST;
    return OrsWeighting.Kind.FASTEST;
  }

  @Override
  public Weighting createWeighting(Profile profile, PMap requestHints, boolean disableTurnCosts) {
    TruckSpec spec = requestHints.getObject(SPEC, null);
    boolean truck = profile.getName().startsWith(GhEngine.PROFILE_TRUCK);
    TurnCostProvider turns = TurnCostProvider.NO_TURN_COST_PROVIDER;
    if (profile.hasTurnCosts() && !disableTurnCosts) {
      BooleanEncodedValue restriction = em.getTurnBooleanEncodedValue(TurnRestriction.key(profile.getName()));
      turns = new DefaultTurnCostProvider(restriction, graph, new TurnCostsConfig(profile.getTurnCostsConfig()), null);
    }
    return new OrsWeighting(em, truck, kindOf(profile.getName()), turns, spec);
  }
}
