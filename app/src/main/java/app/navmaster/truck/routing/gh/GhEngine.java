package app.navmaster.truck.routing.gh;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.carrotsearch.hppc.IntHashSet;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import com.graphhopper.util.TurnCostsConfig;
import com.graphhopper.util.shapes.GHPoint;
import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * GraphHopper on the tablet, offline: loads a country's graph (built on GitHub with the same base
 * model, see .github/workflows/grafo-gh.yml) from a folder, memory-mapped so it does not have to
 * fit in RAM, and computes the route of the vehicle with its measures ({@link TruckSpec}).
 *
 * It gives the path (points on the roads) and, from the graph itself, the guidance along it
 * (manoeuvres, voice, lanes, signs, speed limits: GhGuide).
 *
 * Plain Java, no Android: the same class runs in the routing test on the computer.
 */
public final class GhEngine implements Closeable {
  /**
   * The profiles of the graph, each with its own landmarks, with openrouteservice's rules (worked
   * out from the OSM tags when the graph is built, tools/gh/NmImport.java): lorries as its
   * "driving-hgv" (fastest, recommended = more motorway, shortest) and the other vehicles (campers,
   * vans, buses) as its "driving-car" (fastest, shortest: openrouteservice's "recommended" for cars
   * is the fastest). A vehicle is routed on the profile of its class and kind of route, whose base
   * model matches its default choices exactly - a search with weights far from the landmarks' ones
   * is tens of times slower.
   */
  public static final String PROFILE_TRUCK = "nm_truck";
  public static final String PROFILE_CAR = "nm_car";
  /** The profiles "recommended" (more motorway) and "shortest" of a vehicle: nm_truck_mw, nm_truck_short… */
  public static final String SUFFIX_MOTORWAY = "_mw";
  public static final String SUFFIX_SHORT = "_short";
  /** The kinds of route a driver can ask for (TruckSpec.route). */
  public static final int ROUTE_FAST = 0, ROUTE_MOTORWAY = 1, ROUTE_SHORT = 2;
  /** openrouteservice's values stored in the graph (NmImport), and GraphHopper's ones used by the trip's choices. */
  public static final String ENCODED_VALUES = "ors_hgv_access, ors_hgv_speed, ors_hgv_recommended, ors_car_access, ors_car_speed, "
      + "road_class, road_class_link, road_environment, toll, max_width, max_height, max_weight, max_length, max_axle_load, "
      + "hazmat, hazmat_tunnel, hazmat_water, "
      // for the guidance (GhGuide): the OSM way (limits and difficulties of the tablet's data are
      // matched by way), lanes, surface, country, speed limit, roundabouts
      + "osm_way_id, lanes, surface, country, max_speed, roundabout";

  /** Values of the graph's key-value store added for the guidance (tools/gh/NmImport.java). */
  public static final String KV_TURN_LANES = "nm_turn_lanes", KV_LANES_DIR = "nm_lanes_dir",
      KV_JUNCTION_REF = "nm_junction_ref", KV_NODES = "nm_nodes", KV_SERVICE = "nm_service";

  /** Every profile name of the graph, in order: lorry fast/recommended/short, car fast/short. */
  public static List<String> profileNames() {
    return Arrays.asList(PROFILE_TRUCK, PROFILE_TRUCK + SUFFIX_MOTORWAY, PROFILE_TRUCK + SUFFIX_SHORT,
        PROFILE_CAR, PROFILE_CAR + SUFFIX_SHORT);
  }

  /** The profile for a vehicle and the kind of route asked (cars: "more motorway" is the fastest, as in openrouteservice). */
  public static String profileFor(TruckSpec s) {
    String base = s.hgv ? PROFILE_TRUCK : PROFILE_CAR;
    if (s.route == ROUTE_SHORT || s.shortest) return base + SUFFIX_SHORT;
    if (s.route == ROUTE_MOTORWAY && s.hgv) return base + SUFFIX_MOTORWAY;
    return base;
  }

