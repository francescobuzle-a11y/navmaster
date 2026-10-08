package app.navmaster.truck.routing.gh;

import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.Country;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.ev.Surface;
import com.graphhopper.routing.ev.Toll;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The guidance of a GraphHopper route worked out on the tablet from the graph itself, without
 * Valhalla: manoeuvres, Italian sentences for the voice and the screen, lanes, exit numbers and
 * signs, speed limits. The result is written as an OSRM route answer with the extensions that
 * Valhalla writes (bannerInstructions, voiceInstructions, annotation with maxspeed), the format
 * Ferrostar reads and that the rest of the app was built on; and, for the analysis of the route
 * (tolls, tunnels, countries, junctions, OSM ways), as the answer of Valhalla's trace_attributes.
 *
 * The sentences follow the forms Valhalla writes in Italian (its narrative, MIT licence), which
 * the app turns into spoken Italian (SpeechIt): "Svolta a destra su Via Roma.", "Prendi l'uscita 5
 * a destra per RA1 verso Tangenziale.", "Entra nella rotonda e prendi la 2a uscita per X.".
 *
 * Plain Java, no Android: tested on the computer against Valhalla's answers on the same trips
 * (tools/guide).
 */
public final class GhGuide {
  /** The route answers. */
  public static final class Output {
    /** OSRM route answer (code, routes[1], waypoints), as Valhalla's with format=osrm. */
    public String osrm;
    /** trace_attributes answer (shape, admins, edges) for RouteAnalysis. */
    public String attributes;
    public int steps;
  }

  private final BaseGraph g;
  private final EdgeExplorer explorer;
  private final EnumEncodedValue<RoadClass> roadClass;
  private final BooleanEncodedValue link, roundabout, access, anyAccess;
  private final EnumEncodedValue<RoadEnvironment> environment;
  private final EnumEncodedValue<Toll> toll;
  private final EnumEncodedValue<Surface> surface;
  private final IntEncodedValue lanes, wayId;
  private final EnumEncodedValue<Country> country;
  private final DecimalEncodedValue maxSpeed;

  /** The graph has what the guidance needs (built with NmImport since 10/2026). */
  public static boolean supported(GraphHopper hopper) {
    EncodedValueLookup ev = hopper.getEncodingManager();
    for (String k : new String[] {"osm_way_id", "lanes", "country", "roundabout", "max_speed", "surface", "toll",
        "road_environment", "road_class", "road_class_link", "ors_car_access", "ors_hgv_access"})
      if (!ev.hasEncodedValue(k)) return false;
    return true;
  }

  public GhGuide(GraphHopper hopper, boolean hgv) {
    EncodedValueLookup ev = hopper.getEncodingManager();
    g = hopper.getBaseGraph();
    explorer = g.createEdgeExplorer();
    roadClass = ev.getEnumEncodedValue("road_class", RoadClass.class);
    link = ev.getBooleanEncodedValue("road_class_link");
    roundabout = ev.getBooleanEncodedValue("roundabout");
    access = ev.getBooleanEncodedValue(hgv ? "ors_hgv_access" : "ors_car_access");
    anyAccess = ev.getBooleanEncodedValue("ors_car_access");
    environment = ev.getEnumEncodedValue("road_environment", RoadEnvironment.class);
    toll = ev.getEnumEncodedValue("toll", Toll.class);
    surface = ev.getEnumEncodedValue("surface", Surface.class);
    lanes = ev.getIntEncodedValue("lanes");
    wayId = ev.getIntEncodedValue("osm_way_id");
    country = ev.getEnumEncodedValue("country", Country.class);
    maxSpeed = ev.getDecimalEncodedValue("max_speed");
  }

  // ------------------------------------------------------------------------------------ the path

  /** One road (graph edge) of the route, in the direction driven. */
  static final class Seg {
    /** The graph the road is in (a route between countries joins the graphs of the countries). */
    GhGuide src;
    EdgeIteratorState e;
    int edge, baseNode, adjNode;
    int p0, p1;
    double timeS;
    RoadClass rc;
    boolean link, roundabout, tunnel, bridge, ferry, toll;
    String surface, country;
    int lanes;
    long way;
    String name, ref, dest, destRef, junctionName, junctionRef, turnLanes, nodes;
    double maxSpeed;

    String identity() {
      return !ref.isEmpty() ? ref : name;
    }

    boolean fast() {
      return !link && (rc == RoadClass.MOTORWAY || rc == RoadClass.TRUNK);
    }
  }

  /** A road leaving a junction of the route. */
  static final class Branch {
    int edge;
    double bearing;
    boolean canOut, canIn, roundabout, link, path, incoming;
    /** Open to cars (an exit of a roundabout counts for the driver even when closed to lorries). */
    boolean canOutAny, canInAny;
    RoadClass rc;
    int lanes;
    String name, service;

    /** A road a driver counts as an exit of a roundabout. */
    boolean countsAsExit() {
      if (roundabout || incoming) return false;
      // every road one can drive into counts (also the entrance of a car park), as drivers count them
      if (rc == RoadClass.SERVICE) return !service.equals("emergency_access");
      return rc != RoadClass.FOOTWAY && rc != RoadClass.PATH && rc != RoadClass.CYCLEWAY && rc != RoadClass.PEDESTRIAN
          && rc != RoadClass.STEPS && rc != RoadClass.BRIDLEWAY;
    }
  }

  /** A manoeuvre: where (point index), what, onto which road. */
  static final class Man {
    int at;
    int seg; // index of the road taken (first seg of the step)
    String type;
    String modifier;
    int exitCount;
    String rotary;
    double bearingBefore, bearingAfter, angle;
    String exitNumber, exitName, branch, toward;
    double degrees = Double.NaN;
    int legEnd = -1; // for "arrive": the waypoint
    List<String[]> lanes; // [indications;..., active(0/1), activeDirection]
  }

  static final boolean DEBUG = Boolean.getBoolean("nm.guide.debug");

  /** A roundabout with a name of its own ("Rotonda della Perla Verde"), not a street's. */
  private static final java.util.regex.Pattern ROTARY = java.util.regex.Pattern.compile(
      "^(Rotonda|Rotatoria|Rondò|Rondo|Piazza|Piazzale|Largo|Rond-point|Rond point|Kreisverkehr|Kreisel|Glorieta|Rotunda|Roundabout|Circus|Place|Platz|Plac|Rotonde|Kruispunt|Okružní|Körforgalom|Sens giratoire)\\b",
      java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

  private double[] lat, lon, cum;
  private List<Seg> segs;

  private static double dist(double la1, double lo1, double la2, double lo2) {
    double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
    double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
    double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * 6_371_000.0 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
  }

  static double bearing(double la1, double lo1, double la2, double lo2) {
    double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2), dl = Math.toRadians(lo2 - lo1);
    double y = Math.sin(dl) * Math.cos(p2);
    double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
    return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
  }

  /** -180..180, positive = clockwise (right). */
  static double turn(double from, double to) {
    double d = (to - from) % 360;
    if (d > 180) d -= 360;
    if (d <= -180) d += 360;
    return d;
  }

  /** The direction of travel arriving at point [i] (over the last ~[m] metres). */
  private double bearingIn(int i, double m) {
    int j = i;
    while (j > 0 && cum[i] - cum[j] < m) j--;
    if (j == i) return bearingOut(i, m);
    // skip identical points
    return bearing(lat[j], lon[j], lat[i], lon[i]);
  }

