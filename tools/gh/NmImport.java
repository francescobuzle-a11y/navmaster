import app.navmaster.truck.routing.gh.GhEngine;
import app.navmaster.truck.routing.gh.NmWeightingFactory;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInputFile;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.reader.osm.WaySegmentParser;
import com.graphhopper.routing.OSMReaderConfig;
import com.graphhopper.routing.WeightingFactory;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValueImpl;
import com.graphhopper.routing.ev.DefaultImportRegistry;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.ImportRegistry;
import com.graphhopper.routing.ev.ImportUnit;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValueImpl;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.util.AreaIndex;
import com.graphhopper.routing.util.CustomArea;
import com.graphhopper.routing.util.OSMParsers;
import com.graphhopper.routing.util.parsers.AbstractAccessParser;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.search.KVStorage;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.Helper;
import com.graphhopper.util.shapes.GHPoint3D;
import nmors.DateRangeParser;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongToIntFunction;
import java.util.function.ToIntFunction;
import java.util.function.Predicate;

/**
 * Builds the GraphHopper graph of a country for the tablet, with openrouteservice's own way of
 * reading OpenStreetMap for lorries ("driving-hgv") and cars ("driving-car"): which roads each one
 * may use and in which direction, its speed on every road, how much a lorry prefers it, the vehicle
 * types it is closed to, the signed limits, destination/private access, service roads, tolls,
 * ferries and the turn restrictions - every rule in tools/gh/OrsRules.java, named after the
 * openrouteservice class it comes from. They are stored in the graph (ors_*), and the routes are
 * weighed on the tablet exactly as openrouteservice weighs them (routing/gh/OrsWeighting.java).
 *
 * Besides openrouteservice's values, the graph keeps what the guidance on the tablet needs (names,
 * refs, lanes, exits, toll booths: GhGuide).
 *
 * usage: java -cp gh.jar:poole.jar:classes NmImport ROADS.osm.pbf GRAPH_DIR MODELS_DIR
 */
public class NmImport {
  public static void main(String[] a) throws Exception {
    File pbf = new File(a[0]);
    // the day the graph is built: openrouteservice evaluates the date ranges of the conditional
    // restrictions on its own build day (DateRangeParser.createCalendar)
    DateRangeParser today = new DateRangeParser(DateRangeParser.createCalendar());
    OrsRules.Vehicle hgv = new OrsRules.Vehicle(true, today), car = new OrsRules.Vehicle(false, today);
    long t = System.currentTimeMillis();
    NodeLimits limits = NodeLimits.scan(pbf, new OrsRules.Vehicle(true, today), new OrsRules.Vehicle(false, today));
    System.out.println("NMIMPORT node limits: " + limits.tags.size() + " nodes, " + limits.pillars() + " inside one way, "
        + (System.currentTimeMillis() - t) / 1000 + " s");

    GraphHopperConfig cfg = new GraphHopperConfig();
    cfg.putObject("datareader.file", pbf.getAbsolutePath());
    cfg.putObject("graph.location", new File(a[1]).getAbsolutePath());
    cfg.putObject("custom_models.directory", new File(a[2]).getAbsolutePath());
    cfg.putObject("import.osm.ignored_highways", GhEngine.IGNORED_HIGHWAYS);
    cfg.putObject("graph.encoded_values", GhEngine.ENCODED_VALUES);
    cfg.putObject("prepare.lm.landmarks", GhEngine.LANDMARKS);
    // street names, refs, destinations and junction names: the guidance is worked out on the
    // tablet from the graph itself (GhGuide)
    cfg.putObject("datareader.instructions", true);
    cfg.putObject("prepare.lm.threads", 3);
    // landmarks also for the islands (GraphHopper's default leaves without them every part smaller
    // than half the graph: Jersey in Guernsey-Jersey, where no route could be found)
    cfg.putObject("prepare.lm.min_network_size", 2000);
    // the big countries on the runner: on disk (memory mapped), not all in RAM
    cfg.putObject("graph.dataaccess.default_type", System.getProperty("nm.dataaccess", "RAM_STORE"));
    cfg.setProfiles(GhEngine.profiles());
    cfg.setLMProfiles(GhEngine.lmProfiles());
    GraphHopper gh = new GraphHopper() {
      /** As GraphHopper's own, with openrouteservice's preparation of the ways and turn restrictions. */
      @Override
      protected void importOSM() {
        AreaIndex<CustomArea> areas = new AreaIndex<>(new ArrayList<>(GHUtility.readCountries()));
        OSMReader reader = new OrsReader(getBaseGraph().getBaseGraph(), getOSMParsers(), getReaderConfig(), limits);
        reader.setFile(_getOSMFile()).setAreaIndex(areas).setElevationProvider(getElevationProvider())
            .setCountryRuleFactory(getCountryRuleFactory());
        createBaseGraphAndProperties();
        try {
          reader.readGraph();
        } catch (IOException e) {
          throw new RuntimeException("Cannot read file " + _getOSMFile(), e);
        }
        DateFormat f = Helper.createFormatter();
        getProperties().put("datareader.import.date", f.format(new Date()));
        if (reader.getDataDate() != null) getProperties().put("datareader.data.date", f.format(reader.getDataDate()));
      }

      /** The landmarks are prepared with the weights the tablet uses (openrouteservice's). */
      @Override
      protected WeightingFactory createWeightingFactory() {
        return new NmWeightingFactory(getBaseGraph(), getEncodingManager());
      }
    };
    gh.setImportRegistry(new OrsRegistry(hgv, car));
    gh.init(cfg);
    t = System.currentTimeMillis();
    gh.importOrLoad();
    System.out.println("NMIMPORT done in " + (System.currentTimeMillis() - t) / 1000 + " s");
    gh.close();
  }

