import app.navmaster.truck.routing.gh.GhEngine;
import app.navmaster.truck.routing.gh.TruckSpec;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Our GraphHopper against openrouteservice itself, on the same OpenStreetMap data: openrouteservice
 * (its official Docker image) is built on GitHub with the same extract as our graph, then both get
 * the same trips, asked as the app asks them (OrsRouting.kt: lorry 4 m, 2.55 m, 16.5 m, 40 t,
 * 11.5 t per axle, top speed 90; camper 3.2 m, 3.5 t, 110 km/h on driving-car), between points
 * on the roads (the middle of random roads open to both vehicles, so that both snap to the same
 * place). For each trip: length, time and how much of each route lies on the other (points within
 * 15 m). A route is "the same" when 99% of it lies on the other and the lengths differ by less
 * than 1%.
 *
 * The trips that are not the same are also written with both lines (OUT.tsv → OUT-diff.jsonl:
 * {"case", "from", "to", "ors": [[lat, lon]…], "gh": [[lat, lon]…]}), to be studied on the computer.
 *
 * usage: java -cp gh.jar:classes OrsCompare GRAPH MODELS ORS_URL TRIPS OUT.tsv [seed]
 */
public class OrsCompare {
  static final String[][] CASES = {
      {"hgv", "recommended"}, {"hgv", "fastest"}, {"hgv", "shortest"}, {"car", "fastest"}, {"car", "shortest"}};