  /** The direction of travel leaving point [i] (over the next ~[m] metres). */
  private double bearingOut(int i, double m) {
    int j = i;
    int n = lat.length;
    while (j < n - 1 && cum[j] - cum[i] < m) j++;
    if (j == i) return i > 0 ? bearing(lat[i - 1], lon[i - 1], lat[i], lon[i]) : 0;
    return bearing(lat[i], lon[i], lat[j], lon[j]);
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o).trim();
  }

  /**
   * The roads of a path of this graph, added to [segs]; [offset]: where the path's first point
   * is in the whole route's points.
   */
  private void read(List<Seg> segs, List<PathDetail> keys, List<PathDetail> times, int offset) {
    int first = segs.size();
    for (PathDetail d : keys) {
      Seg s = new Seg();
      s.src = this;
      int key = ((Number) d.getValue()).intValue();
      s.e = g.getEdgeIteratorStateForKey(key);
      s.edge = s.e.getEdge();
      s.baseNode = s.e.getBaseNode();
      s.adjNode = s.e.getAdjNode();
      s.p0 = d.getFirst() + offset;
      s.p1 = d.getLast() + offset;
      s.rc = s.e.get(roadClass);
      s.link = s.e.get(link);
      s.roundabout = s.e.get(roundabout);
      RoadEnvironment env = s.e.get(environment);
      s.tunnel = env == RoadEnvironment.TUNNEL;
      s.bridge = env == RoadEnvironment.BRIDGE;
      s.ferry = env == RoadEnvironment.FERRY;
      s.toll = s.e.get(toll) != Toll.MISSING && s.e.get(toll) != Toll.NO;
      s.surface = surfaceName(s.e.get(surface));
      Country c = s.e.get(country);
      s.country = c == null || c == Country.MISSING ? null : c.getAlpha2();
      s.way = s.e.get(wayId);
      s.name = str(s.e.getValue("street_name"));
      s.ref = refs(str(s.e.getValue("street_ref")));
      s.dest = places(str(s.e.getValue("street_destination")));
      s.destRef = refs(str(s.e.getValue("street_destination_ref")));
      s.junctionName = str(s.e.getValue("motorway_junction"));
      s.junctionRef = str(s.e.getValue(GhEngine.KV_JUNCTION_REF));
      s.turnLanes = str(s.e.getValue(GhEngine.KV_TURN_LANES));
      s.nodes = str(s.e.getValue(GhEngine.KV_NODES));
      double ms = s.e.get(maxSpeed);
      s.maxSpeed = Double.isInfinite(ms) || ms <= 0 ? Double.NaN : ms;
      int total = s.e.get(lanes);
      String dir = str(s.e.getValue(GhEngine.KV_LANES_DIR));
      int perDir;
      if (!dir.isEmpty() && dir.matches("\\d+")) perDir = Integer.parseInt(dir);
      else if (total <= 0) perDir = 1;
      else if (s.e.getReverse(access) && s.e.get(access) && !s.link && !s.roundabout) perDir = Math.max(1, total / 2);
      else perDir = total;
      s.lanes = Math.max(1, perDir);
      segs.add(s);
    }
    // the times of GraphHopper's own pieces (two virtual edges where a stop splits an edge)
    if (times != null && segs.size() > first) {
      int k = first;
      for (PathDetail t : times) {
        while (k < segs.size() - 1 && segs.get(k).p1 <= t.getFirst() + offset) k++;
        segs.get(k).timeS += ((Number) t.getValue()).doubleValue() / 1000.0;
      }
    }
  }

  private static String surfaceName(Surface s) {
    if (s == null) return "paved";
    switch (s) {
      case ASPHALT:
      case CONCRETE:
        return "paved_smooth";
      case PAVED:
        return "paved";
      case PAVING_STONES:
      case COBBLESTONE:
      case WOOD:
        return "paved_rough";
      case COMPACTED:
      case FINE_GRAVEL:
        return "compacted";
      case GRAVEL:
        return "gravel";
      case DIRT:
      case GROUND:
      case GRASS:
      case SAND:
      case UNPAVED:
        return "dirt";
      default:
        return "paved";
    }
  }

  /** "A14;E 55" (GraphHopper stores "A14, E 55") → "A14; E 55" (as Valhalla writes the refs). */
  private static String refs(String r) {
    if (r.isEmpty()) return r;
    StringBuilder b = new StringBuilder();
    for (String p : r.split(";|,")) {
      String t = p.trim();
      if (t.isEmpty()) continue;
      if (b.length() > 0) b.append("; ");
      b.append(t);
    }
    return b.toString();
  }

  /** "Bologna;Ancona" (GraphHopper stores "Bologna, Ancona") → "Bologna/Ancona". */
  private static String places(String r) {
    if (r.isEmpty()) return r;
    StringBuilder b = new StringBuilder();
    for (String p : r.split(";|, ")) {
      String t = p.trim();
      if (t.isEmpty()) continue;
      if (b.length() > 0) b.append("/");
      b.append(t);
    }
    return b.toString();
  }

  // ------------------------------------------------------------------------- the junctions

  /** The roads at the node between seg [a] and seg [b] (where the route goes from a to b). */
  private List<Branch> branches(Seg a, Seg b) {
    if (a.src != this) return a.src.branchesHere(a, b);
    List<Branch> out = new ArrayList<>();
    // where two countries' graphs join (a border crossing, in the middle of a road): no other road
    if (b.src != a.src) return out;
    int node = a.adjNode;
    EdgeIterator it = explorer.setBaseNode(node);
    while (it.next()) {
      Branch br = new Branch();
      br.edge = it.getEdge();
      br.canOut = it.get(access);
      br.canIn = it.getReverse(access);
      br.roundabout = it.get(roundabout);
      br.link = it.get(link);
      br.rc = it.get(roadClass);
      br.lanes = Math.max(1, it.get(lanes));
      br.name = str(it.getValue("street_name"));
      br.service = str(it.getValue(GhEngine.KV_SERVICE));
      br.canOutAny = it.get(anyAccess);
      br.canInAny = it.getReverse(anyAccess);
      br.incoming = it.getEdge() == a.edge && it.getAdjNode() == a.baseNode;
      br.path = it.getEdge() == b.edge && it.getAdjNode() == b.adjNode;
      PointList pl = it.fetchWayGeometry(FetchMode.ALL);
      double la0 = pl.getLat(0), lo0 = pl.getLon(0);
      double la = pl.getLat(pl.size() - 1), lo = pl.getLon(pl.size() - 1);
      double acc = 0;
      for (int i = 1; i < pl.size(); i++) {
        acc += dist(pl.getLat(i - 1), pl.getLon(i - 1), pl.getLat(i), pl.getLon(i));
        la = pl.getLat(i);
        lo = pl.getLon(i);
        if (acc >= 15) break;
      }
      br.bearing = bearing(la0, lo0, la, lo);
      out.add(br);
    }
    return out;
  }

  /** [branches] in this seg's own graph. */
  private List<Branch> branchesHere(Seg a, Seg b) {
    return branches(a, b);
  }

  private static boolean isServiceLike(RoadClass rc) {
    return rc == RoadClass.SERVICE || rc == RoadClass.TRACK || rc == RoadClass.FOOTWAY || rc == RoadClass.PATH
        || rc == RoadClass.CYCLEWAY || rc == RoadClass.PEDESTRIAN || rc == RoadClass.STEPS || rc == RoadClass.BRIDLEWAY;
  }

  private static String modifier(double angle) {
    double a = Math.abs(angle);
    String side = angle > 0 ? "right" : "left";
    if (a < 20) return "straight";
    if (a < 50) return "slight " + side;
    if (a <= 140) return side;
    if (a < 170) return "sharp " + side;
    return "uturn";
  }

  private static String side(double d) {
    return d >= 0 ? "slight right" : "slight left";
  }

  // ------------------------------------------------------------------------------ the guidance

  /**
   * The guidance of [path] (asked with the path details edge_key and time). [breaks]: indices in
   * the path's points of the stops (start and end included, in order); [stopNames] the names of
   * the requested locations; [requested] the requested points [lat, lon] of the stops (for the side of
   * arrival); [hgv]: lorry.
   */
  public Output guide(ResponsePath path, int[] breaks, List<double[]> requested) {
    return guide(java.util.Collections.singletonList(this), java.util.Collections.singletonList(path), breaks, requested);
  }

  /**
   * The guidance of a route made of [paths] computed in different graphs ([guides], one per path:
   * the countries of a trip abroad), each path starting where the one before ends (a border
   * crossing). [breaks]: the stops in the points of the whole route (the paths one after the other,
   * the point where two join counted once).
   */
  public static Output guide(List<GhGuide> guides, List<ResponsePath> paths, int[] breaks, List<double[]> requested) {
    return guides.get(0).guideAll(guides, paths, breaks, requested);
  }

  private Output guideAll(List<GhGuide> guides, List<ResponsePath> paths, int[] breaks, List<double[]> requested) {
    int n = 0;
    for (int k = 0; k < paths.size(); k++) n += paths.get(k).getPoints().size() - (k > 0 ? 1 : 0);
    lat = new double[n];
    lon = new double[n];
    cum = new double[n];
    segs = new ArrayList<>();
    int at = 0;
    for (int k = 0; k < paths.size(); k++) {
      PointList pl = paths.get(k).getPoints();
      int offset = k == 0 ? 0 : at - 1;
      for (int i = k == 0 ? 0 : 1; i < pl.size(); i++) {
        int j = offset + i;
        lat[j] = pl.getLat(i);
        lon[j] = pl.getLon(i);
        if (j > 0) cum[j] = cum[j - 1] + dist(lat[j - 1], lon[j - 1], lat[j], lon[j]);
      }
      at = offset + pl.size();
      Map<String, List<PathDetail>> det = paths.get(k).getPathDetails();
      guides.get(k).read(segs, det.get("edge_key"), det.get("time"), offset);
    }
    // time per point segment, from the time of each road
    double[] segTime = new double[Math.max(0, n - 1)];
    double[] segMax = new double[Math.max(0, n - 1)];
    java.util.Arrays.fill(segMax, Double.NaN);
    for (Seg s : segs) {
      double len = cum[s.p1] - cum[s.p0];
      for (int i = s.p0; i < s.p1; i++) {
        double d = cum[i + 1] - cum[i];
        segTime[i] = len > 0 ? s.timeS * d / len : 0;
        segMax[i] = s.maxSpeed;
      }
    }
    List<Man> mans = maneuvers(breaks, requested);
    Output o = new Output();
    o.osrm = osrm(mans, breaks, requested, segTime, segMax);
    o.attributes = attributes();
    o.steps = mans.size();
    return o;
  }

  private List<Man> maneuvers(int[] breaks, List<double[]> requested) {
    List<Man> out = new ArrayList<>();
    java.util.Set<Integer> stopAt = new java.util.HashSet<>();
    for (int b : breaks) stopAt.add(b);
    Man dep = new Man();
    dep.at = 0;
    dep.seg = 0;
    dep.type = "depart";
    dep.bearingAfter = bearingOut(0, 20);
    out.add(dep);
    int i = 1;
    while (i < segs.size()) {
      Seg a = segs.get(i - 1), b = segs.get(i);
      int at = b.p0;
      // a stop inside the route: arrive, then depart again
      if (stopAt.contains(at) && at != 0 && at != lat.length - 1) {
        out.add(arrive(at, requested, indexOf(breaks, at)));
        Man d = new Man();
        d.at = at;
        d.seg = i;
        d.type = "depart";
        d.bearingAfter = bearingOut(at, 20);
        out.add(d);
        i++;
        continue;
      }
      if (a.edge == b.edge && a.adjNode == b.adjNode) {
        // the same road split by a stop's virtual node: nothing happens
        i++;
        continue;
      }
      double inB = bearingIn(at, 20), outB = bearingOut(at, 20);
      double angle = turn(inB, outB);
      List<Branch> brs = branches(a, b);
      if (DEBUG) System.out.println(String.format(Locale.ROOT, "GUIDE @%.0f m p%d %s%s%s -> %s%s%s angle %.0f | %s", cum[at], at, a.rc, a.link ? "/link" : "",
          a.roundabout ? "/rb" : "", b.rc, b.link ? "/link" : "", b.roundabout ? "/rb" : "", angle, describe(brs) + " in " + Math.round(inB)) + " '" + a.identity() + "'->'" + b.identity() + "'");
      // the roads one could take instead (not back where we came from)
      List<Branch> opts = new ArrayList<>();
      for (Branch br : brs) if (br.canOut && !br.path && !br.incoming && !isServiceLike(br.rc)) opts.add(br);
      // ---- roundabouts
      if (b.roundabout && !a.roundabout) {
        int j = i;
        while (j < segs.size() && segs.get(j).roundabout) j++;
        Man m = new Man();
        m.at = at;
        m.seg = Math.min(j, segs.size() - 1);
        m.bearingBefore = inB;
        m.rotary = ROTARY.matcher(b.name).find() ? b.name : null;
        m.type = m.rotary != null ? "rotary" : "roundabout";
        // the exits passed: every road leaving the roundabout at the nodes after the entry, up to
        // the node where the route leaves it (the road taken included)
        int count = 0;
        for (int k = i + 1; k <= j && k < segs.size(); k++) {
          Seg x = segs.get(k - 1), y = segs.get(k);
          List<Branch> rb = branches(x, y);
          // a road out of the roundabout, also when closed to traffic (private): what the driver sees;
          // not the one-way roads that only lead into it. At the node where the route leaves, the
          // road taken is one of them
          for (Branch br : rb) if ((br.canOut || br.canOutAny || !br.canInAny) && br.countsAsExit()) count++;
          if (DEBUG) System.out.println("GUIDE   roundabout node p" + y.p0 + ": " + describe(rb));
        }
        m.exitCount = Math.max(1, count);
        if (j < segs.size() && segs.get(j).link) {
          String t = m.type;
          m.type = "on ramp";
          ramp(m, j);
          m.type = t;
        }
        if (j < segs.size()) {
          int exitAt = segs.get(j).p0;
          double exitOut = bearingOut(exitAt, 20);
          m.angle = turn(inB, exitOut);
          m.bearingAfter = bearingOut(at, 20);
          m.modifier = modifier(m.angle).replace("uturn", "sharp left");
          m.degrees = ((inB - exitOut) % 360 + 360) % 360;
          out.add(m);
          Man x = new Man();
          x.at = exitAt;
          x.seg = j;
          x.type = m.rotary != null ? "exit rotary" : "exit roundabout";
          x.bearingBefore = bearingIn(exitAt, 15);
          x.bearingAfter = exitOut;
          x.angle = turn(x.bearingBefore, exitOut);
          x.modifier = modifier(x.angle).replace("uturn", "sharp right");
          x.degrees = m.degrees;
          out.add(x);
          i = j + 1;
        } else {
          m.modifier = "straight";
          m.bearingAfter = bearingOut(at, 20);
          out.add(m);
          i = j;
        }
        continue;
      }
      if (b.roundabout) {
        i++;
        continue;
      }
      Man m = new Man();
      m.at = at;
      m.seg = i;
      m.bearingBefore = inB;
      m.bearingAfter = outB;
      m.angle = angle;
      boolean uturn = a.edge == b.edge;
      // the straightest road among all the ones that can be taken (ours included)
      double ours = Math.abs(angle);
      Branch straightest = null;
      double best = ours;
      for (Branch br : opts) {
        double d = Math.abs(turn(inB, br.bearing));
        if (d < best) {
          best = d;
          straightest = br;
        }
      }
      boolean oursStraightest = straightest == null;
      // the other road closest in direction to ours (for "keep left / right")
      Branch closest = null;
      double cd = 999;
      for (Branch br : opts) {
        double d = Math.abs(turn(outB, br.bearing));
        if (d < cd) {
          cd = d;
          closest = br;
        }
      }
      if (uturn) {
        m.type = "turn";
        m.modifier = "uturn";
      } else if (!a.link && b.link && !opts.isEmpty()) {
        if (a.fast()) {
          m.type = "off ramp";
          m.modifier = closest != null ? side(turn(closest.bearing, outB)) : side(angle);
        } else {
          m.type = "on ramp";
          m.modifier = Math.abs(angle) < 20 && closest != null ? side(turn(closest.bearing, outB)) : modifier(angle);
        }
        ramp(m, i);
      } else if (a.link && b.link) {
        if (opts.isEmpty() || closest == null || cd > 60) {
          i++;
          continue;
        }
        m.type = "fork";
        m.modifier = side(turn(closest.bearing, outB));
        ramp(m, i);
      } else if (a.link && !b.link) {
        boolean intoFast = b.fast();
        if (intoFast) {
          // the ramp joins the motorway: a merge, or a fork where the ramp splits onto it
          boolean fork = closest != null && cd < 35 && closest.link == false && closest.rc == b.rc && closest.canOut;
          m.type = fork ? "fork" : "merge";
          m.modifier = fork ? side(turn(closest.bearing, outB)) : (angle >= 0 ? "slight right" : "slight left");
          m.toward = b.dest.isEmpty() ? null : b.dest;
        } else if (opts.isEmpty()) {
          i++;
          continue;
        } else {
          m.type = !anyRoadAhead(opts, inB) && Math.abs(angle) >= 45 ? "end of road" : "turn";
          m.modifier = modifier(angle);
          if ("straight".equals(m.modifier)) {
            // the ramp ends on the road straight ahead: only its name, when it has one
            if (b.identity().isEmpty()) {
              i++;
              continue;
            }
            m.type = "new name";
          }
        }
      } else {
        // between two roads (no ramp)
        boolean nameChange = !b.identity().isEmpty() && !b.identity().equals(a.identity());
        boolean refChange = !b.ref.isEmpty() && !b.ref.equals(a.ref);
        if (opts.isEmpty()) {
          if (!refChange || (cum[at] - cum[a.p0] < 30 && !a.fast())) {
            i++;
            continue;
          }
          m.type = "new name";
          m.modifier = "straight";
        } else if (oursStraightest && ours < 35) {
          // straight on: a fork where the road splits in two of the same kind (not an exit passed
          // on a motorway, not a side street), else only a new name
          Branch twin = null;
          double td = 999;
          for (Branch br : opts) {
            if (br.link || isServiceLike(br.rc) || (br.rc != b.rc && !(b.fast() && (br.rc == RoadClass.MOTORWAY || br.rc == RoadClass.TRUNK)))) continue;
            double d = Math.abs(turn(outB, br.bearing));
            if (d < td) {
              td = d;
              twin = br;
            }
          }
          boolean fork = twin != null && td < 40 && Math.abs(turn(inB, twin.bearing)) < 40 && !isServiceLike(b.rc);
          if (fork) closest = twin;
          if (fork) {
            m.type = "fork";
            m.modifier = side(turn(closest.bearing, outB));
          } else if (nameChange) {
            m.type = "new name";
            m.modifier = "straight";
          } else {
            i++;
            continue;
          }
        } else {
          boolean ahead = anyRoadAhead(opts, inB) || ours < 35;
          m.modifier = modifier(angle);
          if ("straight".equals(m.modifier)) m.modifier = side(angle);
          if (!nameChange && !b.identity().isEmpty()) m.type = "continue";
          else m.type = !ahead && Math.abs(angle) >= 45 ? "end of road" : "turn";
        }
      }
      out.add(m);
      i++;
    }
    out.add(arrive(lat.length - 1, requested, breaks.length - 1));
    return merge(out);
  }

  private static int indexOf(int[] a, int v) {
    for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
    return -1;
  }

  private static boolean anyRoadAhead(List<Branch> opts, double inB) {
    for (Branch br : opts) if (Math.abs(turn(inB, br.bearing)) < 35) return true;
    return false;
  }

  /** The exit number, the exit's name, the branch (road numbers) and the places of a ramp. */
  private void ramp(Man m, int i) {
    Seg b = segs.get(i);
    // the sign is on the first roads of the ramp (a few hundred metres)
    String dest = b.dest, destRef = b.destRef;
    for (int k = i; k < segs.size() && cum[segs.get(k).p0] - cum[b.p0] < 400 && (dest.isEmpty() || destRef.isEmpty()); k++) {
      Seg s = segs.get(k);
      if (!s.link) break;
      if (dest.isEmpty()) dest = s.dest;
      if (destRef.isEmpty()) destRef = s.destRef;
    }
    m.toward = dest.isEmpty() ? null : dest;
    m.branch = destRef.isEmpty() ? null : destRef.replace("; ", "/");
    if ("off ramp".equals(m.type)) {
      m.exitNumber = b.junctionRef.isEmpty() ? null : b.junctionRef;
      m.exitName = b.junctionName.isEmpty() ? null : b.junctionName;
    }
  }

  private Man arrive(int at, List<double[]> requested, int waypoint) {
    Man m = new Man();
    m.at = at;
    m.seg = -1;
    m.type = "arrive";
    m.legEnd = waypoint;
    m.bearingBefore = bearingIn(at, 20);
    m.bearingAfter = 0;
    double[] r = requested != null && waypoint >= 0 && waypoint < requested.size() ? requested.get(waypoint) : null;
    if (r != null && dist(r[0], r[1], lat[at], lon[at]) > 5) {
      double toPoint = bearing(lat[at], lon[at], r[0], r[1]);
      m.modifier = turn(m.bearingBefore, toPoint) >= 0 ? "right" : "left";
    }
    return m;
  }

  /**
   * Two turns a few metres apart (crossing a dual carriageway, the two halves of a junction):
   * one manoeuvre, as the driver sees it.
   */
  private List<Man> merge(List<Man> in) {
    List<Man> out = new ArrayList<>();
    for (int idx = 0; idx < in.size(); idx++) {
      Man m = in.get(idx);
      Man last = out.isEmpty() ? null : out.get(out.size() - 1);
      // just out of a roundabout: the split island where the exit joins the road, not a manoeuvre
      if (last != null && last.type.startsWith("exit ") && cum[m.at] - cum[last.at] < 40
          && ("continue".equals(m.type) || "new name".equals(m.type) || (isTurn(m) && Math.abs(m.angle) < 70))) {
        last.seg = m.seg;
        last.bearingAfter = m.bearingAfter;
        continue;
      }
      // joining the road the ramp was signed for: said with the ramp ("Prendi l'uscita 5 per RA1")
      if ("merge".equals(m.type) && last != null && ("off ramp".equals(last.type) || "on ramp".equals(last.type)
          || ("fork".equals(last.type) && last.seg >= 0 && last.seg < segs.size() && segs.get(last.seg).link))
          && cum[m.at] - cum[last.at] < 1500) {
        last.seg = m.seg;
        continue;
      }
      if ("new name".equals(m.type) && identityOf(m).isEmpty()) continue;
      // a name for a few hundred metres, then the first one again: not worth a word
      if ("new name".equals(m.type) && idx + 1 < in.size() && "new name".equals(in.get(idx + 1).type)
          && cum[in.get(idx + 1).at] - cum[m.at] < 400 && last != null && identityOf(in.get(idx + 1)).equals(identityOf(last))) {
        idx++;
        continue;
      }
      if (last != null && isTurn(last) && isTurn(m) && cum[m.at] - cum[last.at] < 25) {
        double total = turn(last.bearingBefore, m.bearingAfter);
        last.angle = total;
        last.bearingAfter = m.bearingAfter;
        last.seg = m.seg;
        last.modifier = Math.abs(total) > 160 ? "uturn" : modifier(total);
        if ("straight".equals(last.modifier)) last.modifier = side(total);
        last.type = "turn";
        continue;
      }
      out.add(m);
    }
    return out;
  }

  private static boolean isTurn(Man m) {
    return "turn".equals(m.type) || "end of road".equals(m.type) || "continue".equals(m.type);
  }

  // ------------------------------------------------------------------------------- the words

  private static final String[] CARD = {"nord", "nord-est", "est", "sud-est", "sud", "sud-ovest", "ovest", "nord-ovest"};

  private static String cardinal(double b) {
    return CARD[(int) Math.round(((b % 360) + 360) % 360 / 45.0) % 8];
  }

  private static String sideWord(String modifier) {
    if (modifier == null) return "";
    return modifier.contains("left") ? "sinistra" : modifier.contains("right") ? "destra" : "";
  }

  /** The road after a manoeuvre for the screen ("Via Popilia/SS16") or the voice ("Via Popilia, SS16"). */
  private String road(Man m, boolean voice) {
    if (m.seg < 0 || m.seg >= segs.size()) return "";
    Seg s = segs.get(m.seg);
    String ref = s.ref;
    if (s.name.isEmpty()) return ref.replace("; ", voice ? ", " : "/");
    if (ref.isEmpty()) return s.name;
    return voice ? s.name + ", " + ref.replace("; ", ", ") : s.name + "/" + ref.replace("; ", "/");
  }

  /** The road for a fork or merge onto a numbered road: "A14/E 55/Autostrada Adriatica" (refs first). */
  private String roadRefFirst(Man m) {
    if (m.seg < 0 || m.seg >= segs.size()) return "";
    Seg s = segs.get(m.seg);
    String r = s.ref.replace("; ", "/");
    if (r.isEmpty()) return s.name;
    return s.name.isEmpty() ? r : r + "/" + s.name;
  }

  private String identityOf(Man m) {
    if (m.seg < 0 || m.seg >= segs.size()) return "";
    Seg s = segs.get(m.seg);
    String id = s.identity();
    int semi = id.indexOf(';');
    return semi > 0 ? id.substring(0, semi).trim() : id;
  }

  private static String ord(int n) {
    return n + "a";
  }

  /** The instruction of a manoeuvre (the step's own, written as Valhalla writes it). */
  private String instruction(Man m, boolean voice) {
    String road = road(m, voice);
    String sd = sideWord(m.modifier);
    switch (m.type) {
      case "depart":
        return road.isEmpty() ? "Guida verso " + cardinal(m.bearingAfter) + "." : "Guida verso " + cardinal(m.bearingAfter) + " su " + road + ".";
      case "arrive":
        if (sd.isEmpty()) return "Sei arrivato alla tua destinazione.";
        return "La tua destinazione è sulla " + sd + ".";
      case "roundabout":
      case "rotary": {
        String head = m.rotary != null ? "Entra in " + m.rotary : "Entra nella rotonda";
        String where = !road.isEmpty() ? " per " + road
            : (m.branch != null ? " per " + (voice ? m.branch.replace("/", ", ") : m.branch) : "") + (m.toward != null ? " verso " + m.toward : "");
        return head + " e prendi la " + ord(m.exitCount) + " uscita" + where + ".";
      }
      case "exit roundabout":
      case "exit rotary":
        return road.isEmpty() ? "Esci dalla rotatoria." : "Esci dalla rotatoria " + (voice ? "su " : "verso ") + road + ".";
      case "off ramp": {
        StringBuilder b = new StringBuilder("Prendi l'uscita");
        if (m.exitNumber != null) b.append(' ').append(m.exitNumber).append(" a ").append(sd);
        else if (m.exitName != null) b.append(' ').append(m.exitName);
        else if (m.branch == null && m.toward == null) b.append(" a ").append(sd);
        if (m.branch != null) b.append(" per ").append(voice ? m.branch.replace("/", ", ") : m.branch);
        if (m.toward != null) b.append(" verso ").append(m.toward);
        return b.append('.').toString();
      }
      case "on ramp": {
        StringBuilder b = new StringBuilder("Prendi lo svincolo");
        if (m.branch != null) b.append(' ').append(voice ? m.branch.replace("/", ", ") : m.branch);
        if (!sd.isEmpty()) b.append(" a ").append(sd);
        if (m.toward != null) b.append(" verso ").append(m.toward);
        return b.append('.').toString();
      }
      case "fork": {
        String r = voice ? identityOf(m) : roadRefFirst(m);
        if (r.isEmpty() && m.toward == null) return "Mantieni la " + sd + " al bivio.";
        if (r.isEmpty()) return "Mantieni la " + sd + " verso " + m.toward + ".";
        return "Mantieni la " + sd + (voice ? " per imboccare " : " per prendere ") + r + (m.toward != null && !voice ? " verso " + m.toward : "") + ".";
      }
      case "merge": {
        String r = voice ? identityOf(m) : roadRefFirst(m);
        return r.isEmpty() ? "Immettiti." : "Immettiti su " + r + ".";
      }
      case "new name":
        return road.isEmpty() ? "Continua." : "Continua su " + road + ".";
      case "continue":
        return turnWords(m) + (road.isEmpty() ? "." : " per rimanere su " + road + ".");
      case "end of road":
      case "turn":
      default: {
        if ("uturn".equals(m.modifier)) return road.isEmpty() ? "Fai inversione a U." : "Fai inversione a U su " + road + ".";
        if (road.isEmpty() && m.seg >= 0 && m.seg < segs.size() && segs.get(m.seg).link) return turnWords(m) + " per prendere lo svincolo.";
        if (m.modifier != null && m.modifier.startsWith("slight")) return "Mantieni la " + sd + (road.isEmpty() ? "." : " su " + road + ".");
        return turnWords(m) + (road.isEmpty() ? "." : " su " + road + ".");
      }
    }
  }

  private static String turnWords(Man m) {
    String sd = sideWord(m.modifier);
    if (m.modifier == null || sd.isEmpty()) return "Prosegui";
    if (m.modifier.startsWith("sharp")) return "Svolta decisamente a " + sd;
    if (m.modifier.startsWith("slight")) return "Svolta leggermente a " + sd;
    return "Svolta a " + sd;
  }

  /** What the banner shows for a manoeuvre: main text and components, the places (secondary). */
  private String[] bannerText(Man m) {
    String road = road(m, false);
    switch (m.type) {
      case "arrive":
        return new String[] {instruction(m, false), null};
      case "off ramp":
      case "on ramp": {
        String main = m.exitName != null ? m.exitName : (road.isEmpty() ? null : road);
        String dest = (m.branch != null ? m.branch.replace("/", ", ") + (m.toward != null ? ": " : "") : "") + (m.toward != null ? m.toward.replace("/", " ") : "");
        if (main == null) return new String[] {dest.isEmpty() ? instruction(m, false) : dest, null};
        return new String[] {main, dest.isEmpty() || dest.equals(main) ? null : dest};
      }
      case "fork":
      case "merge":
        if (road.isEmpty()) return new String[] {m.toward != null ? m.toward.replace("/", " ") : instruction(m, false), null};
        return new String[] {road, m.toward != null ? m.toward.replace("/", " ") : null};
      default:
        return new String[] {road.isEmpty() ? instruction(m, false) : road, null};
    }
  }

  // --------------------------------------------------------------------------------- lanes

  private static String laneDir(String osm) {
    switch (osm.trim()) {
      case "through":
        return "straight";
      case "slight_right":
        return "slight right";
      case "slight_left":
        return "slight left";
      case "sharp_right":
        return "sharp right";
      case "sharp_left":
        return "sharp left";
      case "merge_to_left":
        return "merge to left";
      case "merge_to_right":
        return "merge to right";
      case "reverse":
        return "uturn";
      case "left":
      case "right":
        return osm.trim();
      default:
        // no arrow painted ("", "none"): the lane goes straight on (as Valhalla shows it)
        return "straight";
    }
  }

  /**
   * The lanes before manoeuvre [m] (turn:lanes of the road arriving at it, or of the road before
   * when it is close): each lane's arrows and whether it goes the route's way.
   */
  private List<String[]> lanesAt(Man m) {
    if (m.seg <= 0 || m.seg > segs.size()) return null;
    String tl = null;
    for (int k = m.seg - 1; k >= 0; k--) {
      Seg s = segs.get(k);
      if (!s.turnLanes.isEmpty()) {
        tl = s.turnLanes;
        break;
      }
      if (cum[m.at] - cum[s.p0] > 150) break;
    }
    if (tl == null) return null;
    String[] parts = tl.split("\\|", -1);
    if (parts.length < 2) return null;
    String want = m.modifier == null ? "straight" : m.modifier;
    if ("uturn".equals(want)) want = "uturn";
    List<String[]> out = new ArrayList<>();
    boolean anyActive = false;
    for (String p : parts) {
      List<String> dirs = new ArrayList<>();
      for (String d : p.split(";")) dirs.add(laneDir(d));
      if (dirs.isEmpty()) dirs.add("none");
      String active = match(dirs, want, m.type);
      if (active != null) anyActive = true;
      out.add(new String[] {String.join(";", dirs), active != null ? "1" : "0", active == null ? "" : active});
    }
    if (!anyActive) {
      // lanes without arrows ("none") all go straight on
      return null;
    }
    return out;
  }

  private static String match(List<String> dirs, String want, String type) {
    for (String d : dirs) if (d.equals(want)) return d;
    String side = want.contains("right") ? "right" : want.contains("left") ? "left" : "straight";
    for (String d : dirs) {
      if (side.equals("straight") && (d.equals("straight") || d.equals("none"))) return d;
      if (!side.equals("straight") && d.contains(side) && !d.startsWith("merge")) return d;
    }
    return null;
  }

  // ------------------------------------------------------------------------- OSRM writing

  private static void q(StringBuilder b, String s) {
    b.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        case '\n':
          b.append("\\n");
          break;
        case '\r':
          b.append("\\r");
          break;
        case '\t':
          b.append("\\t");
          break;
        default:
          if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else b.append(c);
      }
    }
    b.append('"');
  }

  private static String num(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
    return String.format(Locale.ROOT, "%.3f", v);
  }

  /** Google's polyline with 6 decimals (Valhalla's shape, Ferrostar's parser precision). */
  static String polyline6(double[] la, double[] lo, int from, int to) {
    StringBuilder b = new StringBuilder();
    long pLat = 0, pLon = 0;
    for (int i = from; i <= to; i++) {
      long a = Math.round(la[i] * 1e6), c = Math.round(lo[i] * 1e6);
      enc(b, a - pLat);
      enc(b, c - pLon);
      pLat = a;
      pLon = c;
    }
    return b.toString();
  }

  private static void enc(StringBuilder b, long v) {
    long s = v < 0 ? ~(v << 1) : (v << 1);
    while (s >= 0x20) {
      b.append((char) ((0x20 | (s & 0x1f)) + 63));
      s >>= 5;
    }
    b.append((char) (s + 63));
  }

  private void coord(StringBuilder b, int i) {
    b.append('[').append(String.format(Locale.ROOT, "%.6f,%.6f", lon[i], lat[i])).append(']');
  }

  private String distanceWords(double m) {
    if (m < 1000) return Math.max(100, Math.round(m / 100.0) * 100) + " metri";
    double km = Math.round(m / 500.0) / 2.0;
    if (km == Math.floor(km)) return (km == 1 ? "1 chilometro" : (long) km + " chilometri");
    return String.format(Locale.ITALIAN, "%.1f chilometri", km);
  }

  private String osrm(List<Man> mans, int[] breaks, List<double[]> requested, double[] segTime, double[] segMax) {
    StringBuilder b = new StringBuilder(64 * 1024);
    int n = lat.length;
    // legs: between departs and arrives
    List<int[]> legs = new ArrayList<>(); // [first man index, last man index (arrive)]
    int start = 0;
    for (int k = 0; k < mans.size(); k++) {
      if ("arrive".equals(mans.get(k).type)) {
        legs.add(new int[] {start, k});
        start = k + 1;
      }
    }
    double total = cum[n - 1];
    double totalTime = 0;
    for (double t : segTime) totalTime += t;
    b.append("{\"code\":\"Ok\",\"routes\":[{\"distance\":").append(num(total)).append(",\"duration\":").append(num(totalTime))
        .append(",\"weight_name\":\"auto\",\"weight\":").append(num(totalTime)).append(",\"voiceLocale\":\"it-IT\",\"geometry\":");
    q(b, polyline6(lat, lon, 0, n - 1));
    b.append(",\"legs\":[");
    for (int L = 0; L < legs.size(); L++) {
      int[] leg = legs.get(L);
      int lp0 = mans.get(leg[0]).at, lp1 = mans.get(leg[1]).at;
      if (L > 0) b.append(',');
      double ld = cum[lp1] - cum[lp0], lt = 0;
      for (int i = lp0; i < lp1; i++) lt += segTime[i];
      b.append("{\"distance\":").append(num(ld)).append(",\"duration\":").append(num(lt)).append(",\"weight\":").append(num(lt))
          .append(",\"summary\":\"\",\"via_waypoints\":[],\"annotation\":{");
      // per point segment
      StringBuilder dd = new StringBuilder(), du = new StringBuilder(), sp = new StringBuilder(), mx = new StringBuilder();
      for (int i = lp0; i < lp1; i++) {
        if (i > lp0) {
          dd.append(',');
          du.append(',');
          sp.append(',');
          mx.append(',');
        }
        double d = cum[i + 1] - cum[i], t = segTime[i];
        dd.append(String.format(Locale.ROOT, "%.1f", d));
        du.append(String.format(Locale.ROOT, "%.3f", t));
        sp.append(String.format(Locale.ROOT, "%.1f", t > 0 ? d / t : 0));
        double ms = segMax[i];
        if (Double.isNaN(ms)) mx.append("{\"unknown\":true}");
        else mx.append("{\"speed\":").append(Math.round(ms)).append(",\"unit\":\"km/h\"}");
      }
      b.append("\"distance\":[").append(dd).append("],\"duration\":[").append(du).append("],\"speed\":[").append(sp)
          .append("],\"maxspeed\":[").append(mx).append("]},\"steps\":[");
      for (int k = leg[0]; k <= leg[1]; k++) {
        if (k > leg[0]) b.append(',');
        Man m = mans.get(k);
        Man next = k < leg[1] ? mans.get(k + 1) : null;
        int s0 = m.at, s1 = next != null ? next.at : m.at;
        double sd = cum[s1] - cum[s0], st = 0;
        for (int i = s0; i < s1; i++) st += segTime[i];
        step(b, m, next, s0, s1, sd, st);
      }
      b.append("]}");
    }
    b.append("]}],\"waypoints\":[");
    for (int w = 0; w < breaks.length; w++) {
      if (w > 0) b.append(',');
      int at = breaks[w];
      double dd = 0;
      if (requested != null && w < requested.size()) dd = dist(requested.get(w)[0], requested.get(w)[1], lat[at], lon[at]);
      b.append("{\"name\":");
      Seg s = segAt(at);
      q(b, s == null ? "" : s.name);
      b.append(",\"distance\":").append(num(dd)).append(",\"location\":");
      coord(b, at);
      b.append('}');
    }
    b.append("]}");
    return b.toString();
  }

  private Seg segAt(int point) {
    for (Seg s : segs) if (point >= s.p0 && point <= s.p1) return s;
    return null;
  }

  private void step(StringBuilder b, Man m, Man next, int s0, int s1, double sd, double st) {
    Seg road = m.seg >= 0 && m.seg < segs.size() ? segs.get(m.seg) : segAt(s0);
    b.append("{\"distance\":").append(num(sd)).append(",\"duration\":").append(num(st)).append(",\"weight\":").append(num(st))
        .append(",\"mode\":\"driving\",\"driving_side\":\"right\",\"speedLimitSign\":\"vienna\",\"speedLimitUnit\":\"km/h\",\"name\":");
    q(b, road == null ? "" : road.name);
    if (road != null && !road.ref.isEmpty()) {
      b.append(",\"ref\":");
      q(b, road.ref);
    }
    String dest = null;
    if (m.branch != null || m.toward != null) {
      dest = (m.branch != null ? m.branch.replace("/", ", ") + (m.toward != null ? ": " : "") : "") + (m.toward != null ? m.toward.replace("/", ", ") : "");
    }
    if (dest != null) {
      b.append(",\"destinations\":");
      q(b, dest);
    }
    if (m.exitNumber != null) {
      b.append(",\"exits\":");
      q(b, m.exitNumber);
    }
    if (m.rotary != null) {
      b.append(",\"rotary_name\":");
      q(b, m.rotary);
    }
    b.append(",\"geometry\":");
    q(b, "arrive".equals(m.type) ? polyline6(new double[] {lat[s0], lat[s0]}, new double[] {lon[s0], lon[s0]}, 0, 1) : polyline6(lat, lon, s0, s1));
    // the manoeuvre at the start of the step
    b.append(",\"maneuver\":{\"type\":");
    q(b, m.type);
    if (m.modifier != null) {
      b.append(",\"modifier\":");
      q(b, m.modifier);
    }
    if (("roundabout".equals(m.type) || "rotary".equals(m.type)) && m.exitCount > 0) b.append(",\"exit\":").append(m.exitCount);
    b.append(",\"bearing_before\":").append(Math.round(m.bearingBefore) % 360).append(",\"bearing_after\":").append(Math.round(m.bearingAfter) % 360)
        .append(",\"location\":");
    coord(b, s0);
    b.append(",\"instruction\":");
    q(b, instruction(m, false));
    b.append('}');
    // the junction at the start of the step
    b.append(",\"intersections\":[{\"location\":");
    coord(b, s0);
    b.append(",\"bearings\":[").append(Math.round(m.bearingAfter) % 360).append("],\"entry\":[true],\"in\":0,\"out\":0");
    if (m.lanes != null) {
      b.append(",\"lanes\":[");
      lanesJson(b, m.lanes, false);
      b.append(']');
    }
    b.append("}]");
    // what the step says about the manoeuvre at its end (Mapbox / Valhalla convention)
    b.append(",\"bannerInstructions\":[");
    if (next != null) {
      next.lanes = lanesAt(next);
      String[] bt = bannerText(next);
      b.append("{\"distanceAlongGeometry\":").append(num(sd)).append(",\"primary\":{\"text\":");
      q(b, bt[0]);
      b.append(",\"type\":");
      q(b, next.type);
      if (next.modifier != null) {
        b.append(",\"modifier\":");
        q(b, next.modifier);
      }
      if (!Double.isNaN(next.degrees)) b.append(",\"degrees\":").append(Math.round(next.degrees));
      b.append(",\"driving_side\":\"right\",\"components\":[");
      boolean first = true;
      if (next.exitNumber != null) {
        b.append("{\"type\":\"exit\",\"text\":\"Uscita\"},{\"type\":\"exit-number\",\"text\":");
        q(b, next.exitNumber);
        b.append('}');
        first = false;
      }
      for (String part : bt[0].split("/")) {
        if (!first) b.append(",{\"type\":\"delimiter\",\"text\":\"/\"},");
        b.append("{\"type\":\"text\",\"text\":");
        q(b, part);
        b.append('}');
        first = false;
      }
      b.append("]}");
      if (bt[1] != null) {
        b.append(",\"secondary\":{\"text\":");
        q(b, bt[1]);
        b.append(",\"type\":");
        q(b, next.type);
        if (next.modifier != null) {
          b.append(",\"modifier\":");
          q(b, next.modifier);
        }
        b.append(",\"components\":[{\"type\":\"text\",\"text\":");
        q(b, bt[1]);
        b.append("}]}");
      }
      if (next.lanes != null) {
        b.append(",\"sub\":{\"text\":\"\",\"components\":[");
        lanesJson(b, next.lanes, true);
        b.append("]}");
      }
      b.append('}');
    }
    b.append("],\"voiceInstructions\":[");
    if (next != null) {
      boolean fast = road != null && (road.fast() || road.rc == RoadClass.PRIMARY && road.maxSpeed >= 90);
      String say = instruction(next, true);
      List<String[]> vs = new ArrayList<>();
      if ("depart".equals(m.type)) vs.add(new String[] {num(sd), instruction(m, true) + (sd < 400 ? " poi " + say : "")});
      else if (sd >= 600) vs.add(new String[] {num(Math.max(0, sd - 10)), "Continua per " + distanceWords(sd) + "."});
      double pre = fast ? 1300 : 450, fin = fast ? 300 : 140;
      if (sd > pre + 200) vs.add(new String[] {num(pre), "Tra " + distanceWords(pre) + " , " + say});
      vs.add(new String[] {num(Math.min(sd, fin)), say});
      for (int k = 0; k < vs.size(); k++) {
        if (k > 0) b.append(',');
        b.append("{\"distanceAlongGeometry\":").append(vs.get(k)[0]).append(",\"announcement\":");
        q(b, vs.get(k)[1]);
        b.append(",\"ssmlAnnouncement\":");
        q(b, "<speak><amazon:effect name=\"drc\"><prosody rate=\"1.08\">" + vs.get(k)[1].replace("&", "e").replace("<", "").replace(">", "") + "</prosody></amazon:effect></speak>");
        b.append('}');
      }
    }
    b.append("]}");
  }

  private static void lanesJson(StringBuilder b, List<String[]> lanes, boolean banner) {
    for (int k = 0; k < lanes.size(); k++) {
      String[] l = lanes.get(k);
      if (k > 0) b.append(',');
      StringBuilder dirs = new StringBuilder("[");
      String[] ds = l[0].split(";");
      for (int j = 0; j < ds.length; j++) {
        if (j > 0) dirs.append(',');
        dirs.append('"').append(ds[j]).append('"');
      }
      dirs.append(']');
      boolean active = "1".equals(l[1]);
      if (banner) {
        b.append("{\"type\":\"lane\",\"text\":\"\",\"directions\":").append(dirs).append(",\"active\":").append(active);
        if (active) b.append(",\"active_direction\":\"").append(l[2]).append('"');
        b.append('}');
      } else {
        b.append("{\"indications\":").append(dirs).append(",\"valid\":").append(active).append(",\"active\":").append(active);
        if (active) b.append(",\"valid_indication\":\"").append(l[2]).append('"');
        b.append('}');
      }
    }
  }

  // ----------------------------------------------------------------------- trace_attributes

  /** The roads of the route as Valhalla's trace_attributes gives them (RouteAnalysis.parse). */
  private String attributes() {
    StringBuilder b = new StringBuilder(64 * 1024);
    int n = lat.length;
    List<String> admins = new ArrayList<>();
    b.append("{\"units\":\"kilometers\",\"shape\":");
    q(b, polyline6(lat, lon, 0, n - 1));
    StringBuilder edges = new StringBuilder();
    double lastNodeAt = -1e9;
    String lastNodeType = null;
    for (int k = 0; k < segs.size(); k++) {
      Seg s = segs.get(k);
      // toll booths, gantries, border controls: the edge is cut there, so that the node is at an edge's end
      List<double[]> cuts = new ArrayList<>(); // [point index, type index]
      List<String> types = new ArrayList<>();
      if (!s.nodes.isEmpty()) {
        double len = cum[s.p1] - cum[s.p0];
        double lastAt = -1e9;
        String lastType = null;
        for (String part : s.nodes.split(";")) {
          int at = part.indexOf('@');
          if (at <= 0) continue;
          String type = part.substring(0, at);
          double f;
          try {
            f = Double.parseDouble(part.substring(at + 1));
          } catch (NumberFormatException e) {
            continue;
          }
          // the edge as stored may run the other way
          int pi = pointAtFraction(s, f);
          // one booth per toll station (OSM has one node per lane, GraphHopper a copy per barrier)
          if (type.equals(lastType) && Math.abs(cum[pi] - lastAt) < 30) continue;
          if (type.equals(lastNodeType) && Math.abs(cum[pi] - lastNodeAt) < 60) continue;
          lastType = type;
          lastAt = cum[pi];
          lastNodeType = type;
          lastNodeAt = cum[pi];
          cuts.add(new double[] {pi});
          types.add(type);
        }
      }
      int from = s.p0;
      for (int c = 0; c <= cuts.size(); c++) {
        int to = c < cuts.size() ? (int) cuts.get(c)[0] : s.p1;
        if (to < from) to = from;
        String type = c < cuts.size() ? types.get(c) : null;
        if (to == from && type == null && c > 0) break;
        Seg nextSeg = k + 1 < segs.size() ? segs.get(k + 1) : null;
        boolean last = c == cuts.size();
        if (edges.length() > 0) edges.append(',');
        edge(edges, s, from, to, type, last ? nextSeg : null, admins);
        from = to;
      }
    }
    b.append(",\"admins\":[");
    for (int i = 0; i < admins.size(); i++) {
      if (i > 0) b.append(',');
      b.append("{\"country_code\":");
      q(b, admins.get(i));
      b.append('}');
    }
    b.append("],\"edges\":[").append(edges).append("]}");
    return b.toString();
  }

  /** The point of the route at fraction [f] of the stored edge (stored direction). */
  private int pointAtFraction(Seg s, double f) {
    // stored direction: from the edge's base to its adj in storage order
    boolean reversed = s.e.get(EdgeIteratorState.REVERSE_STATE);
    double ff = reversed ? 1 - f : f;
    double edgeLen = s.e.getDistance();
    // where the route's part of this edge starts on the edge (partial edges at the ends)
    double partLen = cum[s.p1] - cum[s.p0];
    double offset = 0;
    if (partLen < edgeLen - 1) {
      // a partial edge (the start or the end of the route): measure from the edge's own end
      if (s.p0 == 0) offset = edgeLen - partLen;
    }
    double target = cum[s.p0] + ff * edgeLen - offset;
    int best = s.p0;
    for (int i = s.p0; i <= s.p1; i++) if (Math.abs(cum[i] - target) < Math.abs(cum[best] - target)) best = i;
    return best;
  }

  private void edge(StringBuilder b, Seg s, int from, int to, String nodeType, Seg next, List<String> admins) {
    b.append("{\"way_id\":").append(s.way).append(",\"road_class\":");
    q(b, valhallaClass(s.rc));
    b.append(",\"use\":");
    q(b, s.ferry ? "ferry" : s.link ? "ramp" : s.rc == RoadClass.SERVICE ? "driveway" : "road");
    b.append(",\"toll\":").append(s.toll).append(",\"surface\":");
    q(b, s.surface);
    b.append(",\"lane_count\":").append(s.lanes).append(",\"length\":").append(String.format(Locale.ROOT, "%.4f", (cum[to] - cum[from]) / 1000))
        .append(",\"begin_shape_index\":").append(from).append(",\"end_shape_index\":").append(to)
        .append(",\"tunnel\":").append(s.tunnel).append(",\"bridge\":").append(s.bridge).append(",\"roundabout\":").append(s.roundabout);
    if (!Double.isNaN(s.maxSpeed)) b.append(",\"speed_limit\":").append(Math.round(s.maxSpeed));
    List<String> names = new ArrayList<>();
    if (!s.ref.isEmpty()) for (String r : s.ref.split("; ")) names.add(r);
    if (!s.name.isEmpty()) names.add(s.name);
    if (!names.isEmpty()) {
      b.append(",\"names\":[");
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) b.append(',');
        q(b, names.get(i));
      }
      b.append(']');
    }
    // the sign at the start of a ramp (destination tags, exit number and name)
    if (s.link && from == s.p0 && (!s.dest.isEmpty() || !s.destRef.isEmpty() || !s.junctionRef.isEmpty() || !s.junctionName.isEmpty())) {
      b.append(",\"sign\":{");
      boolean first = true;
      first = signList(b, "exit_number", s.junctionRef, ";", first);
      first = signList(b, "exit_branch", s.destRef, "; ", first);
      first = signList(b, "exit_toward", s.dest, "/", first);
      signList(b, "exit_name", s.junctionName, ";", first);
      b.append('}');
    }
    double hb = bearingOut(from, 15), he = bearingIn(to, 15);
    b.append(",\"begin_heading\":").append(Math.round(hb) % 360).append(",\"end_heading\":").append(Math.round(he) % 360);
    // the node at the end
    int ai = -1;
    if (s.country != null) {
      ai = admins.indexOf(s.country);
      if (ai < 0) {
        admins.add(s.country);
        ai = admins.size() - 1;
      }
    }
    b.append(",\"end_node\":{");
    if (ai >= 0) b.append("\"admin_index\":").append(ai).append(',');
    b.append("\"type\":");
    q(b, nodeType != null ? nodeType : "street_intersection");
    if (next != null && nodeType == null && to == s.p1 && !(next.edge == s.edge)) {
      b.append(",\"intersecting_edges\":[");
      boolean first = true;
      for (Branch br : branches(s, next)) {
        if (br.incoming || br.path) continue;
        if (!first) b.append(',');
        first = false;
        String drive = br.canOut && br.canIn ? "both" : br.canOut ? "forward" : br.canIn ? "backward" : "none";
        b.append("{\"driveability\":");
        q(b, drive);
        b.append(",\"use\":");
        q(b, br.link ? "ramp" : br.rc == RoadClass.SERVICE ? "driveway" : isServiceLike(br.rc) ? "footway" : "road");
        b.append(",\"begin_heading\":").append(Math.round(br.bearing) % 360).append(",\"road_class\":");
        q(b, valhallaClass(br.rc));
        b.append(",\"lane_count\":").append(br.lanes).append('}');
      }
      b.append(']');
    }
    b.append("}}");
  }

  private static boolean signList(StringBuilder b, String key, String value, String sep, boolean first) {
    if (value == null || value.isEmpty()) return first;
    if (!first) b.append(',');
    q(b, key);
    b.append(":[");
    String[] parts = value.split(java.util.regex.Pattern.quote(sep));
    boolean f = true;
    for (String p : parts) {
      String t = p.trim();
      if (t.isEmpty()) continue;
      if (!f) b.append(',');
      f = false;
      b.append("{\"text\":");
      q(b, t);
      b.append('}');
    }
    b.append(']');
    return false;
  }

  private static String valhallaClass(RoadClass rc) {
    if (rc == null) return "service_other";
    switch (rc) {
      case MOTORWAY:
        return "motorway";
      case TRUNK:
        return "trunk";
      case PRIMARY:
        return "primary";
      case SECONDARY:
        return "secondary";
      case TERTIARY:
        return "tertiary";
      case UNCLASSIFIED:
        return "unclassified";
      case RESIDENTIAL:
      case LIVING_STREET:
        return "residential";
      default:
        return "service_other";
    }
  }

  /** Unused here; kept so the list of a node's roads can be logged in the tests. */
  static String describe(List<Branch> brs) {
    StringBuilder b = new StringBuilder();
    for (Branch br : brs)
      b.append(String.format(Locale.ROOT, "[%d %.0f° %s%s%s%s] ", br.edge, br.bearing, br.rc, br.link ? " link" : "", br.canOut ? " out" : "",
          br.path ? " PATH" : br.incoming ? " IN" : ""));
    return b.toString();
  }

  static <T> List<T> emptyIfNull(List<T> l) {
    return l == null ? Collections.<T>emptyList() : l;
  }
}