  /**
   * The signed limits of nodes (maxheight, maxweight… on a node of a way, e.g. under a bridge):
   * openrouteservice gives them to the whole way when the node is inside one way only (a "pillar"
   * node: used once, not the first or the last of its way; ORSOSMReader.applyNodeTagsToWay).
   */
  static final class NodeLimits {
    final LongObjectHashMap<Map<String, String>> tags = new LongObjectHashMap<>();
    /** How many times each of those nodes is used by an accepted way, and -1 if it ends a way. */
    final LongObjectHashMap<int[]> uses = new LongObjectHashMap<>();

    static NodeLimits scan(File pbf, OrsRules.Vehicle hgv, OrsRules.Vehicle car) throws Exception {
      NodeLimits n = new NodeLimits();
      try (OSMInputFile in = new OSMInputFile(pbf).setWorkerThreads(2).open()) {
        ReaderElement e;
        while ((e = in.getNext()) != null) {
          if (e.getType() == ReaderElement.Type.NODE) {
            Map<String, String> m = null;
            for (Map.Entry<String, Object> kv : e.getTags().entrySet()) {
              if (!OrsRules.NODE_LIMIT_TAGS.contains(kv.getKey())) continue;
              if (m == null) m = new HashMap<>();
              m.put(kv.getKey(), String.valueOf(kv.getValue()));
            }
            if (m != null) {
              n.tags.put(e.getId(), m);
              n.uses.put(e.getId(), new int[2]);
            }
          } else if (e.getType() == ReaderElement.Type.WAY) {
            ReaderWay way = (ReaderWay) e;
            int size = way.getNodes().size();
            if (size < 2 || n.tags.isEmpty()) continue;
            // the ways openrouteservice reads (its lorry or its car graph)
            if (hgv.access(way) == OrsRules.SKIP && car.access(way) == OrsRules.SKIP) continue;
            for (int i = 0; i < size; i++) {
              int[] u = n.uses.get(way.getNodes().get(i));
              if (u == null) continue;
              u[0]++;
              if (i == 0 || i == size - 1) u[1] = 1;
            }
          } else if (e.getType() == ReaderElement.Type.RELATION) {
            break;
          }
        }
      }
      return n;
    }

    boolean pillar(long id) {
      int[] u = uses.get(id);
      return u != null && u[0] == 1 && u[1] == 0;
    }

    int pillars() {
      int c = 0;
      for (com.carrotsearch.hppc.cursors.LongCursor k : uses.keys()) if (pillar(k.value)) c++;
      return c;
    }
  }

  /** GraphHopper's reader with openrouteservice's preparation of the ways and turn restrictions (ORSOSMReader). */
  static final class OrsReader extends OSMReader {
    private final NodeLimits limits;

