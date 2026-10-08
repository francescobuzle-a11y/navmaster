import app.navmaster.truck.routing.gh.*;
import java.io.File;
import java.util.*;
public class CrossTest {
  // CrossTest MODELS g-A A valichi-A.json g-B B valichi-B.json "lat,lon;lat,lon" OUTDIR NAME
  public static void main(String[] a) throws Exception {
    File models = new File(a[0]);
    Map<String, GhEngine> engines = new HashMap<>();
    Map<String, List<GhCross.Crossing>> cross = new HashMap<>();
    engines.put(a[2], GhEngine.open(new File(a[1]), models, false));
    cross.put(a[2], GhCross.read(new File(a[3])));
    engines.put(a[5], GhEngine.open(new File(a[4]), models, false));
    cross.put(a[5], GhCross.read(new File(a[6])));
    for (String k : cross.keySet()) System.out.println(k + ": " + cross.get(k).size() + " crossings " + cross.get(k).subList(0, Math.min(5, cross.get(k).size())));
    GhCross.Graphs g = new GhCross.Graphs() {
      public boolean has(String c) { return engines.containsKey(c); }
      public GhEngine engine(String c) { return engines.get(c); }
      public List<GhCross.Crossing> crossings(String c) { return cross.getOrDefault(c, Collections.emptyList()); }
    };
    List<double[]> pts = new ArrayList<>();
    for (String p : a[7].split(";")) { String[] q = p.split(","); pts.add(new double[] {Double.parseDouble(q[0]), Double.parseDouble(q[1])}); }
    // the country of each point: the first one is in A, the last in B (the test trips)
    List<String> countries = Arrays.asList(a[2], a[5]);
    TruckSpec s = new TruckSpec();
    long t = System.currentTimeMillis();
    GhCross.Trip trip = GhCross.route(g, pts, countries, Double.NaN, s);
    System.out.printf("trip: %s %s in %d ms%n", trip.error, trip.note, System.currentTimeMillis() - t);
    if (trip.error != null) return;
    System.out.printf("whole: %.1f km, %d min, wp %s%n", trip.whole.distanceM / 1000, trip.whole.timeMs / 60000, Arrays.toString(trip.whole.waypointIndex));
    GhGuide.Output o = GhEngine.guide(trip, s, trip.whole.waypointIndex, pts);
    new File(a[8]).mkdirs();
    java.nio.file.Files.write(new File(a[8], a[9] + ".route.json").toPath(), o.osrm.getBytes("UTF-8"));
    java.nio.file.Files.write(new File(a[8], a[9] + ".attr.json").toPath(), o.attributes.getBytes("UTF-8"));
    System.out.println("guide: " + o.steps + " steps");
  }
}
