import app.navmaster.truck.routing.gh.GhEngine;
import app.navmaster.truck.routing.gh.TruckSpec;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Check of a GraphHopper graph with the app's own routing code (GhEngine, NmWeightingFactory),
 * run on GitHub right after the graph is built: the graph loads with the app's profiles, and for
 * each trip a lorry (4 m, 40 t) and a camper (3.2 m, 3.5 t) get a route, with its alternatives,
 * and the time it took. A trip slower than 3 s on the runner fails the check (on a tablet it would
 * be several times slower).
 *
 * usage: java -cp gh.jar:classes GhCheck GRAPH_DIR MODELS_DIR "lat,lon;lat,lon|lat,lon;lat,lon"  ("-": load only)
 */
public class GhCheck {
  public static void main(String[] a) throws Exception {
    long t0 = System.currentTimeMillis();
    GhEngine e = GhEngine.open(new File(a[0]), new File(a[1]), true);
    System.out.println("GHCHECK loaded in " + (System.currentTimeMillis() - t0) + " ms");
    if (a.length < 3 || a[2].equals("-")) {
      e.close();
      return;
    }
    int fails = 0;
    for (String trip : a[2].split("\\|")) {
      List<double[]> pts = new ArrayList<>();
      for (String s : trip.split(";")) {
        String[] c = s.split(",");
        pts.add(new double[] {Double.parseDouble(c[0]), Double.parseDouble(c[1])});
      }
      for (int v = 0; v < 2; v++) {
        TruckSpec s = new TruckSpec();
        if (v == 1) {
          s.hgv = false; s.heightM = 3.2; s.widthM = 2.3; s.lengthM = 7.5; s.weightT = 3.5; s.axleLoadT = 2.0;
        }
        for (int run = 0; run < 2; run++) {
          List<GhEngine.Result> rs = e.routes(pts, null, s, 3);
          GhEngine.Result r = rs.get(0);
          StringBuilder alt = new StringBuilder();
          for (int k = 1; k < rs.size(); k++) alt.append(String.format(" / %.0f km", rs.get(k).distanceM / 1000));
          System.out.println("GHCHECK " + trip + " " + (v == 0 ? "camion" : "camper") + " run " + run + ": "
              + (r.ok() ? String.format("%.1f km, %d min, %d paths%s, %d ms", r.distanceM / 1000, r.timeMs / 60000, rs.size(), alt, r.computeMs)
                  : "ERROR " + r.error) + (r.note != null ? " (" + r.note + ")" : ""));
          if (!r.ok() || (run == 1 && r.computeMs > 3000)) fails++;
        }
      }
    }
    e.close();
    if (fails > 0) {
      System.out.println("GHCHECK " + fails + " failures");
      System.exit(1);
    }
  }
}
