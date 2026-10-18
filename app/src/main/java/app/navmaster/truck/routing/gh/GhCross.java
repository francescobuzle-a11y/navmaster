package app.navmaster.truck.routing.gh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Routes between countries with GraphHopper: one graph per country, joined at the border
 * crossings (valichi.json of each country, tools/gh/build_crossings.py). The route goes in the
 * first country's graph to a crossing, from there in the next country's graph, and so on.
 *
 * The countries in between are chosen by the crossings (the shortest chain of neighbours, then
 * the one nearest the straight line); at each border a few crossings are tried, the nearest the
 * way (motorways first), with the real time in the graph to reach them. At the last border the
 * time on the other side counts too.
 *
 * Plain Java, no Android: tested on the computer with two small graphs (tools/guide, como+ticino).
 */
public final class GhCross {
  /** A point where a road crosses into a neighbouring country. */
  public static final class Crossing {
    public String to;
    public double lat, lon;
    public String hw = "", ref = "", name = "";
    /** Direction of travel there into the neighbour, degrees; NaN where the road is one-way the other way. */
    public double ab = Double.NaN;
    /** Direction of travel there coming back from the neighbour (NaN when one-way into it). */
    public double ba = Double.NaN;

    /** The same crossing seen from the neighbour's side: into this country. */
    public Crossing reversed(String from) {
      Crossing x = new Crossing();
      x.to = from;
      x.lat = lat;
      x.lon = lon;
      x.hw = hw;
      x.ref = ref;
      x.name = name;
      x.ab = ba;
      x.ba = ab;
      return x;
    }

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "%s %s %s %.5f,%.5f", to, hw, ref.isEmpty() ? name : ref, lat, lon);
    }
  }

  /** The graphs of the countries the driver downloaded. */
  public interface Graphs {
    /** The graph of [country] is there (downloaded, for this vehicle), without opening it. */
    boolean has(String country);

    /** The graph of [country], or null when it is not there. */
    GhEngine engine(String country);

    /** The border crossings of [country] (empty when not known). */
    List<Crossing> crossings(String country);
  }

  /** A route between countries: the piece in each country, and the whole route. */
  public static final class Trip {
    public final List<String> countries = new ArrayList<>();
    public final List<GhEngine> engines = new ArrayList<>();
    public final List<GhEngine.Result> pieces = new ArrayList<>();
    /** The whole route (points of all the pieces, the stops as waypoints). */
    public GhEngine.Result whole;
    public String error;
    public String note = "";
  }

  static final boolean DEBUG = Boolean.getBoolean("nm.cross.debug");

  /** Crossings tried at each border. */
  static final int CANDIDATES = 6;
  /** The longest the search to one candidate crossing may take (ms). */
  static final long CANDIDATE_TIMEOUT_MS = 20_000;
  /** Chains of countries tried. */
  static final int CHAINS = 2;

  /** The border crossings of a country, from its valichi.json (also the ones only usable coming back, [all]). */
  public static List<Crossing> read(File valichi) {
    List<Crossing> out = new ArrayList<>();
    for (Crossing x : readAll(valichi)) if (!Double.isNaN(x.ab)) out.add(x);
    return out;
  }

  /** Every crossing of valichi.json, both directions of travel (ab and ba). */
  public static List<Crossing> readAll(File valichi) {
    List<Crossing> out = new ArrayList<>();
    try {
      JsonNode root = new ObjectMapper().readTree(valichi);
      for (JsonNode c : root.path("crossings")) {
        Crossing x = new Crossing();
        x.to = c.path("to").asText();
        x.lat = c.path("lat").asDouble();
        x.lon = c.path("lon").asDouble();
        x.hw = c.path("hw").asText("");
        x.ref = c.path("ref").asText("");
        x.name = c.path("name").asText("");
        x.ab = c.path("ab").isNumber() ? c.path("ab").asDouble() : Double.NaN;
        x.ba = c.path("ba").isNumber() ? c.path("ba").asDouble() : Double.NaN;
        if (!x.to.isEmpty() && !(Double.isNaN(x.ab) && Double.isNaN(x.ba))) out.add(x);
      }
    } catch (Exception e) {
      // no crossings: the country is reached only when its graph covers the whole trip
    }
    return out;
  }

  /**
   * The route through [points] ([lat, lon], start, stops, destination), each in the country
   * [countries] (same order), for [spec]. [heading]: the direction of the vehicle at the start
   * (NaN when not known).
   */
  public static Trip route(Graphs graphs, List<double[]> points, List<String> countries, double heading, TruckSpec spec) {
    long t0 = System.currentTimeMillis();
    Trip trip = new Trip();
    // the pieces: in each country the points it has to go through (stops and crossings)
    List<String> pc = new ArrayList<>();
    List<List<double[]>> pp = new ArrayList<>();
    List<List<Double>> ph = new ArrayList<>();
    // the stops, as (piece, index in the piece)
    List<int[]> stops = new ArrayList<>();
    pc.add(countries.get(0));
    pp.add(new ArrayList<>(Collections.singletonList(points.get(0))));
    ph.add(new ArrayList<>(Collections.singletonList(heading)));
    stops.add(new int[] {0, 0});
    for (int i = 1; i < points.size(); i++) {
      String from = pc.get(pc.size() - 1), to = countries.get(i);
      double[] q = points.get(i);
      if (!from.equals(to)) {
        List<double[]> cur = pp.get(pp.size() - 1);
        double[] p = cur.get(cur.size() - 1);
        List<Crossing> chosen = crossings(graphs, from, p, to, q, spec, trip);
        if (chosen == null) {
          if (trip.error == null) trip.error = "nessun valico tra " + from + " e " + to;
          return trip;
        }
        String c = from;
        for (Crossing x : chosen) {
          // the piece in [c] ends at the crossing; the next country's piece starts there
          pp.get(pp.size() - 1).add(new double[] {x.lat, x.lon});
          ph.get(ph.size() - 1).add(x.ab);
          pc.add(x.to);
          pp.add(new ArrayList<>(Collections.singletonList(new double[] {x.lat, x.lon})));
          ph.add(new ArrayList<>(Collections.singletonList(x.ab)));
          trip.note += " " + c + "→" + x;
          c = x.to;
        }
      }
      pp.get(pp.size() - 1).add(q);
      ph.get(ph.size() - 1).add(Double.NaN);
      stops.add(new int[] {pp.size() - 1, pp.get(pp.size() - 1).size() - 1});
    }
    // each piece in its country's graph
    for (int k = 0; k < pc.size(); k++) {
      GhEngine e = graphs.engine(pc.get(k));
      if (e == null) {
        trip.error = "manca il grafo di " + pc.get(k);
        return trip;
      }
      // the pieces start or end at a border crossing: snapped onto the motorway's stub there
      TruckSpec piece = spec.copy();
      piece.borderPiece = true;
      GhEngine.Result r = e.routes(pp.get(k), ph.get(k), piece, 1).get(0);
      // a start or an end snapped into a dead end: as GraphHopper snaps by itself
      if (!r.ok()) r = e.routes(pp.get(k), ph.get(k), spec, 1).get(0);
      if (!r.ok()) {
        trip.error = pc.get(k) + ": " + r.error;
        return trip;
      }
      trip.countries.add(pc.get(k));
      trip.engines.add(e);
      trip.pieces.add(r);
    }
    trip.whole = join(trip.pieces, stops);
    trip.whole.computeMs = System.currentTimeMillis() - t0;
    trip.note = (pc.size() - 1) + " border(s):" + trip.note;
    return trip;
  }

  /** The pieces one after the other (the joining point once), with the stops as waypoints. */
  static GhEngine.Result join(List<GhEngine.Result> pieces, List<int[]> stops) {
    GhEngine.Result w = new GhEngine.Result();
    int n = 0;
    for (int k = 0; k < pieces.size(); k++) n += pieces.get(k).lat.length - (k > 0 ? 1 : 0);
    w.lat = new double[n];
    w.lon = new double[n];
    int[] start = new int[pieces.size()];
    int at = 0;
    for (int k = 0; k < pieces.size(); k++) {
      GhEngine.Result r = pieces.get(k);
      int offset = k == 0 ? 0 : at - 1;
      start[k] = offset;
      for (int i = k == 0 ? 0 : 1; i < r.lat.length; i++) {
        w.lat[offset + i] = r.lat[i];
        w.lon[offset + i] = r.lon[i];
      }
      at = offset + r.lat.length;
      w.distanceM += r.distanceM;
      w.timeMs += r.timeMs;
    }
    w.waypointIndex = new int[stops.size()];
    for (int s = 0; s < stops.size(); s++) {
      int[] st = stops.get(s);
      w.waypointIndex[s] = start[st[0]] + pieces.get(st[0]).waypointIndex[st[1]];
    }
    w.waypointIndex[0] = 0;
    w.waypointIndex[stops.size() - 1] = n - 1;
    return w;
  }

  /**
   * The crossings from country [a] (at point [p]) to country [b] (point [q]): one per border, in
   * order; null when the countries are not joined by crossings of downloaded countries.
   */
  static List<Crossing> crossings(Graphs graphs, String a, double[] p, String b, double[] q, TruckSpec spec, Trip trip) {
    List<List<String>> chains = chains(graphs, a, b, p, q);
    if (chains.isEmpty()) {
      trip.error = "nessuna catena di Paesi scaricati tra " + a + " e " + b;
      return null;
    }
    List<Crossing> best = null;
    double bestS = Double.MAX_VALUE;
    for (List<String> chain : chains) {
      double[] cur = p;
      double total = 0;
      List<Crossing> chosen = new ArrayList<>();
      boolean ok = true;
      for (int i = 0; i + 1 < chain.size() && ok; i++) {
        String x = chain.get(i), y = chain.get(i + 1);
        boolean last = y.equals(b);
        GhEngine ex = graphs.engine(x), ey = graphs.engine(y);
        List<Crossing> cands = new ArrayList<>();
        for (Crossing c : graphs.crossings(x)) if (c.to.equals(y)) cands.add(c);
        final double[] from = cur;
        cands.sort((u, v) -> Double.compare(score(from, u, q), score(from, v, q)));
        boolean byWeight = spec.route == GhEngine.ROUTE_MOTORWAY && !spec.shortest;
        if (cands.size() > CANDIDATES) {
          List<Crossing> kept = new ArrayList<>(cands.subList(0, CANDIDATES));
          // "more motorway": the two nearest motorway crossings are always among the ones tried
          if (byWeight) {
            int added = 0;
            for (Crossing c : cands.subList(CANDIDATES, cands.size())) {
              if (added >= 2) break;
              if (isMotorway(c)) { kept.add(c); added++; }
            }
          }
          cands = kept;
        }
        Crossing pick = null;
        double pickS = Double.MAX_VALUE, pickT = 0;
        // "more motorway": the best crossing on a motorway (or a trunk road), kept apart: it is
        // taken whenever it does not cost much more than the best of all (the driver asked to
        // stay on the motorway: a border crossed on a side road, with the exit and the entrance
        // it implies, is what this choice is meant to avoid)
        Crossing pickM = null;
        double pickMS = Double.MAX_VALUE, pickMT = 0;
        // the searches to the candidate crossings: limited in time, and after three crossings in
        // a row without a way the others are not tried (the vehicle cannot cross this border with
        // these choices: e.g. a lorry avoiding tolls into Austria)
        TruckSpec probe = spec.copy();
        probe.timeoutMs = spec.timeoutMs > 0 ? Math.min(spec.timeoutMs, CANDIDATE_TIMEOUT_MS) : CANDIDATE_TIMEOUT_MS;
        probe.borderPiece = true;
        int failedInARow = 0;
        for (Crossing c : cands) {
          if (failedInARow >= 3) break;
          if (DEBUG) System.out.println("CROSS try " + c);
          GhEngine.Result r1 = ex.routes(Arrays.asList(cur, new double[] {c.lat, c.lon}), Arrays.asList(i == 0 ? Double.NaN : chosen.get(i - 1).ab, c.ab), probe, 1).get(0);
          if (!r1.ok() && i == 0) {
            // the start snapped into a dead end: as GraphHopper snaps by itself
            TruckSpec plain = probe.copy();
            plain.borderPiece = false;
            r1 = ex.routes(Arrays.asList(cur, new double[] {c.lat, c.lon}), Arrays.asList(Double.NaN, c.ab), plain, 1).get(0);
          }
          if (!r1.ok()) {
            if (DEBUG) System.out.println("CROSS   no way to it: " + r1.error);
            failedInARow++;
            continue;
          }
          // "more motorway": the crossings compared by the weight of the profile (the roads off
          // the motorway and the ramps cost more), so that the border is crossed on the motorway
          // whenever the driver asked for it; otherwise by time
          double s;
          if (last) {
            GhEngine.Result r2 = ey.routes(Arrays.asList(new double[] {c.lat, c.lon}, q), Arrays.asList(c.ab, Double.NaN), probe, 1).get(0);
            if (!r2.ok()) {
              TruckSpec plain = probe.copy();
              plain.borderPiece = false;
              r2 = ey.routes(Arrays.asList(new double[] {c.lat, c.lon}, q), Arrays.asList(c.ab, Double.NaN), plain, 1).get(0);
            }
            if (!r2.ok()) {
              if (DEBUG) System.out.println("CROSS   no way from it: " + r2.error);
              failedInARow++;
              continue;
            }
            s = cost(r1, byWeight) + cost(r2, byWeight);
          } else {
            s = cost(r1, byWeight) + rest(c, q, spec);
            // "more motorway", a border before the last one: the roads after the crossing are not
            // computed here (only guessed), so a crossing off the motorway pays the exit and the
            // entrance it implies (about 1.2 km of ramps at the level's price), as the weighting would
            if (byWeight && !c.hw.startsWith("motorway") && !c.hw.startsWith("trunk"))
              s += OrsWeighting.LEVEL_LINK_S_PER_M[Math.max(1, Math.min(5, spec.motorwayLevel)) - 1] * 1200;
          }
          failedInARow = 0;
          if (DEBUG) System.out.println("CROSS " + c + ": " + Math.round(s) + (byWeight ? " (weight)" : " s") + " (" + Math.round(cost(r1, byWeight)) + " to it)");
          if (s < pickS) {
            pickS = s;
            pick = c;
            pickT = cost(r1, byWeight);
          }
          if (byWeight && isMotorway(c) && s < pickMS) {
            pickMS = s;
            pickM = c;
            pickMT = cost(r1, byWeight);
          }
        }
        if (byWeight && pickM != null && pick != pickM && pickMS <= pickS * MOTORWAY_CROSSING_TOLERANCE) {
          if (DEBUG) System.out.println("CROSS motorway crossing " + pickM + " kept over " + pick + " (" + Math.round(pickMS) + " vs " + Math.round(pickS) + ")");
          pick = pickM;
          pickS = pickMS;
          pickT = pickMT;
        }
        if (pick == null) {
          ok = false;
          break;
        }
        chosen.add(pick);
        total += last ? pickS : pickT;
        cur = new double[] {pick.lat, pick.lon};
      }
      if (ok && total < bestS) {
        bestS = total;
        best = chosen;
      }
    }
    if (best == null) trip.error = "nessun valico percorribile tra " + a + " e " + b + " con queste misure";
    return best;
  }

  /** "More motorway": a motorway (or trunk road) crossing is taken unless another one costs less than this share of it. */
  static final double MOTORWAY_CROSSING_TOLERANCE = 1.35;

  static boolean isMotorway(Crossing c) {
    return c.hw != null && (c.hw.startsWith("motorway") || c.hw.startsWith("trunk"));
  }

  /** The cost of a piece for the choice of the crossings: its weight (seconds, "more motorway") or its time. */
  static double cost(GhEngine.Result r, boolean byWeight) {
    return byWeight && r.weightS > 0 ? r.weightS : r.timeMs / 1000.0;
  }

  /** How good a crossing looks before any route: the way through it, main roads first. */
  static double score(double[] p, Crossing c, double[] q) {
    double f;
    switch (c.hw) {
      case "motorway": f = 1.0; break;
      case "trunk": f = 1.03; break;
      case "motorway_link": case "trunk_link": f = 1.06; break;
      case "primary": f = 1.06; break;
      case "primary_link": f = 1.08; break;
      case "secondary": f = 1.1; break;
      default: f = 1.15;
    }
    return (metres(p[0], p[1], c.lat, c.lon) + metres(c.lat, c.lon, q[0], q[1])) * f;
  }

  /** A guess of the time from a crossing to the destination (seconds), for the borders before the last one. */
  static double rest(Crossing c, double[] q, TruckSpec spec) {
    double kmh = Math.min(80, spec.topSpeedKmh) * 0.8;
    return metres(c.lat, c.lon, q[0], q[1]) * 1.25 / (kmh / 3.6);
  }

  /**
   * The chains of countries from [a] to [b] through countries whose graph is there, fewest
   * borders first, then nearest to the straight line; at most [CHAINS].
   */
  static List<List<String>> chains(Graphs graphs, String a, String b, double[] p, double[] q) {
    // breadth first, every chain of the fewest borders (and one more)
    List<List<String>> found = new ArrayList<>();
    Deque<List<String>> queue = new ArrayDeque<>();
    queue.add(Collections.singletonList(a));
    int shortest = Integer.MAX_VALUE;
    Map<String, Set<String>> neighbours = new HashMap<>();
    while (!queue.isEmpty() && found.size() < 20) {
      List<String> chain = queue.poll();
      if ((shortest != Integer.MAX_VALUE && chain.size() > shortest + 1) || chain.size() > 7) break;
      String last = chain.get(chain.size() - 1);
      if (last.equals(b)) {
        found.add(chain);
        shortest = Math.min(shortest, chain.size());
        continue;
      }
      Set<String> next = neighbours.get(last);
      if (next == null) {
        next = new HashSet<>();
        for (Crossing c : graphs.crossings(last)) if (graphs.has(c.to)) next.add(c.to);
        neighbours.put(last, next);
      }
      for (String y : next) {
        if (chain.contains(y)) continue;
        List<String> longer = new ArrayList<>(chain);
        longer.add(y);
        queue.add(longer);
      }
    }
    // nearest to the straight line: the best crossing of each border, in a straight line
    found.sort((u, v) -> Double.compare(straight(graphs, u, p, q), straight(graphs, v, p, q)));
    return found.size() > CHAINS ? found.subList(0, CHAINS) : found;
  }

  private static double straight(Graphs graphs, List<String> chain, double[] p, double[] q) {
    double total = 0;
    double[] cur = p;
    for (int i = 0; i + 1 < chain.size(); i++) {
      Crossing best = null;
      double bs = Double.MAX_VALUE;
      for (Crossing c : graphs.crossings(chain.get(i))) {
        if (!c.to.equals(chain.get(i + 1))) continue;
        double s = score(cur, c, q);
        if (s < bs) {
          bs = s;
          best = c;
        }
      }
      if (best == null) return Double.MAX_VALUE;
      total += metres(cur[0], cur[1], best.lat, best.lon);
      cur = new double[] {best.lat, best.lon};
    }
    return total + metres(cur[0], cur[1], q[0], q[1]);
  }

  static double metres(double lat1, double lon1, double lat2, double lon2) {
    return GhEngine.metres(lat1, lon1, lat2, lon2);
  }
}
