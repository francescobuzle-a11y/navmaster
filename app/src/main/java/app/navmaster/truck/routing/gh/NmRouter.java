package app.navmaster.truck.routing.gh;

import com.graphhopper.GHRequest;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.Router;
import com.graphhopper.routing.RouterConfig;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.lm.LandmarkStorage;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.RoutingCHGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.TranslationMap;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import java.util.Map;

/**
 * GraphHopper's router with one addition for NavMaster: with the hint {@link #SNAP_SUBNETWORKS}
 * a point is snapped also onto the roads GraphHopper marked as "subnetwork" at the import.
 * <p>
 * The graphs are one per country, cut at the border: the piece of a motorway between its last
 * junction and the border can be driven only one way (into the country, or out of it), so it is a
 * dead end of the country's network and the import marks it as a subnetwork (prepare
 * min_network_size). GraphHopper never snaps a point onto such a road: a route that must start
 * or end exactly at the border crossing of a motorway was moved onto the nearest side road, and
 * the whole trip left the motorway before the border to come back onto it after it. The pieces
 * of a trip between countries (GhCross) ask for this hint, so that they start and end on the
 * motorway itself. The hint is only for points known to lie on a road that continues abroad: a
 * point snapped into a real dead end gives no route.
 */
public class NmRouter extends Router {
  public static final String SNAP_SUBNETWORKS = "nm.snap_subnetworks";

  public NmRouter(BaseGraph graph, EncodingManager encodingManager, LocationIndex locationIndex,
      Map<String, Profile> profilesByName, PathDetailsBuilderFactory pathDetailsBuilderFactory,
      TranslationMap translationMap, RouterConfig routerConfig, WeightingFactory weightingFactory,
      Map<String, RoutingCHGraph> chGraphs, Map<String, LandmarkStorage> landmarks) {
    super(graph, encodingManager, locationIndex, profilesByName, pathDetailsBuilderFactory, translationMap,
        routerConfig, weightingFactory, chGraphs, landmarks);
  }

  static boolean snapAll(GHRequest request) {
    return request.getHints().getBool(SNAP_SUBNETWORKS, false);
  }

  /**
   * A request that snaps onto the subnetworks never uses the landmarks: the landmarks (LM) were
   * prepared leaving the subnetworks out, so the nodes of a border stub have no landmark weights
   * (GraphHopper stores "infinity" there), and the A* with landmarks that starts or ends on such
   * a node is not exact: its estimate for the stub's nodes is enormous, the two searches meet and
   * stop too early, and the route found depends on where it starts (from Lograto the A23 was left
   * at Tarvisio for the SS54 and taken again at Coccau; from Canneto sull'Oglio it was not).
   * Plain A* (the flex solver, beeline estimate) is exact on every node; a piece of a trip between
   * countries costs a little more time, never a wrong road.
   */
  @Override
  protected Solver createLMSolver(GHRequest request, Map<String, Profile> profilesByName, RouterConfig routerConfig,
      EncodingManager encodingManager, WeightingFactory weightingFactory, BaseGraph graph, LocationIndex locationIndex,
      Map<String, LandmarkStorage> landmarks) {
    if (!snapAll(request))
      return super.createLMSolver(request, profilesByName, routerConfig, encodingManager, weightingFactory, graph, locationIndex, landmarks);
    return createFlexSolver(request, profilesByName, routerConfig, encodingManager, weightingFactory, graph, locationIndex);
  }

  @Override
  protected Solver createFlexSolver(GHRequest request, Map<String, Profile> profilesByName, RouterConfig routerConfig,
      EncodingManager encodingManager, WeightingFactory weightingFactory, BaseGraph graph, LocationIndex locationIndex) {
    if (!snapAll(request))
      return super.createFlexSolver(request, profilesByName, routerConfig, encodingManager, weightingFactory, graph, locationIndex);
    return new FlexSolver(request, profilesByName, routerConfig, encodingManager, weightingFactory, graph, locationIndex) {
      @Override
      protected EdgeFilter createSnapFilter() {
        return new AccessSnapFilter(weighting);
      }
    };
  }

  /** DefaultSnapFilter without the subnetwork test: the road is open to the vehicle in one direction at least. */
  static final class AccessSnapFilter implements EdgeFilter {
    private final com.graphhopper.routing.weighting.Weighting weighting;

    AccessSnapFilter(com.graphhopper.routing.weighting.Weighting weighting) {
      this.weighting = weighting;
    }

    @Override
    public boolean accept(EdgeIteratorState edge) {
      return Double.isFinite(weighting.calcEdgeWeight(edge, false)) || Double.isFinite(weighting.calcEdgeWeight(edge, true));
    }
  }
}