  /**
   * Landmarks per profile (the precomputed data that make long searches fast): 4, half the
   * GraphHopper default, so the data of each profile take half the room on the tablet.
   */
  public static final int LANDMARKS = 4;

  /** The profiles of lorries ("camion" package) or of the other vehicles ("auto" package). */
  public static List<String> profilesOf(boolean hgv) {
    List<String> out = new ArrayList<>();
    for (String n : profileNames()) if (n.startsWith(PROFILE_TRUCK) == hgv) out.add(n);
    return out;
  }

  /** The landmarks of [profile] are in the graph folder [dir]. */
  public static boolean hasLandmarks(File dir, String profile) {
    return new File(dir, "landmarks_" + profile).exists();
  }

  /** The models folder files: one per profile, named after it. */
  public static List<String> modelFiles() {
    List<String> out = new ArrayList<>();
    for (String n : profileNames()) out.add(n + ".json");
    return out;
  }
  /** Vehicle types whose turn restrictions are followed (as when the graph was built). */
  public static final List<String> TURN_VEHICLES_TRUCK = Arrays.asList("hgv", "motorcar", "motor_vehicle");
  public static final List<String> TURN_VEHICLES_CAR = Arrays.asList("motorcar", "motor_vehicle");
  public static final int U_TURN_COSTS = 60;
  /** Ways left out of the graph (no vehicle drives on them); the same list as on GitHub. */
  public static final String IGNORED_HIGHWAYS =
      "footway,cycleway,path,pedestrian,steps,bridleway,corridor,construction,proposed,platform,raceway";

  private final GraphHopper hopper;
  /** The profiles whose landmarks are on the tablet (the others are not used). */
  private final java.util.Set<String> ready;

  private GhEngine(GraphHopper hopper, java.util.Set<String> ready) {
    this.hopper = hopper;
    this.ready = ready;
  }

  /** The landmarks of the vehicle's profile are on the tablet (its package was downloaded). */
  public boolean canRoute(TruckSpec spec) {
    return ready.contains(profileFor(spec));
  }

  /**
   * A profile exactly as the graph was built with it (GraphHopper checks its fingerprint): the
   * base model is read by GraphHopper from its file in the models folder, as on GitHub.
   */
  public static Profile profile(String name, String modelFile, List<String> turnVehicles) {
    Profile p = new Profile(name);
    p.setTurnCostsConfig(new TurnCostsConfig(turnVehicles, U_TURN_COSTS));
    // as read from a config file: no inline model (Profile(name) puts an empty one), only the file
    p.getHints().remove("custom_model");
    p.putHint("custom_model_files", Collections.singletonList(modelFile));
    return p;
  }

  public static List<Profile> profiles() {
    List<Profile> out = new ArrayList<>();
    for (String n : profileNames()) out.add(profile(n, n + ".json", n.startsWith(PROFILE_TRUCK) ? TURN_VEHICLES_TRUCK : TURN_VEHICLES_CAR));
    return out;
  }

  public static List<LMProfile> lmProfiles() {
    List<LMProfile> out = new ArrayList<>();
    for (String n : profileNames()) out.add(new LMProfile(n));
    return out;
  }