  public static void main(String[] a) throws Exception {
    GhEngine e = GhEngine.open(new File(a[0]), new File(a[1]), false);
    String ors = a[2];
    int trips = Integer.parseInt(a[3]);
    long seed = a.length > 5 ? Long.parseLong(a[5]) : 7;
    java.lang.reflect.Field f = GhEngine.class.getDeclaredField("hopper");
    f.setAccessible(true);
    GraphHopper gh = (GraphHopper) f.get(e);
    BaseGraph g = gh.getBaseGraph();
    BooleanEncodedValue hgvAcc = gh.getEncodingManager().getBooleanEncodedValue(GhEngine.ORS_HGV_ACCESS);
    BooleanEncodedValue carAcc = gh.getEncodingManager().getBooleanEncodedValue(GhEngine.ORS_CAR_ACCESS);
    Random rnd = new Random(seed);
    List<double[]> points = new ArrayList<>();
    while (points.size() < trips * 2) {
      EdgeIteratorState edge = g.getEdgeIteratorState(rnd.nextInt(g.getEdges()), Integer.MIN_VALUE);
      if (edge.getDistance() < 30 || !(edge.get(hgvAcc) || edge.getReverse(hgvAcc)) || !(edge.get(carAcc) || edge.getReverse(carAcc))) continue;
      PointList pl = edge.fetchWayGeometry(FetchMode.ALL);
      int i = pl.size() / 2;
      if (pl.size() == 2) points.add(new double[] {(pl.getLat(0) + pl.getLat(1)) / 2, (pl.getLon(0) + pl.getLon(1)) / 2});
      else points.add(new double[] {pl.getLat(i), pl.getLon(i)});
    }
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    int[] same = new int[CASES.length], close = new int[CASES.length], other = new int[CASES.length], none = new int[CASES.length];
    try (PrintWriter out = new PrintWriter(new FileWriter(a[4]));
         PrintWriter diff = new PrintWriter(new FileWriter(a[4].replaceAll("\\.tsv$", "") + "-diff.jsonl"))) {
      out.println("case\tfrom\tto\tors_km\tgh_km\tors_min\tgh_min\tors_on_gh\tgh_on_ors\tverdict");
      for (int t = 0; t < trips; t++) {
        double[] p = points.get(2 * t), q = points.get(2 * t + 1);
        for (int c = 0; c < CASES.length; c++) {
          boolean hgv = CASES[c][0].equals("hgv");
          String pref = CASES[c][1];
          TruckSpec s = spec(hgv, pref);
          double[][] orsLine;
          double orsM, orsS;
          try {
            String body = body(p, q, hgv, pref, s);
            HttpRequest req = HttpRequest.newBuilder(URI.create(ors + "/ors/v2/directions/" + (hgv ? "driving-hgv" : "driving-car") + "/geojson"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> rsp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (rsp.statusCode() != 200) {
              orsLine = null;
              orsM = orsS = Double.NaN;
            } else {
              String txt = rsp.body();
              orsLine = coords(txt);
              orsM = num(txt, "\"distance\":");
              orsS = num(txt, "\"duration\":");
            }
          } catch (Exception ex) {
            orsLine = null;
            orsM = orsS = Double.NaN;
          }
          GhEngine.Result r = e.route(Arrays.asList(p, q), Arrays.asList(Double.NaN, Double.NaN), s);
          String verdict;
          double a1 = Double.NaN, a2 = Double.NaN;
          if (orsLine == null && !r.ok()) {
            verdict = "none_both";
            none[c]++;
          } else if (orsLine == null || !r.ok()) {
            verdict = orsLine == null ? "only_gh" : "only_ors";
            other[c]++;
          } else {
            double[][] ghLine = new double[r.lat.length][];
            for (int i = 0; i < r.lat.length; i++) ghLine[i] = new double[] {r.lat[i], r.lon[i]};
            a1 = on(orsLine, ghLine);
            a2 = on(ghLine, orsLine);
            double dd = Math.abs(r.distanceM - orsM) / Math.max(1, orsM);
            if (a1 >= 0.99 && a2 >= 0.99 && dd < 0.01) {
              verdict = "same";
              same[c]++;
            } else if (a1 >= 0.9 && a2 >= 0.9 && dd < 0.05) {
              verdict = "close";
              close[c]++;
            } else {
              verdict = "different";
              other[c]++;
            }
          }
          out.printf(Locale.ROOT, "%s-%s\t%.6f,%.6f\t%.6f,%.6f\t%.3f\t%.3f\t%.2f\t%.2f\t%.3f\t%.3f\t%s%n", CASES[c][0], pref,
              p[0], p[1], q[0], q[1], orsM / 1000, r.distanceM / 1000, orsS / 60, r.timeMs / 60000.0, a1, a2, verdict);
          out.flush();
          if (!verdict.equals("same") && !verdict.equals("none_both")) {
            StringBuilder j = new StringBuilder();
            j.append(String.format(Locale.ROOT, "{\"case\":\"%s-%s\",\"from\":[%.6f,%.6f],\"to\":[%.6f,%.6f],\"verdict\":\"%s\",\"ors\":%s,\"gh\":%s}",
                CASES[c][0], pref, p[0], p[1], q[0], q[1], verdict, line(orsLine), r.ok() ? line(r.lat, r.lon) : "[]"));
            diff.println(j);
            diff.flush();
          }
        }
      }
    }
    for (int c = 0; c < CASES.length; c++)
      System.out.printf(Locale.ROOT, "ORSCOMPARE %s-%s: same %d, close %d, different %d, no route %d%n", CASES[c][0], CASES[c][1],
          same[c], close[c], other[c], none[c]);
    e.close();
  }

  static TruckSpec spec(boolean hgv, String pref) {
    TruckSpec s = new TruckSpec();
    s.hgv = hgv;
    if (!hgv) {
      s.heightM = 3.2;
      s.widthM = 2.3;
      s.lengthM = 7.5;
      s.weightT = 3.5;
      s.axleLoadT = 2;
      s.topSpeedKmh = 110;
    }
    s.route = pref.equals("shortest") ? GhEngine.ROUTE_SHORT : pref.equals("recommended") ? GhEngine.ROUTE_MOTORWAY : GhEngine.ROUTE_FAST;
    return s;
  }

  /** The request of the app (OrsRouting.kt). */
  static String body(double[] p, double[] q, boolean hgv, String pref, TruckSpec s) {
    StringBuilder b = new StringBuilder();
    b.append(String.format(Locale.ROOT, "{\"coordinates\":[[%.7f,%.7f],[%.7f,%.7f]],\"preference\":\"%s\",\"units\":\"m\",\"instructions\":false,\"geometry\":true",
        p[1], p[0], q[1], q[0], pref));
    if (hgv) {
      b.append(String.format(Locale.ROOT, ",\"options\":{\"vehicle_type\":\"%s\",\"profile_params\":{\"restrictions\":{\"height\":%s,\"width\":%s,\"length\":%s,\"weight\":%s,\"axleload\":%s}}}",
          s.vehicleType, s.heightM, s.widthM, s.lengthM, s.weightT, s.axleLoadT));
    }
    b.append(String.format(Locale.ROOT, ",\"maximum_speed\":%s}", Math.max(80, s.topSpeedKmh)));
    return b.toString();
  }

  static String line(double[][] l) {
    if (l == null) return "[]";
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < l.length; i++) b.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "[%.6f,%.6f]", l[i][0], l[i][1]));
    return b.append("]").toString();
  }

  static String line(double[] lat, double[] lon) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < lat.length; i++) b.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "[%.6f,%.6f]", lat[i], lon[i]));
    return b.append("]").toString();
  }

  private static final Pattern PAIR = Pattern.compile("\\[(-?[0-9.]+),(-?[0-9.]+)\\]");

  static double[][] coords(String txt) {
    int i = txt.indexOf("\"coordinates\":[");
    int j = txt.indexOf("]]", i);
    Matcher m = PAIR.matcher(txt.substring(i + 14, j + 2));
    List<double[]> out = new ArrayList<>();
    while (m.find()) out.add(new double[] {Double.parseDouble(m.group(2)), Double.parseDouble(m.group(1))});
    return out.toArray(new double[0][]);
  }

  static double num(String txt, String key) {
    int i = txt.indexOf(key);
    if (i < 0) return Double.NaN;
    i += key.length();
    int j = i;
    while (j < txt.length() && "-0123456789.eE".indexOf(txt.charAt(j)) >= 0) j++;
    return Double.parseDouble(txt.substring(i, j));
  }

  /** Share of the length of [a] lying within 15 m of [b]. */
  static double on(double[][] a, double[][] b) {
    double total = 0, near = 0;
    for (int i = 1; i < a.length; i++) {
      double len = dist(a[i - 1], a[i]);
      // sample the segment every 10 m
      int n = Math.max(1, (int) (len / 10));
      int ok = 0;
      for (int k = 0; k < n; k++) {
        double t = (k + 0.5) / n;
        double[] x = {a[i - 1][0] + (a[i][0] - a[i - 1][0]) * t, a[i - 1][1] + (a[i][1] - a[i - 1][1]) * t};
        if (toLine(x, b) <= 15) ok++;
      }
      total += len;
      near += len * ok / n;
    }
    return total == 0 ? 1 : near / total;
  }

  static double toLine(double[] x, double[][] b) {
    double best = Double.MAX_VALUE;
    double k = Math.cos(Math.toRadians(x[0])) * 111_195, m = 111_195;
    for (int i = 1; i < b.length; i++) {
      double ax = (b[i - 1][1] - x[1]) * k, ay = (b[i - 1][0] - x[0]) * m;
      double bx = (b[i][1] - x[1]) * k, by = (b[i][0] - x[0]) * m;
      double dx = bx - ax, dy = by - ay;
      double l2 = dx * dx + dy * dy;
      double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
      double px = ax + t * dx, py = ay + t * dy;
      best = Math.min(best, Math.sqrt(px * px + py * py));
    }
    return best;
  }

  static double dist(double[] p, double[] q) {
    double k = Math.cos(Math.toRadians((p[0] + q[0]) / 2)) * 111_195;
    double dx = (q[1] - p[1]) * k, dy = (q[0] - p[0]) * 111_195;
    return Math.sqrt(dx * dx + dy * dy);
  }
}