    OrsReader(BaseGraph graph, OSMParsers parsers, OSMReaderConfig config, NodeLimits limits) {
      super(graph, parsers, config);
      this.limits = limits;
    }

    @Override
    protected void preprocessWay(ReaderWay way, WaySegmentParser.CoordinateSupplier coords, WaySegmentParser.NodeTagSupplier nodeTags) {
      super.preprocessWay(way, coords, nodeTags);
      guideValues(way, nodeTags);
      int n = way.getNodes().size();
      if (n < 2) return;
      // ORSOSMReader.applyNodeTagsToWay: the limits of the nodes inside the way (not its ends)
      for (int i = 1; i < n - 1; i++) {
        long id = way.getNodes().get(i);
        Map<String, String> m = limits.tags.get(id);
        if (m != null && limits.pillar(id)) for (Map.Entry<String, String> kv : m.entrySet()) way.setTag(kv.getKey(), kv.getValue());
      }
      // recordEstimatedWayDistance: straight line from the first to the last node (0 for a closed way)
      GHPoint3D first = coords.getCoordinate(way.getNodes().get(0)), last = coords.getCoordinate(way.getNodes().get(n - 1));
      if (first != null && last != null && !Double.isNaN(first.lat) && !Double.isNaN(last.lat))
        way.setTag(OrsRules.ESTIMATED_DISTANCE, DistanceCalcEarth.DIST_EARTH.calcDist(first.lat, first.lon, last.lat, last.lon));
      // recordExactWayDistance: the length of a whole ferry way (ferry speed)
      if (OrsRules.ferry(way)) {
        double total = 0;
        GHPoint3D prev = first;
        for (int i = 1; i < n; i++) {
          GHPoint3D next = coords.getCoordinate(way.getNodes().get(i));
          if (prev != null && next != null && !Double.isNaN(prev.lat) && !Double.isNaN(next.lat)) {
            total += DistanceCalcEarth.DIST_EARTH.calcDist(prev.lat, prev.lon, next.lat, next.lon);
            prev = next;
          }
        }
        if (total > 0) way.setTag(OrsRules.EXACT_DISTANCE, total);
      }
    }

    /**
     * Turn restrictions as openrouteservice reads them (only via a node; "restriction" before the
     * "restriction:<vehicle>" tags; "except"): the relation is rewritten for GraphHopper's parsers,
     * "restriction:hgv" for the lorry profiles and "restriction:motorcar" for the others
     * (GhEngine.TURN_VEHICLES_*), and left out when no vehicle follows it.
     */
    @Override
    protected void preprocessRelations(ReaderRelation relation) {
      orsTurnRestriction(relation);
      super.preprocessRelations(relation);
    }

    @Override
    protected void processRelation(ReaderRelation relation, LongToIntFunction getIdForOSMNodeId) {
      orsTurnRestriction(relation);
      super.processRelation(relation, getIdForOSMNodeId);
    }

    static void orsTurnRestriction(ReaderRelation r) {
      if (!r.hasTag("type", "restriction") || r.hasTag("nm:ors")) return;
      long from = -1, via = -1, to = -1;
      for (ReaderRelation.Member m : r.getMembers()) {
        if (m.getType() == ReaderElement.Type.WAY) {
          if ("from".equals(m.getRole())) from = m.getRef();
          else if ("to".equals(m.getRole())) to = m.getRef();
        } else if (m.getType() == ReaderElement.Type.NODE && "via".equals(m.getRole())) {
          via = m.getRef();
        }
      }
      Map<String, Object> tags = new HashMap<>(r.getTags());
      String hgv = OrsRules.turnValue(tags, OrsRules.HGV_RESTRICTIONS);
      String car = OrsRules.turnValue(tags, OrsRules.CAR_RESTRICTIONS);
      for (String k : new ArrayList<>(r.getTags().keySet()))
        if (k.equals("restriction") || k.startsWith("restriction:") || k.equals("except")) r.removeTag(k);
      r.setTag("nm:ors", "1");
      boolean viaWay = r.getMembers().stream().anyMatch(m -> m.getType() == ReaderElement.Type.WAY && "via".equals(m.getRole()));
      if (from < 0 || to < 0 || via < 0 || viaWay || (hgv == null && car == null)) {
        r.setTag("type", "nm_ignored");
        return;
      }
      if (hgv != null) r.setTag("restriction:" + GhEngine.TURN_VEHICLES_TRUCK.get(0), hgv);
      if (car != null) r.setTag("restriction:" + GhEngine.TURN_VEHICLES_CAR.get(0), car);
    }

