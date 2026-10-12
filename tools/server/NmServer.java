package app.navmaster.server;

import app.navmaster.truck.routing.gh.GhCross;
import app.navmaster.truck.routing.gh.GhEngine;
import app.navmaster.truck.routing.gh.GhGuide;
import app.navmaster.truck.routing.gh.TruckSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * NavMaster's own routing server: the same GraphHopper graphs, the same openrouteservice rules
 * (OrsWeighting), the same crossings between countries (GhCross) and the same guidance (GhGuide)
 * as the tablet, on a machine with the graphs of every country. The tablet asks it for the route
 * when it is online and gets back exactly what it would compute itself: the OSRM answer for
 * Ferrostar and the roads of the route for the analysis.
 *
 * <p>Plain Java, no dependency but GraphHopper's jar (Jackson is inside it) and the JDK's HTTP
 * server. Data folder (made by update_graphs.py): {@code <data>/catalog.json} (the countries with
 * their borders) and {@code <data>/<country>/gh} (graph + landmarks) and {@code
 * <data>/<country>/valichi.json}.
 *
 * <p>Settings (environment): NM_DATA (/opt/navmaster/data), NM_MODELS (/opt/navmaster/models: the
 * app's assets/gh), NM_BIND (127.0.0.1: Caddy in front gives HTTPS), NM_PORT (8989), NM_KEYS (file of
 * the tablets' keys, one "name sha256-of-the-key" per line), NM_THREADS (4: searches at once),
 * NM_PRELOAD (comma list of countries opened at start, or "all").
 *
 * <p>API (JSON):
 *
 * <ul>
 *   <li>GET /v1/health: {ok, countries, open, started} (no key needed, says nothing private)
 *   <li>POST /v1/route with "Authorization: Bearer KEY": {points: [[lat, lon]...], kinds: ["break"|"via"...],
 *       headings: [deg|null...], vehicle: {...TruckSpec measures}, trip: {route, avoidTolls, shortest,
 *       alternatives, timeoutMs, softIfNone, avoidZones: [[[lat, lon]...]...]}, guide: true} →
 *       {ok, routes: [{distanceM, timeMs, computeMs, countries, note, waypointIndex, polyline6, osrm,
 *       attributes}], soft, softWhy, error}
 * </ul>
 */
public final class NmServer {
  static final String VERSION = "1";
  static final ObjectMapper JSON = new ObjectMapper();

  final File data;
  final File models;
  final Map<String, Country> countries = new LinkedHashMap<>();
  final Map<String, GhEngine> engines = new ConcurrentHashMap<>();
  final Map<String, List<GhCross.Crossing>> crossingsCache = new ConcurrentHashMap<>();
  final Map<String, List<GhCross.Crossing>> ownCrossings = new ConcurrentHashMap<>();
  final Map<String, String> keys = new HashMap<>();
  final Semaphore searches;
  final Instant started = Instant.now();

  /** A country of the catalog: its bounding box and border (rings of [lon, lat]). */
  static final class Country {
    String id, name;
    double[] bbox;
    List<double[][]> rings = new ArrayList<>();

    boolean contains(double lat, double lon) {
      if (bbox == null || lon < bbox[0] || lat < bbox[1] || lon > bbox[2] || lat > bbox[3]) return false;
      if (rings.isEmpty()) return true;
      for (double[][] ring : rings) {
        boolean inside = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
          double xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
          if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi) inside = !inside;
        }
        if (inside) return true;
      }
      return false;
    }
  }

  NmServer(File data, File models, File keysFile, int threads) throws IOException {
    this.data = data;
    this.models = models;
    this.searches = new Semaphore(threads);
    JsonNode cat = JSON.readTree(new File(data, "catalog.json"));
    for (JsonNode c : cat.path("countries")) {
      Country k = new Country();
      k.id = c.path("id").asText();
      k.name = c.path("name").asText(k.id);
      if (c.path("bbox").size() == 4) {
        k.bbox = new double[4];
        for (int i = 0; i < 4; i++) k.bbox[i] = c.path("bbox").get(i).asDouble();
      }
      for (JsonNode ring : c.path("poly")) {
        double[][] r = new double[ring.size()][];
        for (int i = 0; i < ring.size(); i++) r[i] = new double[] {ring.get(i).get(0).asDouble(), ring.get(i).get(1).asDouble()};
        k.rings.add(r);
      }
      countries.put(k.id, k);
    }
    if (keysFile != null && keysFile.exists()) {
      for (String line : Files.readAllLines(keysFile.toPath(), StandardCharsets.UTF_8)) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) continue;
        String[] p = t.split("\\s+");
        if (p.length >= 2) keys.put(p[1].toLowerCase(Locale.ROOT), p[0]);
      }
    }
    log("catalog: " + countries.size() + " countries, graphs on disk: " + installed() + ", keys: " + keys.size());
  }

  /** The countries whose graph is on the server. */
  List<String> installed() {
    List<String> out = new ArrayList<>();
    for (String id : countries.keySet()) if (new File(data, id + "/gh/properties").exists()) out.add(id);
    return out;
  }

  Country countryAt(double lat, double lon) {
    for (Country c : countries.values()) if (c.contains(lat, lon)) return c;
    return null;
  }

  /** The graph of [id], opened once (memory-mapped: the system keeps in memory what is used). */
  GhEngine engine(String id) {
    GhEngine e = engines.get(id);
    if (e != null) return e;
    synchronized (this) {
      e = engines.get(id);
      if (e != null) return e;
      File dir = new File(data, id + "/gh");
      if (!new File(dir, "properties").exists()) return null;
      long t = System.currentTimeMillis();
      try {
        e = GhEngine.open(dir, models, true);
      } catch (Exception ex) {
        log("graph of " + id + " not opened: " + ex);
        return null;
      }
      engines.put(id, e);
      log("graph of " + id + " opened in " + (System.currentTimeMillis() - t) + " ms (strong motorway: " + e.strongMotorway() + ")");
      return e;
    }
  }

  List<GhCross.Crossing> own(String id) {
    return ownCrossings.computeIfAbsent(id, k -> {
      File f = new File(data, k + "/valichi.json");
      return f.exists() ? GhCross.readAll(f) : Collections.emptyList();
    });
  }

  /**
   * The crossings out of [id]: its own and those of the neighbours read the other way (as on the
   * tablet, GhRouting.crossingsOf), each once.
   */
  List<GhCross.Crossing> crossings(String id) {
    return crossingsCache.computeIfAbsent(id, k -> {
      List<GhCross.Crossing> out = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      Consumer<GhCross.Crossing> add = c -> {
        if (Double.isNaN(c.ab) || c.to.equals(k)) return;
        String key = c.to + ":" + String.format(Locale.ROOT, "%.4f:%.4f", c.lat, c.lon) + ":" + (int) c.ab;
        if (seen.add(key)) out.add(c);
      };
      File f = new File(data, k + "/valichi.json");
      if (f.exists()) for (GhCross.Crossing c : GhCross.read(f)) add.accept(c);
      for (String o : installed()) {
        if (o.equals(k)) continue;
        for (GhCross.Crossing c : own(o)) if (c.to.equals(k)) add.accept(c.reversed(o));
      }
      return out;
    });
  }

  final GhCross.Graphs graphs = new GhCross.Graphs() {
    @Override
    public boolean has(String country) {
      return new File(data, country + "/gh/properties").exists();
    }

    @Override
    public GhEngine engine(String country) {
      return NmServer.this.engine(country);
    }

    @Override
    public List<GhCross.Crossing> crossings(String country) {
      return NmServer.this.crossings(country);
    }
  };

  // ------------------------------------------------------------------------------------- requests

  static TruckSpec spec(JsonNode v, JsonNode t) {
    TruckSpec s = new TruckSpec();
    s.hgv = v.path("hgv").asBoolean(s.hgv);
    s.vehicleType = v.path("vehicleType").asText(s.vehicleType);
    s.heightM = v.path("heightM").asDouble(s.heightM);
    s.widthM = v.path("widthM").asDouble(s.widthM);
    s.lengthM = v.path("lengthM").asDouble(s.lengthM);
    s.weightT = v.path("weightT").asDouble(s.weightT);
    s.axleLoadT = v.path("axleLoadT").asDouble(s.axleLoadT);
    s.hazmat = v.path("hazmat").asBoolean(s.hazmat);
    String tc = v.path("tunnelCode").asText("");
    s.tunnelCode = tc.isEmpty() ? 0 : Character.toUpperCase(tc.charAt(0));
    s.hazmatWater = v.path("hazmatWater").asBoolean(s.hazmatWater);
    s.topSpeedKmh = v.path("topSpeedKmh").asDouble(s.topSpeedKmh);
    s.avoidFerries = v.path("avoidFerries").asBoolean(s.avoidFerries);
    s.avoidUnpaved = v.path("avoidUnpaved").asBoolean(s.avoidUnpaved);
    s.preferTruckRoutes = v.path("preferTruckRoutes").asBoolean(s.preferTruckRoutes);
    s.avoidTolls = t.path("avoidTolls").asBoolean(false);
    s.shortest = t.path("shortest").asBoolean(false);
    s.route = t.path("route").asInt(0);
    // never longer than a minute: the server is shared by the fleet
    s.timeoutMs = Math.min(Math.max(t.path("timeoutMs").asLong(0), 0), 60_000);
    for (JsonNode ring : t.path("avoidZones")) {
      double[][] r = new double[ring.size()][];
      for (int i = 0; i < ring.size(); i++) r[i] = new double[] {ring.get(i).get(0).asDouble(), ring.get(i).get(1).asDouble()};
      if (r.length >= 3) s.avoidZones.add(r);
    }
    return s;
  }

  /** The answer of POST /v1/route. */
  ObjectNode route(JsonNode q) throws InterruptedException {
    ObjectNode out = JSON.createObjectNode();
    List<double[]> points = new ArrayList<>();
    for (JsonNode p : q.path("points")) points.add(new double[] {p.get(0).asDouble(), p.get(1).asDouble()});
    if (points.size() < 2 || points.size() > 30) return fail(out, "da 2 a 30 punti");
    List<Double> headings = new ArrayList<>();
    for (int i = 0; i < points.size(); i++) {
      JsonNode h = q.path("headings").path(i);
      headings.add(h.isNumber() ? h.asDouble() : Double.NaN);
    }
    List<String> kinds = new ArrayList<>();
    for (int i = 0; i < points.size(); i++) kinds.add(q.path("kinds").path(i).asText("break"));
    JsonNode trip = q.path("trip");
    TruckSpec spec = spec(q.path("vehicle"), trip);
    int alternatives = Math.min(Math.max(trip.path("alternatives").asInt(0), 0), 2);
    boolean guide = q.path("guide").asBoolean(true);

    // the countries of the points
    List<String> ids = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (double[] p : points) {
      Country c = countryAt(p[0], p[1]);
      if (c == null) return fail(out, String.format(Locale.ROOT, "il punto %.5f, %.5f è fuori dai Paesi del server", p[0], p[1]));
      if (!graphs.has(c.id) && !missing.contains(c.name)) missing.add(c.name);
      ids.add(c.id);
    }
    if (!missing.isEmpty()) return fail(out, "sul server manca il grafo di " + String.join(", ", missing));

    if (!searches.tryAcquire(20, TimeUnit.SECONDS)) return fail(out, "server occupato, riprova tra poco");
    try {
      Found f = find(points, headings, ids, spec, alternatives);
      if (f.routes.isEmpty() && trip.path("softIfNone").asBoolean(true)) {
        // no road respects every limit: the route that passes the fewest of them, said as such
        TruckSpec soft = spec.copy();
        soft.soft = true;
        Found s = find(points, headings, ids, soft, 0);
        if (!s.routes.isEmpty()) {
          s.softWhy = s.engineOfFirst != null ? s.engineOfFirst.closedOn(s.routes.get(0), spec) : null;
          out.put("soft", true);
          if (s.softWhy != null) out.put("softWhy", s.softWhy);
          f = s;
        }
      }
      if (f.routes.isEmpty()) return fail(out, f.error != null ? f.error : "nessun percorso");
      ArrayNode routes = out.putArray("routes");
      for (GhEngine.Result r : f.routes) {
        ObjectNode o = routes.addObject();
        o.put("distanceM", Math.round(r.distanceM * 10) / 10.0);
        o.put("timeMs", r.timeMs);
        o.put("computeMs", r.computeMs);
        if (r.note != null) o.put("note", r.note);
        ArrayNode cs = o.putArray("countries");
        for (String c : f.countries) cs.add(c);
        ArrayNode wi = o.putArray("waypointIndex");
        for (int i : r.waypointIndex) wi.add(i);
        o.put("polyline6", polyline6(r.lat, r.lon));
        if (!guide) continue;
        GhGuide.Output g = guideOf(f, r, spec, points, kinds);
        if (g == null) {
          o.put("guideError", "guida non calcolata (grafo senza dati di guida?)");
          continue;
        }
        o.set("osrm", JSON.readTree(g.osrm));
        o.set("attributes", JSON.readTree(g.attributes));
      }
      out.put("ok", true);
      return out;
    } catch (IOException e) {
      return fail(out, "risposta non scritta: " + e.getMessage());
    } finally {
      searches.release();
    }
  }

  /** The routes found and what is needed for their guidance. */
  static final class Found {
    final List<GhEngine.Result> routes = new ArrayList<>();
    final List<String> countries = new ArrayList<>();
    GhCross.Trip trip;
    GhEngine engineOfFirst;
    String error, softWhy;
  }

  Found find(List<double[]> points, List<Double> headings, List<String> ids, TruckSpec spec, int alternatives) {
    Found f = new Found();
    Set<String> distinct = new java.util.LinkedHashSet<>(ids);
    if (distinct.size() > 1) {
      // a trip abroad: the countries' graphs joined at the crossings (one route)
      GhCross.Trip trip = GhCross.route(graphs, points, ids, headings.get(0), spec);
      if (trip.error != null || trip.whole == null) {
        f.error = "percorso tra Paesi: " + (trip.error != null ? trip.error : "nessun percorso");
        return f;
      }
      f.trip = trip;
      f.routes.add(trip.whole);
      f.countries.addAll(trip.countries);
      f.engineOfFirst = trip.engines.isEmpty() ? null : trip.engines.get(0);
      log(String.format(Locale.ROOT, "route across %s: %.1f km, %d min, %s", trip.countries, trip.whole.distanceM / 1000, trip.whole.timeMs / 60000, trip.note));
      return f;
    }
    String id = ids.get(0);
    GhEngine e = engine(id);
    if (e == null) {
      f.error = "il grafo di " + id + " non si apre";
      return f;
    }
    if (!e.canRoute(spec)) {
      f.error = "il grafo di " + id + " non ha il calcolo per " + (spec.hgv ? "i camion" : "camper, bus e auto");
      return f;
    }
    List<GhEngine.Result> rs = e.routes(points, headings, spec, 1 + alternatives);
    for (GhEngine.Result r : rs) if (r.ok()) f.routes.add(r);
    if (f.routes.isEmpty()) f.error = "nessuna strada percorribile con queste misure e questi divieti (" + (rs.isEmpty() ? "?" : rs.get(0).error) + ")";
    f.countries.add(id);
    f.engineOfFirst = e;
    return f;
  }

  /** As the tablet (OfflineRouteProvider.ghGuide): the stops are the breaks, the "via" points are not. */
  static GhGuide.Output guideOf(Found f, GhEngine.Result r, TruckSpec spec, List<double[]> points, List<String> kinds) {
    TreeMap<Integer, double[]> stops = new TreeMap<>();
    stops.put(0, points.get(0));
    stops.put(r.lat.length - 1, points.get(points.size() - 1));
    for (int k = 1; k < r.waypointIndex.length - 1; k++) {
      if (!"via".equals(kinds.get(Math.min(k, kinds.size() - 1))) && k < points.size()) stops.put(r.waypointIndex[k], points.get(k));
    }
    int[] breaks = new int[stops.size()];
    int i = 0;
    for (int b : stops.keySet()) breaks[i++] = b;
    List<double[]> requested = new ArrayList<>(stops.values());
    try {
      if (f.trip != null) {
        for (GhEngine e : f.trip.engines) if (!e.canGuide()) return null;
        return GhEngine.guide(f.trip, spec, breaks, requested);
      }
      if (f.engineOfFirst == null || !f.engineOfFirst.canGuide()) return null;
      return f.engineOfFirst.guide(r, spec, breaks, requested);
    } catch (RuntimeException ex) {
      log("guidance failed: " + ex);
      return null;
    }
  }

  static ObjectNode fail(ObjectNode out, String why) {
    out.put("ok", false);
    out.put("error", why);
    return out;
  }

  /** Google encoded polyline, 6 decimals (as the OSRM answers). */
  static String polyline6(double[] lat, double[] lon) {
    StringBuilder sb = new StringBuilder();
    long pLat = 0, pLon = 0;
    for (int i = 0; i < lat.length; i++) {
      long a = Math.round(lat[i] * 1e6), b = Math.round(lon[i] * 1e6);
      enc(a - pLat, sb);
      enc(b - pLon, sb);
      pLat = a;
      pLon = b;
    }
    return sb.toString();
  }

  private static void enc(long v, StringBuilder sb) {
    long s = v << 1;
    if (v < 0) s = ~s;
    while (s >= 0x20) {
      sb.append((char) ((0x20 | (s & 0x1f)) + 63));
      s >>= 5;
    }
    sb.append((char) (s + 63));
  }

  // ----------------------------------------------------------------------------------------- HTTP

  String keyName(HttpExchange x) {
    String h = x.getRequestHeaders().getFirst("Authorization");
    if (h == null || !h.startsWith("Bearer ")) return null;
    String hash = sha256(h.substring(7).trim());
    for (Map.Entry<String, String> e : keys.entrySet()) {
      if (MessageDigest.isEqual(e.getKey().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) return e.getValue();
    }
    return null;
  }

  static String sha256(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : d) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void send(HttpExchange x, int code, JsonNode body) throws IOException {
    byte[] b = JSON.writeValueAsBytes(body);
    x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    // the answers with the guidance are big (1-2 MB across countries): compressed for the mobile data
    String ae = x.getRequestHeaders().getFirst("Accept-Encoding");
    if (ae != null && ae.contains("gzip") && b.length > 1024) {
      java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(b.length / 6);
      try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) {
        gz.write(b);
      }
      b = bo.toByteArray();
      x.getResponseHeaders().set("Content-Encoding", "gzip");
    }
    x.sendResponseHeaders(code, b.length);
    try (OutputStream os = x.getResponseBody()) {
      os.write(b);
    }
  }

  void health(HttpExchange x) throws IOException {
    ObjectNode o = JSON.createObjectNode();
    o.put("ok", true);
    o.put("version", VERSION);
    o.put("started", started.toString());
    ArrayNode c = o.putArray("countries");
    for (String id : installed()) c.add(id);
    ArrayNode op = o.putArray("open");
    for (String id : new TreeMap<>(engines).keySet()) op.add(id);
    send(x, 200, o);
  }

  void routeHttp(HttpExchange x) throws IOException {
    if (!"POST".equals(x.getRequestMethod())) {
      send(x, 405, fail(JSON.createObjectNode(), "usa POST"));
      return;
    }
    String who = keyName(x);
    if (who == null) {
      send(x, 401, fail(JSON.createObjectNode(), "chiave mancante o non valida"));
      return;
    }
    long t = System.currentTimeMillis();
    JsonNode q;
    try (InputStream in = x.getRequestBody()) {
      byte[] b = in.readNBytes(2_000_000);
      q = JSON.readTree(b);
    } catch (IOException e) {
      send(x, 400, fail(JSON.createObjectNode(), "richiesta non leggibile"));
      return;
    }
    ObjectNode out;
    try {
      out = route(q);
    } catch (Throwable e) {
      log("route failed: " + e);
      out = fail(JSON.createObjectNode(), "errore del server (" + e.getClass().getSimpleName() + ")");
    }
    out.put("serverMs", System.currentTimeMillis() - t);
    log(String.format(Locale.ROOT, "%s: %d points, ok=%s, %d ms%s", who, q.path("points").size(), out.path("ok").asBoolean(),
        System.currentTimeMillis() - t, out.has("error") ? " (" + out.path("error").asText() + ")" : ""));
    send(x, 200, out);
  }

  static void log(String s) {
    System.out.println(Instant.now() + " " + s);
  }

  static String env(String k, String def) {
    String v = System.getenv(k);
    return v == null || v.isEmpty() ? def : v;
  }

  public static void main(String[] args) throws Exception {
    File data = new File(env("NM_DATA", "/opt/navmaster/data"));
    File models = new File(env("NM_MODELS", "/opt/navmaster/models"));
    File keys = new File(env("NM_KEYS", "/opt/navmaster/keys.txt"));
    int threads = Integer.parseInt(env("NM_THREADS", "4"));
    NmServer s = new NmServer(data, models, keys, threads);
    if (s.keys.isEmpty()) log("ATTENZIONE: nessuna chiave in " + keys + ": nessun tablet può chiedere percorsi");
    String preload = env("NM_PRELOAD", "");
    List<String> pre = "all".equals(preload) ? s.installed() : preload.isEmpty() ? Collections.emptyList() : Arrays.asList(preload.split(","));
    for (String id : pre) s.engine(id.trim());
    HttpServer http = HttpServer.create(new InetSocketAddress(env("NM_BIND", "127.0.0.1"), Integer.parseInt(env("NM_PORT", "8989"))), 64);
    http.setExecutor(Executors.newFixedThreadPool(threads + 4));
    http.createContext("/v1/health", x -> {
      try {
        s.health(x);
      } finally {
        x.close();
      }
    });
    http.createContext("/v1/route", x -> {
      try {
        s.routeHttp(x);
      } finally {
        x.close();
      }
    });
    http.start();
    log("NavMaster server " + VERSION + " on " + http.getAddress());
  }
}
