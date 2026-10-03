import app.navmaster.truck.routing.gh.GhEngine;
import app.navmaster.truck.routing.gh.TruckSpec;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Check of a GraphHopper graph with the app's own routing code (GhEngine, NmWeightingFactory),
 * run on the computer / on GitHub right after the graph is built: the graph loads with the app's
 * profile, and routes are found for a lorry and a camper.
 *
 * usage: java -cp gh.jar:classes GhCheck GRAPH_DIR MODELS_DIR "lat1,lon1;lat2,lon2" [h w l t]...  (points "-": load only)
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
    List<double[]> pts = new ArrayList<>();
    for (String s : a[2].split(";")) {
      String[] c = s.split(",");
      pts.add(new double[] {Double.parseDouble(c[0]), Double.parseDouble(c[1])});
    }
    int fails = 0;
    for (int k = 3; k + 3 < a.length + 0 || k == 3; k += 4) {
      TruckSpec s = new TruckSpec();
      if (a.length > k + 3) {
        s.heightM = Double.parseDouble(a[k]);
        s.widthM = Double.parseDouble(a[k + 1]);
        s.lengthM = Double.parseDouble(a[k + 2]);
        s.weightT = Double.parseDouble(a[k + 3]);
        s.hgv = s.weightT > 3.5;
      }
      GhEngine.Result r = e.route(pts, null, s);
      System.out.println("GHCHECK " + s + " -> " + (r.ok() ? String.format("%.0f m, %d s, %d points, %d ms", r.distanceM,
          r.timeMs / 1000, r.lat.length, r.computeMs) : "ERROR " + r.error) + " wp=" + Arrays.toString(r.waypointIndex));
      if (!r.ok()) fails++;
      if (a.length <= k + 3) break;
    }
    e.close();
    if (fails > 0) System.exit(1);
  }
}
