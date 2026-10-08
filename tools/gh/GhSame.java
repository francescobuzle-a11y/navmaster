import app.navmaster.truck.routing.gh.GhEngine;
import app.navmaster.truck.routing.gh.NmWeightingFactory;
import app.navmaster.truck.routing.gh.TruckSpec;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.LMProfile;
import com.graphhopper.util.shapes.GHPoint;
import java.io.File;
import java.util.Arrays;

/**
 * The app's weighting (NmWeightingFactory, written in Java) gives exactly the weights of
 * GraphHopper's own custom models (assets/gh/nm_truck.json, nm_car.json, compiled by Janino on the
 * computer) for the default choices: same route, same weight. Run on GitHub after each graph.
 *
 * usage: java -cp gh.jar:classes GhSame GRAPH_DIR MODELS_DIR "lat,lon;lat,lon|..."
 */
public class GhSame {
  public static void main(String[] a) throws Exception {
    GraphHopperConfig cfg = new GraphHopperConfig();
    cfg.putObject("graph.location", new File(a[0]).getAbsolutePath());
    cfg.putObject("custom_models.directory", new File(a[1]).getAbsolutePath());
    cfg.putObject("import.osm.ignored_highways", GhEngine.IGNORED_HIGHWAYS);
    cfg.putObject("graph.dataaccess.default_type", "MMAP");
    cfg.setProfiles(GhEngine.profiles());
    cfg.setLMProfiles(GhEngine.lmProfiles());
    cfg.putObject("prepare.lm.landmarks", GhEngine.LANDMARKS);
    cfg.putObject("routing.lm.active_landmarks", GhEngine.LANDMARKS);
    GraphHopper std = new GraphHopper();
    std.init(cfg);
    std.setAllowWrites(false);
    std.load();
    java.lang.reflect.Field f = GhEngine.class.getDeclaredField("hopper");
    f.setAccessible(true);
    GhEngine e = GhEngine.open(new File(a[0]), new File(a[1]), true);
    GraphHopper ours = (GraphHopper) f.get(e);
    int fails = 0;
    for (String trip : a[2].split("\\|")) {
      String[] p = trip.split(";");
      String[] c1 = p[0].split(","), c2 = p[1].split(",");
      for (String prof : GhEngine.profileNames()) {
        double[] w = new double[2];
        for (int k = 0; k < 2; k++) {
          GHRequest r = new GHRequest(new GHPoint(Double.parseDouble(c1[0]), Double.parseDouble(c1[1])),
              new GHPoint(Double.parseDouble(c2[0]), Double.parseDouble(c2[1]))).setProfile(prof);
          r.getHints().putObject("instructions", false);
          if (k == 1) {
            // the default choices, and no limits from the measures (they only close roads)
            TruckSpec s = new TruckSpec();
            s.hgv = prof.startsWith(GhEngine.PROFILE_TRUCK);
            s.route = prof.endsWith(GhEngine.SUFFIX_MOTORWAY) ? GhEngine.ROUTE_MOTORWAY
                : prof.endsWith(GhEngine.SUFFIX_SHORT) ? GhEngine.ROUTE_SHORT : GhEngine.ROUTE_FAST;
            s.avoidFerries = false; s.avoidTolls = false; s.topSpeedKmh = 0;
            s.heightM = 0; s.widthM = 0; s.lengthM = 0; s.weightT = 0; s.axleLoadT = 0;
            r.getHints().putObject(NmWeightingFactory.SPEC, s);
          }
          long t = System.currentTimeMillis();
          GHResponse rsp = (k == 0 ? std : ours).route(r);
          w[k] = rsp.hasErrors() ? -1 : rsp.getBest().getRouteWeight();
          System.out.println("GHSAME " + trip + " " + prof + (k == 0 ? " GraphHopper " : " app ")
              + (rsp.hasErrors() ? rsp.getErrors() : String.format("weight %.3f, %.1f km, %d ms", w[k], rsp.getBest().getDistance() / 1000,
                  System.currentTimeMillis() - t)));
        }
        if (w[0] < 0 || Math.abs(w[0] - w[1]) > 1e-6 * Math.max(1, w[0])) fails++;
      }
    }
    e.close();
    std.close();
    if (fails > 0) {
      System.out.println("GHSAME " + fails + " differences");
      System.exit(1);
    }
  }
}