  /**
   * Opens the graph in [dir] (the folder with "properties", "nodes", "edges"...). [modelsDir]: the
   * folder with the base models. [mmap]: memory mapped (tablet) or in RAM (small test graphs).
   */
  public static GhEngine open(File dir, File modelsDir, boolean mmap) throws Exception {
    GraphHopperConfig cfg = new GraphHopperConfig();
    cfg.putObject("graph.location", dir.getAbsolutePath());
    cfg.putObject("custom_models.directory", modelsDir.getAbsolutePath());
    // as when the graph was built (only checked, nothing is imported here)
    cfg.putObject("import.osm.ignored_highways", IGNORED_HIGHWAYS);
    cfg.putObject("graph.dataaccess.default_type", mmap ? "MMAP" : "RAM_STORE");
    cfg.setProfiles(profiles());
    // only the profiles whose landmarks were downloaded (lorries, other vehicles or both)
    List<LMProfile> lm = new ArrayList<>();
    java.util.Set<String> ready = new java.util.HashSet<>();
    for (String n : profileNames()) {
      if (hasLandmarks(dir, n)) {
        lm.add(new LMProfile(n));
        ready.add(n);
      }
    }
    cfg.setLMProfiles(lm);
    cfg.putObject("prepare.lm.landmarks", LANDMARKS);
    cfg.putObject("routing.lm.active_landmarks", LANDMARKS);
    GraphHopper gh = new GraphHopper() {
      @Override
      protected WeightingFactory createWeightingFactory() {
        return new NmWeightingFactory(getBaseGraph(), getEncodingManager());
      }
    };
    gh.init(cfg);
    gh.setAllowWrites(false);
    if (!gh.load()) {
      gh.close();
      throw new IllegalStateException("grafo GraphHopper non trovato in " + dir);
    }
    return new GhEngine(gh, ready);
  }

  /** The route found: its points, where each point given is on it, distance and time. */
  public static final class Result {
    public double[] lat = new double[0];
    public double[] lon = new double[0];
    /** For each point given (start, stops, destination), the index of its point on the path. */
    public int[] waypointIndex = new int[0];
    public double distanceM;
    public long timeMs;
    public long computeMs;
    public String error;
    /** Why GraphHopper gave no alternatives (when asked and it could not), for the log. */
    public String note;
    /** GraphHopper's own answer (with the roads of the path), for the guidance (GhGuide). */
    public transient ResponsePath path;

    public boolean ok() {
      return error == null && lat.length >= 2;
    }
  }

  /**
   * The route through [points] ([lat, lon]) for [spec]. [headings]: direction of travel at each
   * point in degrees, NaN where there is none (the first one is the direction of the vehicle).
   */
  public Result route(List<double[]> points, List<Double> headings, TruckSpec spec) {
    return routes(points, headings, spec, 1).get(0);
  }

  /** Trips shorter than this (in a straight line) get GraphHopper's own search for alternatives. */
  static final double SHORT_TRIP_M = 60_000;

  /**
   * Up to [maxPaths] different routes, the best one first (alternatives only for a trip without
   * stops). Never empty: a failure is a Result with [Result#error].
   *
   * The best route is one fast search on the landmarks. The alternatives: on short trips
   * GraphHopper's own "alternative route" search (it compares whole corridors and gives the nicest
   * alternatives, but its time grows with the distance: Milano - Roma 20 s); on longer trips routes
   * pulled to one side or the other of the best one (viaAlternatives), a fraction of a second each.
   */
  public List<Result> routes(List<double[]> points, List<Double> headings, TruckSpec spec, int maxPaths) {
    long t0 = System.currentTimeMillis();
    List<Result> out = new ArrayList<>();
    String note = null;
    try {
      boolean alt = maxPaths > 1 && points.size() == 2;
      boolean shortTrip = alt && crow(points) < SHORT_TRIP_M;
      if (shortTrip) {
        GHResponse rsp = hopper.route(request(points, headings, spec, maxPaths));
        List<ResponsePath> paths = rsp.hasErrors() ? new ArrayList<>() : new ArrayList<>(rsp.getAll());
        note = "alternative_route: " + paths.size() + " paths";
        for (ResponsePath p : paths) if (!p.hasErrors() && out.size() < maxPaths) out.add(result(p));
      }
      if (out.isEmpty()) {
        GHRequest req = request(points, headings, spec, 1);
        GHResponse rsp = hopper.route(req);
        if (rsp.hasErrors()) {
          Result r = new Result();
          r.error = String.valueOf(rsp.getErrors().get(0).getMessage());
          out.add(r);
        } else {
          ResponsePath best = rsp.getBest();
          out.add(result(best));
          if (alt) note = viaAlternatives(points, headings, spec, maxPaths, best, out);
        }
      }
    } catch (Exception e) {
      Result r = new Result();
      r.error = e.toString();
      out.clear();
      out.add(r);
    }
    long ms = System.currentTimeMillis() - t0;
    for (Result r : out) r.computeMs = ms;
    if (!out.isEmpty() && note != null) out.get(0).note = note;
    return out;
  }

