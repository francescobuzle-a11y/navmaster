import app.navmaster.truck.routing.gh.GhEngine;
import app.navmaster.truck.routing.gh.GhGuide;
import app.navmaster.truck.routing.gh.TruckSpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guidance computed by GraphHopper (GhGuide) on the test trips of one area, written as OSRM
 * route answers (one file per trip), to be read and checked on the computer.
 *
 * usage: java -cp gh.jar:classes GuideRun GRAPH_DIR MODELS_DIR trips.json AREA OUTDIR
 */
public class GuideRun {
  public static void main(String[] a) throws Exception {
    GhEngine e = GhEngine.open(new File(a[0]), new File(a[1]), false);
    String json = new String(Files.readAllBytes(new File(a[2]).toPath()), StandardCharsets.UTF_8);
    File out = new File(a[4]);
    out.mkdirs();
    // {"name": "x", "area": "y", "points": [[lat, lon], ...]}
    Matcher m = Pattern.compile("\\{\"name\":\\s*\"([^\"]+)\",\\s*\"area\":\\s*\"([^\"]+)\",\\s*\"points\":\\s*\\[(.*?)\\]\\}").matcher(json);
    while (m.find()) {
      if (!m.group(2).equals(a[3])) continue;
      List<double[]> pts = new ArrayList<>();
      Matcher p = Pattern.compile("\\[\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*\\]").matcher(m.group(3));
      while (p.find()) pts.add(new double[] {Double.parseDouble(p.group(1)), Double.parseDouble(p.group(2))});
      TruckSpec s = new TruckSpec();
      long t = System.currentTimeMillis();
      GhEngine.Result r = e.route(pts, null, s);
      if (!r.ok()) {
        System.out.println(m.group(1) + ": ERROR " + r.error);
        continue;
      }
      long t1 = System.currentTimeMillis();
      GhGuide.Output o = e.guide(r, s, r.waypointIndex, pts);
      long t2 = System.currentTimeMillis();
      Files.write(new File(out, m.group(1) + ".route.json").toPath(), o.osrm.getBytes(StandardCharsets.UTF_8));
      Files.write(new File(out, m.group(1) + ".attr.json").toPath(), o.attributes.getBytes(StandardCharsets.UTF_8));
      System.out.printf("%s: %.1f km, %d min, %d steps, route %d ms, guide %d ms%n", m.group(1), r.distanceM / 1000, r.timeMs / 60000, o.steps,
          t1 - t, t2 - t1);
    }
    e.close();
  }
}