    /**
     * What the guidance on the tablet needs besides GraphHopper's own names, refs, destinations
     * and junction names (GhGuide): the lane arrows (turn:lanes) and lanes per direction, the
     * number of a motorway exit (ref of the motorway_junction node where a link starts).
     */
    private static void guideValues(ReaderWay way, WaySegmentParser.NodeTagSupplier nodeTags) {
      Map<String, KVStorage.KValue> map = new java.util.LinkedHashMap<>(way.getTag("key_values", Collections.emptyMap()));
      int before = map.size();
      boolean oneway = way.hasTag("oneway", "yes", "1", "true") || way.hasTag("junction", "roundabout", "circular")
          || (way.hasTag("highway", "motorway", "motorway_link", "trunk_link") && !way.hasTag("oneway", "no"));
      boolean reverse = way.hasTag("oneway", "-1", "reverse");
      String fwd = clean(way.getTag("turn:lanes:forward")), bwd = clean(way.getTag("turn:lanes:backward"));
      String both = clean(way.getTag("turn:lanes"));
      if (both != null && fwd == null && bwd == null) {
        if (reverse) bwd = both;
        else if (oneway) fwd = both;
      }
      if (fwd != null || bwd != null) map.put(GUIDE_TURN_LANES, kv(fwd, bwd));
      String lf = clean(way.getTag("lanes:forward")), lb = clean(way.getTag("lanes:backward"));
      if (lf != null || lb != null) map.put(GUIDE_LANES_DIR, kv(lf, lb));
      // what kind of service road (driveways and parking aisles are not counted as roundabout exits)
      if (way.hasTag("highway", "service")) {
        String sv = clean(way.getTag("service"));
        if (sv != null) map.put(GhEngine.KV_SERVICE, new KVStorage.KValue(sv));
      }
      if (way.getNodes().size() > 1 && way.hasTag("highway", "motorway", "motorway_link", "trunk", "trunk_link")) {
        Map<String, Object> first = nodeTags.getTags(way.getNodes().get(0));
        if (first != null && "motorway_junction".equals(first.get("highway"))) {
          Object ref = first.get("ref");
          if (ref instanceof String && !((String) ref).trim().isEmpty()) map.put(GUIDE_JUNCTION_REF, new KVStorage.KValue(KVStorage.cutString(((String) ref).trim())));
        }
      }
      if (map.size() != before) way.setTag("key_values", map);
    }

    private static KVStorage.KValue kv(String fwd, String bwd) {
      return fwd != null && fwd.equals(bwd) ? new KVStorage.KValue(fwd) : new KVStorage.KValue(fwd, bwd);
    }

    private static String clean(String s) {
      if (s == null) return null;
      s = s.trim();
      return s.isEmpty() ? null : KVStorage.cutString(s);
    }

    /**
     * Toll booths, toll gantries and border controls on the edge, as "type@fraction" (fraction of
     * the edge's length where the node is): the tablet shows and announces them.
     */
    @Override
    protected void addEdge(int fromIndex, int toIndex, com.graphhopper.util.PointList pointList, ReaderWay way, List<Map<String, Object>> nodeTags) {
      StringBuilder found = null;
      double total = 0;
      double[] at = new double[pointList.size()];
      for (int i = 1; i < pointList.size(); i++) {
        total += DistanceCalcEarth.DIST_EARTH.calcDist(pointList.getLat(i - 1), pointList.getLon(i - 1), pointList.getLat(i), pointList.getLon(i));
        at[i] = total;
      }
      for (int i = 0; i < nodeTags.size(); i++) {
        String type = nodeType(nodeTags.get(i));
        if (type == null) continue;
        if (found == null) found = new StringBuilder();
        else found.append(';');
        found.append(type).append('@').append(String.format(java.util.Locale.ROOT, "%.3f", total > 0 ? at[i] / total : 0));
      }
      if (found == null) {
        super.addEdge(fromIndex, toIndex, pointList, way, nodeTags);
        return;
      }
      Map<String, KVStorage.KValue> old = way.getTag("key_values", Collections.emptyMap());
      Map<String, KVStorage.KValue> map = new java.util.LinkedHashMap<>(old);
      map.put(GUIDE_NODES, new KVStorage.KValue(found.toString()));
      way.setTag("key_values", map);
      try {
        super.addEdge(fromIndex, toIndex, pointList, way, nodeTags);
      } finally {
        way.setTag("key_values", old);
      }
    }