  /**
   * The alternatives of a long trip: the route through a point to one side of the best route's
   * middle (left and right, nearer and farther), kept when not much slower and really different.
   * Every search has the vehicle's own weights, so each one takes a fraction of a second.
   */
  private String viaAlternatives(List<double[]> points, List<Double> headings, TruckSpec spec, int maxPaths,
      ResponsePath best, List<Result> out) {
    List<IntHashSet> chosen = new ArrayList<>();
    chosen.add(edgeIds(best));
    PointList pl = best.getPoints();
    double[] a = points.get(0), b = points.get(points.size() - 1);
    double crow = crow(points);
    // the middle of the best route
    double total = best.getDistance(), along = 0;
    double midLat = pl.getLat(pl.size() / 2), midLon = pl.getLon(pl.size() / 2);
    for (int i = 1; i < pl.size(); i++) {
      along += crow(Arrays.asList(new double[] {pl.getLat(i - 1), pl.getLon(i - 1)}, new double[] {pl.getLat(i), pl.getLon(i)}));
      if (along >= total / 2) {
        midLat = pl.getLat(i);
        midLon = pl.getLon(i);
        break;
      }
    }
    // across the direction of the trip, in metres
    double k = Math.cos(Math.toRadians(midLat));
    double dy = (b[0] - a[0]) * 111_195, dx = (b[1] - a[1]) * 111_195 * k;
    double len = Math.max(1, Math.sqrt(dx * dx + dy * dy));
    double px = -dy / len, py = dx / len;
    String note = "via alternatives:";
    double[] offsets = {0.12, -0.12, 0.22, -0.22, 0.06, -0.06};
    for (double off : offsets) {
      if (out.size() >= maxPaths) break;
      double vLat = midLat + py * off * crow / 111_195, vLon = midLon + px * off * crow / (111_195 * k);
      List<double[]> via = Arrays.asList(a, new double[] {vLat, vLon}, b);
      List<Double> hs = null;
      if (headings != null && !headings.isEmpty()) hs = Arrays.asList(headings.get(0), Double.NaN, Double.NaN);
      GHRequest req = request(via, hs, spec, 1);
      // no turning back at the point: it is only there to pull the route to that side
      req.getHints().putObject("pass_through", true);
      long t = System.currentTimeMillis();
      GHResponse rsp = hopper.route(req);
      if (rsp.hasErrors()) {
        note += " (no road)";
        continue;
      }
      ResponsePath p = rsp.getBest();
      IntHashSet mine = edgeIds(p);
      double share = 0;
      for (IntHashSet other : chosen) {
        int shared = 0;
        for (com.carrotsearch.hppc.cursors.IntCursor c : mine) if (other.contains(c.value)) shared++;
        share = Math.max(share, mine.isEmpty() ? 1 : shared / (double) mine.size());
      }
      note += String.format(java.util.Locale.ROOT, " %.0f km/%.0f min/%.0f%%/%d ms", p.getDistance() / 1000, p.getTime() / 60000.0,
          share * 100, System.currentTimeMillis() - t);
      if (p.getTime() > best.getTime() * 1.35 || share > 0.7) continue;
      chosen.add(mine);
      Result r = result(p);
      // the pulling point is not a stop: only the start and the destination are waypoints
      r.waypointIndex = new int[] {0, r.lat.length - 1};
      out.add(r);
    }
    return note;
  }

  private static IntHashSet edgeIds(ResponsePath p) {
    IntHashSet set = new IntHashSet();
    List<PathDetail> details = p.getPathDetails().get("edge_id");
    if (details != null) for (PathDetail d : details) if (d.getValue() instanceof Number) set.add(((Number) d.getValue()).intValue());
    return set;
  }

