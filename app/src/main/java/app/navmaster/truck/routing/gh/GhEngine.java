package app.navmaster.truck.routing.gh;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.util.PointList;
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
 * It gives the path (points on the roads); the guidance along it (manoeuvres, voice, lanes) is
 * worked out by Valhalla matching that path on its own map (OfflineRouteProvider).
 *
 * Plain Java, no Android: the same class runs in the routing test on the computer.
 */
public final class GhEngine implements Closeable {
  /** Name of the only profile of the graph. */
  public static final String PROFILE = "nm";
  /** File name of the base model, as given when the graph was built (part of the profile's identity). */
  public static final String BASE_MODEL_FILE = "nm_base.json";
  /** Vehicle types whose turn restrictions are followed (as when the graph was built). */
  public static final List<String> TURN_VEHICLES = Arrays.asList("hgv", "motorcar", "motor_vehicle");
  public static final int U_TURN_COSTS = 60;
  /** Ways left out of the graph (no vehicle drives on them); the same list as on GitHub. */
  public static final String IGNORED_HIGHWAYS =
      "footway,cycleway,path,pedestrian,steps,bridleway,corridor,construction,proposed,platform,raceway";

  private final GraphHopper hopper;

  private GhEngine(GraphHopper hopper) {
    this.hopper = hopper;
  }

  /**
   * The profile exactly as the graph was built with it (GraphHopper checks its fingerprint): the
   * base model is read by GraphHopper from [BASE_MODEL_FILE] in the models folder, as on GitHub.
   */
  public static Profile profile() {
    Profile p = new Profile(PROFILE);
    p.setTurnCostsConfig(new TurnCostsConfig(TURN_VEHICLES, U_TURN_COSTS));
    // as read from a config file: no inline model (Profile(name) puts an empty one), only the file
    p.getHints().remove("custom_model");
    p.putHint("custom_model_files", Collections.singletonList(BASE_MODEL_FILE));
    return p;
  }

  /**
   * Opens the graph in [dir] (the folder with "properties", "nodes", "edges"...). [modelsDir]: the
   * folder with [BASE_MODEL_FILE]. [mmap]: memory mapped (tablet) or in RAM (small test graphs).
   */
  public static GhEngine open(File dir, File modelsDir, boolean mmap) throws Exception {
    GraphHopperConfig cfg = new GraphHopperConfig();
    cfg.putObject("graph.location", dir.getAbsolutePath());
    cfg.putObject("custom_models.directory", modelsDir.getAbsolutePath());
    // as when the graph was built (only checked, nothing is imported here)
    cfg.putObject("import.osm.ignored_highways", IGNORED_HIGHWAYS);
    cfg.putObject("graph.dataaccess.default_type", mmap ? "MMAP" : "RAM_STORE");
    cfg.setProfiles(Collections.singletonList(profile()));
    cfg.setLMProfiles(Collections.singletonList(new LMProfile(PROFILE)));
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
    return new GhEngine(gh);
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

  /**
   * Up to [maxPaths] different routes from GraphHopper itself (its "alternative route" search, for
   * a trip without stops): the best one first. With stops, or when the alternatives cannot be
   * worked out, only the best one. Never empty: a failure is a Result with [Result#error].
   */
  public List<Result> routes(List<double[]> points, List<Double> headings, TruckSpec spec, int maxPaths) {
    long t0 = System.currentTimeMillis();
    List<Result> out = new ArrayList<>();
    String note0 = null;
    try {
      boolean alt = maxPaths > 1 && points.size() == 2;
      GHResponse rsp = hopper.route(request(points, headings, spec, alt ? maxPaths : 1));
      String note = null;
      if (alt && rsp.hasErrors()) {
        Throwable t = rsp.getErrors().get(0);
        note = "alternative_route: " + t;
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 6; i++) note += " < " + st[i];
        rsp = hopper.route(request(points, headings, spec, 1));
      } else if (alt) {
        note = "alternative_route: " + rsp.getAll().size() + " paths";
      }
      note0 = note;
      if (rsp.hasErrors()) {
        Result r = new Result();
        r.error = String.valueOf(rsp.getErrors().get(0).getMessage());
        out.add(r);
      } else {
        List<ResponsePath> paths = rsp.getAll();
        for (int k = 0; k < paths.size() && k < Math.max(1, maxPaths); k++) {
          ResponsePath path = paths.get(k);
          if (path.hasErrors()) continue;
          out.add(result(path));
        }
        if (out.isEmpty()) {
          Result r = new Result();
          r.error = "nessun percorso";
          out.add(r);
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
    if (!out.isEmpty() && note0 != null) out.get(0).note = note0;
    return out;
  }

  private static GHRequest request(List<double[]> points, List<Double> headings, TruckSpec spec, int maxPaths) {
    List<GHPoint> pts = new ArrayList<>();
    for (double[] p : points) pts.add(new GHPoint(p[0], p[1]));
    GHRequest req = new GHRequest(pts).setProfile(PROFILE).setLocale(Locale.ITALIAN);
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
    return r;
  }

  @Override
  public void close() {
    hopper.close();
  }
}