    private static String nodeType(Map<String, Object> tags) {
      if (tags == null || tags.isEmpty()) return null;
      Object b = tags.get("barrier"), h = tags.get("highway");
      if ("toll_booth".equals(b)) return "toll_booth";
      if ("toll_gantry".equals(h)) return "toll_gantry";
      if ("border_control".equals(b)) return "border_control";
      return null;
    }
  }

  /** Keys of the guidance values (read on the tablet by GhGuide). */
  static final String GUIDE_TURN_LANES = GhEngine.KV_TURN_LANES, GUIDE_LANES_DIR = GhEngine.KV_LANES_DIR,
      GUIDE_JUNCTION_REF = GhEngine.KV_JUNCTION_REF, GUIDE_NODES = GhEngine.KV_NODES;

  /** GraphHopper's encoded values, plus openrouteservice's (ors_*: GhEngine.ORS_*). */
  static final class OrsRegistry implements ImportRegistry {
    private final DefaultImportRegistry std = new DefaultImportRegistry();
    private final OrsRules.Vehicle hgv, car;

    OrsRegistry(OrsRules.Vehicle hgv, OrsRules.Vehicle car) {
      this.hgv = hgv;
      this.car = car;
    }

    @Override
    public ImportUnit createImportUnit(String name) {
      switch (name) {
        case GhEngine.ORS_HGV_ACCESS:
          return ImportUnit.create(name, p -> new SimpleBooleanEncodedValue(name, true),
              (lookup, p) -> new AccessParser(lookup.getBooleanEncodedValue(name), hgv));
        case GhEngine.ORS_CAR_ACCESS:
          return ImportUnit.create(name, p -> new SimpleBooleanEncodedValue(name, true),
              (lookup, p) -> new AccessParser(lookup.getBooleanEncodedValue(name), car));
        case GhEngine.ORS_HGV_SPEED:
          return ImportUnit.create(name, p -> new DecimalEncodedValueImpl(name, 5, 5, true),
              (lookup, p) -> new SpeedParser(lookup.getDecimalEncodedValue(name), hgv));
        case GhEngine.ORS_CAR_SPEED:
          return ImportUnit.create(name, p -> new DecimalEncodedValueImpl(name, 5, 5, true),
              (lookup, p) -> new SpeedParser(lookup.getDecimalEncodedValue(name), car));
        case GhEngine.ORS_HGV_PRIORITY:
          return intUnit(name, 3, w -> hgv.access(w) == OrsRules.SKIP ? -1 : OrsRules.hgvPriority(w));
        case GhEngine.ORS_ROAD_ACCESS:
          return intUnit(name, 4, OrsRules::roadAccess);
        case GhEngine.ORS_TOLL:
          return intUnit(name, 2, OrsRules::toll);
        case GhEngine.ORS_SERVICE:
          return boolUnit(name, w -> OrsRules.service(w, false));
        case GhEngine.ORS_FERRY:
          return boolUnit(name, OrsRules::ferry);
        case GhEngine.ORS_HGV_TYPE_NO:
          return boolUnit(name, OrsRules::hgvTypeBlocked);
        case GhEngine.ORS_BUS_TYPE_NO:
          return boolUnit(name, OrsRules::busTypeBlocked);
        case GhEngine.ORS_HAZMAT_NO:
          return boolUnit(name, OrsRules::hazmatBlocked);
        case GhEngine.ORS_MAX_HEIGHT:
          return limitUnit(name, 11, 0.01, OrsRules.HEIGHT_TAGS, false);
        case GhEngine.ORS_MAX_WIDTH:
          return limitUnit(name, 10, 0.01, OrsRules.WIDTH_TAGS, false);
        case GhEngine.ORS_MAX_LENGTH:
          return limitUnit(name, 9, 0.1, OrsRules.LENGTH_TAGS, false);
        case GhEngine.ORS_MAX_WEIGHT:
          return limitUnit(name, 11, 0.1, OrsRules.WEIGHT_TAGS, true);
        case GhEngine.ORS_MAX_AXLE_LOAD:
          return limitUnit(name, 9, 0.1, OrsRules.AXLE_TAGS, true);
        default:
          return std.createImportUnit(name);
      }
    }