  private static double crow(List<double[]> points) {
    double[] a = points.get(0), b = points.get(points.size() - 1);
    double k = Math.cos(Math.toRadians((a[0] + b[0]) / 2));
    double dy = (b[0] - a[0]) * 111_195, dx = (b[1] - a[1]) * 111_195 * k;
    return Math.sqrt(dx * dx + dy * dy);
  }

  private GHRequest request(List<double[]> points, List<Double> headings, TruckSpec spec, int maxPaths) {
    List<GHPoint> pts = new ArrayList<>();
    for (double[] p : points) pts.add(new GHPoint(p[0], p[1]));
    GHRequest req = new GHRequest(pts).setProfile(profileFor(spec)).setLocale(Locale.ITALIAN);
    if (headings != null && headings.size() == pts.size()) {
      boolean any = false;
      for (Double h : headings) if (h != null && !h.isNaN()) any = true;
      if (any) {
        List<Double> hs = new ArrayList<>();
        for (Double h : headings) hs.add(h == null ? Double.NaN : h);
        req.setHeadings(hs);
      }
    }
    req.getHints().putObject(NmWeightingFactory.SPEC, spec);
    // the roads of the path (alternatives, guidance) and the time on each one
    req.setPathDetails(Arrays.asList("edge_id", "edge_key", "time"));
    req.getHints().putObject("instructions", false);
    req.getHints().putObject("calc_points", true);
    // every point of the roads, not simplified: Valhalla follows the path closely
    req.getHints().putObject("way_point_max_distance", 0);
    if (maxPaths > 1) {
      // routes that are really different (at most 70% of road in common) and not much longer
      // (at most 50% more) than the best one: on Rimini - San Marino three routes, as Valhalla gave
      req.setAlgorithm("alternative_route");
      req.getHints().putObject("alternative_route.max_paths", maxPaths);
      req.getHints().putObject("alternative_route.max_weight_factor", 1.5);
      req.getHints().putObject("alternative_route.max_share_factor", 0.7);
    }
    return req;
  }

  private static Result result(ResponsePath best) {
    Result r = new Result();
    PointList pl = best.getPoints();
    int n = pl.size();
    r.lat = new double[n];
    r.lon = new double[n];
    for (int i = 0; i < n; i++) {
      r.lat[i] = pl.getLat(i);
      r.lon[i] = pl.getLon(i);
    }
    PointList wp = best.getWaypoints();
    r.waypointIndex = new int[wp.size()];
    int from = 0;
    for (int k = 0; k < wp.size(); k++) {
      int bestI = from;
      double bestD = Double.MAX_VALUE;
      for (int i = from; i < n; i++) {
        double dLat = pl.getLat(i) - wp.getLat(k), dLon = pl.getLon(i) - wp.getLon(k);
        double d = dLat * dLat + dLon * dLon;
        if (d < bestD) {
          bestD = d;
          bestI = i;
        }
        if (d == 0) break;
      }
      r.waypointIndex[k] = k == 0 ? 0 : (k == wp.size() - 1 ? n - 1 : bestI);
      from = r.waypointIndex[k];
    }
    r.distanceM = best.getDistance();
    r.timeMs = best.getTime();
    r.path = best;
    return r;
  }

  /** The graph has the values the guidance needs (graphs built since 10/2026). */
  public boolean canGuide() {
    return GhGuide.supported(hopper);
  }

  /**
   * The guidance along [r] (GhGuide): an OSRM answer for Ferrostar and the roads of the route for
   * the analysis. [breaks]: indices in r's points of the stops (start and end included);
   * [requested]: the points asked for the stops (same order), for the side of arrival.
   */
  public GhGuide.Output guide(Result r, TruckSpec spec, int[] breaks, List<double[]> requested) {
    return new GhGuide(hopper, spec.hgv).guide(r.path, breaks, requested);
  }

  @Override
  public void close() {
    hopper.close();
  }
}