    private static ImportUnit intUnit(String name, int bits, ToIntFunction<ReaderWay> value) {
      return ImportUnit.create(name, p -> new IntEncodedValueImpl(name, bits, false), (lookup, p) -> {
        IntEncodedValue enc = lookup.getIntEncodedValue(name);
        return (TagParser) (edgeId, ia, way, rel) -> {
          int v = value.applyAsInt(way);
          if (v > 0) enc.setInt(false, edgeId, ia, v);
        };
      });
    }

    private static ImportUnit boolUnit(String name, Predicate<ReaderWay> value) {
      return ImportUnit.create(name, p -> new SimpleBooleanEncodedValue(name, false), (lookup, p) -> {
        BooleanEncodedValue enc = lookup.getBooleanEncodedValue(name);
        return (TagParser) (edgeId, ia, way, rel) -> {
          if (value.test(way)) enc.setBool(false, edgeId, ia, true);
        };
      });
    }

    /** ORS-GH OSMMax*Parser: the first tag of the list, in metres or tonnes, 0 = no limit. */
    private static ImportUnit limitUnit(String name, int bits, double factor, List<String> keys, boolean tons) {
      return ImportUnit.create(name, p -> new DecimalEncodedValueImpl(name, bits, 0, factor, false, false, true), (lookup, p) -> {
        DecimalEncodedValue enc = lookup.getDecimalEncodedValue(name);
        double max = enc.getMaxStorableDecimal();
        return (TagParser) (edgeId, ia, way, rel) -> {
          String raw = OrsRules.firstTag(way, keys);
          double v = tons ? OrsRules.tons(raw) : OrsRules.meters(raw);
          if (Double.isNaN(v)) return;
          if (v > max) v = max;
          if (v < 0) return;
          enc.setDecimal(false, edgeId, ia, v);
        };
      });
    }
  }

  /** Access per direction: the road, one-ways, barriers (VehicleFlagEncoder / ORSAbstractFlagEncoder). */
  static final class AccessParser extends AbstractAccessParser {
    private final OrsRules.Vehicle v;

    AccessParser(BooleanEncodedValue enc, OrsRules.Vehicle v) {
      super(enc, v.restrictions);
      this.v = v;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess ia, ReaderWay way) {
      int acc = v.access(way);
      if (acc == OrsRules.SKIP) return;
      boolean[] dir = v.directions(way, acc);
      if (dir[0]) accessEnc.setBool(false, edgeId, ia, true);
      if (dir[1]) accessEnc.setBool(true, edgeId, ia, true);
      if (way.hasTag("gh:barrier_edge") && way.hasTag("node_tags")) {
        List<Map<String, Object>> nodeTags = way.getTag("node_tags", Collections.emptyList());
        if (!nodeTags.isEmpty()) handleBarrierEdge(edgeId, ia, nodeTags.get(0));
      }
    }

    @Override
    public boolean isBarrier(ReaderNode node) {
      return v.barrier(node);
    }
  }

  /** The speed, both directions (VehicleFlagEncoder.handleWayTags with use_acceleration: true). */
  static final class SpeedParser implements TagParser {
    private final DecimalEncodedValue enc;
    private final OrsRules.Vehicle v;

    SpeedParser(DecimalEncodedValue enc, OrsRules.Vehicle v) {
      this.enc = enc;
      this.v = v;
    }

    @Override
    public void handleWayTags(int edgeId, EdgeIntAccess ia, ReaderWay way, IntsRef relationFlags) {
      int acc = v.access(way);
      if (acc == OrsRules.SKIP) return;
      double speed = v.speed(way, acc);
      enc.setDecimal(false, edgeId, ia, speed);
      enc.setDecimal(true, edgeId, ia, speed);
    }
  }
}
