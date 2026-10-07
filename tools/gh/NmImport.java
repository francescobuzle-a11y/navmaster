import app.navmaster.truck.routing.gh.GhEngine;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValueImpl;
import com.graphhopper.routing.ev.DefaultImportRegistry;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.FerrySpeed;
import com.graphhopper.routing.ev.ImportRegistry;
import com.graphhopper.routing.ev.ImportUnit;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.util.parsers.AbstractAccessParser;
import com.graphhopper.routing.util.parsers.TagParser;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.reader.osm.OSMReader;
import com.graphhopper.reader.osm.WaySegmentParser;
import com.graphhopper.routing.OSMReaderConfig;
import com.graphhopper.routing.util.AreaIndex;
import com.graphhopper.routing.util.CustomArea;
import com.graphhopper.routing.util.OSMParsers;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.Helper;
import com.graphhopper.util.shapes.GHPoint3D;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the GraphHopper graph of a country for the tablet, with openrouteservice's own rules for
 * the roads (its "driving-hgv" and "driving-car" profiles, version on GitHub main, 10/2026): which
 * roads a lorry or a car may use, the speed on each road and, for lorries, how much each road is
 * preferred ("recommended"). They are worked out here from the OSM tags exactly as openrouteservice
 * does when it builds its graph, and stored in the graph (ors_*): the custom models of the app
 * (assets/gh) only read them.
 *
 * The same numbers as openrouteservice (heavyvehicle.json / car.json, HeavyVehicleFlagEncoder,
 * VehicleFlagEncoder, CarFlagEncoder, VehicleAccessParser, ORSAbstractFlagEncoder.isBarrier),
 * rewritten here (no code copied), including its "estimated_distance" (straight line between the
 * first and the last node of the OSM way, OrsReader). Not evaluated: the date-dependent tags
 * (":conditional").
 *
 * usage: java -cp gh.jar:classes NmImport ROADS.osm.pbf GRAPH_DIR MODELS_DIR
 */
public class NmImport {
  public static void main(String[] a) throws Exception {
    GraphHopperConfig cfg = new GraphHopperConfig();
    cfg.putObject("datareader.file", new File(a[0]).getAbsolutePath());
    cfg.putObject("graph.location", new File(a[1]).getAbsolutePath());
    cfg.putObject("custom_models.directory", new File(a[2]).getAbsolutePath());
    cfg.putObject("import.osm.ignored_highways", GhEngine.IGNORED_HIGHWAYS);
    cfg.putObject("graph.encoded_values", GhEngine.ENCODED_VALUES);
    cfg.putObject("prepare.lm.landmarks", 8);
    cfg.putObject("prepare.lm.threads", 3);
    // the big countries on the runner: on disk (memory mapped), not all in RAM
    cfg.putObject("graph.dataaccess.default_type", System.getProperty("nm.dataaccess", "RAM_STORE"));
    cfg.setProfiles(GhEngine.profiles());
    cfg.setLMProfiles(GhEngine.lmProfiles());
    GraphHopper gh = new GraphHopper() {
      /** As GraphHopper's own, with the reader that adds openrouteservice's estimated distance. */
      @Override
      protected void importOSM() {
        AreaIndex<CustomArea> areas = new AreaIndex<>(new ArrayList<>(GHUtility.readCountries()));
        OSMReader reader = new OrsReader(getBaseGraph().getBaseGraph(), getOSMParsers(), getReaderConfig());
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
    };
    gh.setImportRegistry(new OrsRegistry());
    gh.init(cfg);
    long t = System.currentTimeMillis();
    gh.importOrLoad();
    System.out.println("NMIMPORT done in " + (System.currentTimeMillis() - t) / 1000 + " s");
    gh.close();
  }

  /**
   * openrouteservice's "estimated_distance" of a way: the straight line from its first to its last
   * node (none for a closed way), used for the acceleration on short roads and the residential
   * streets with many nodes.
   */
  static final class OrsReader extends OSMReader {
    OrsReader(BaseGraph graph, OSMParsers parsers, OSMReaderConfig config) {
      super(graph, parsers, config);
    }

    @Override
    protected void preprocessWay(ReaderWay way, WaySegmentParser.CoordinateSupplier coords, WaySegmentParser.NodeTagSupplier nodeTags) {
      super.preprocessWay(way, coords, nodeTags);
      int n = way.getNodes().size();
      if (n < 2) return;
      long first = way.getNodes().get(0), last = way.getNodes().get(n - 1);
      if (first == last) return;
      GHPoint3D a = coords.getCoordinate(first), b = coords.getCoordinate(last);
      if (a == null || b == null || Double.isNaN(a.lat) || Double.isNaN(b.lat)) return;
      way.setTag(Ors.ESTIMATED_DISTANCE, DistanceCalcEarth.DIST_EARTH.calcDist(a.lat, a.lon, b.lat, b.lon));
    }
  }

  /** GraphHopper's encoded values, plus openrouteservice's. */
  static final class OrsRegistry implements ImportRegistry {
    private final DefaultImportRegistry std = new DefaultImportRegistry();

    @Override
    public ImportUnit createImportUnit(String name) {
      switch (name) {
        case Ors.HGV_ACCESS:
          return ImportUnit.create(name, p -> new SimpleBooleanEncodedValue(name, true),
              (lookup, p) -> new Ors.AccessParser(lookup.getBooleanEncodedValue(name), true));
        case Ors.CAR_ACCESS:
          return ImportUnit.create(name, p -> new SimpleBooleanEncodedValue(name, true),
              (lookup, p) -> new Ors.AccessParser(lookup.getBooleanEncodedValue(name), false));
        case Ors.HGV_SPEED:
          return ImportUnit.create(name, p -> new DecimalEncodedValueImpl(name, 5, 5, true),
              (lookup, p) -> new Ors.SpeedParser(lookup.getDecimalEncodedValue(name), lookup.getDecimalEncodedValue(FerrySpeed.KEY), true),
              FerrySpeed.KEY);
        case Ors.CAR_SPEED:
          return ImportUnit.create(name, p -> new DecimalEncodedValueImpl(name, 5, 5, true),
              (lookup, p) -> new Ors.SpeedParser(lookup.getDecimalEncodedValue(name), lookup.getDecimalEncodedValue(FerrySpeed.KEY), false),
              FerrySpeed.KEY);
        case Ors.HGV_RECOMMENDED:
          return ImportUnit.create(name, p -> new DecimalEncodedValueImpl(name, 10, 0.001, false),
              (lookup, p) -> new Ors.PriorityParser(lookup.getDecimalEncodedValue(name)));
        default:
          return std.createImportUnit(name);
      }
    }
  }

  /** openrouteservice's rules for lorries (driving-hgv) and cars (driving-car). */
  static final class Ors {
    static final String HGV_ACCESS = "ors_hgv_access", CAR_ACCESS = "ors_car_access";
    static final String HGV_SPEED = "ors_hgv_speed", CAR_SPEED = "ors_car_speed";
    static final String HGV_RECOMMENDED = "ors_hgv_recommended";
    static final String ESTIMATED_DISTANCE = "ors_estimated_distance";

    // speed_limits/heavyvehicle.json and car.json: "default", "surface", "tracktype"
    static final Map<String, Integer> HGV_DEFAULT = map("motorway", 85, "motorway_link", 50, "motorroad", 80, "trunk", 80,
        "trunk_link", 50, "primary", 60, "primary_link", 50, "secondary", 60, "secondary_link", 50, "tertiary", 50,
        "tertiary_link", 40, "unclassified", 30, "residential", 30, "living_street", 10, "service", 20, "road", 20, "track", 15);
    static final Map<String, Integer> CAR_DEFAULT = map("motorway", 100, "motorway_link", 60, "motorroad", 90, "trunk", 85,
        "trunk_link", 60, "primary", 65, "primary_link", 50, "secondary", 60, "secondary_link", 50, "tertiary", 50,
        "tertiary_link", 40, "unclassified", 30, "residential", 30, "living_street", 10, "service", 20, "road", 20, "track", 15);
    static final Map<String, Integer> HGV_SURFACE = surface(60, 60, 50);
    static final Map<String, Integer> CAR_SURFACE = surface(80, 80, 60);
    static final Map<String, Integer> TRACKTYPE = map("grade1", 40, "grade2", 30, "grade3", 20, "grade4", 15, "grade5", 10);
    // "max_speeds" (zone:maxspeed / zone:traffic, used only without a maxspeed tag)
    static final Map<String, Integer> HGV_ZONES = zones(true);
    static final Map<String, Integer> CAR_ZONES = zones(false);

    static final double HGV_MAX = 90, CAR_MAX = 140, MIN_SPEED = 5, MAX_SPEED_FACTOR = 0.9;

    static final List<String> RESTRICTIONS = Arrays.asList("motorcar", "motor_vehicle", "vehicle", "access");
    static final Set<String> RESTRICTED = set("private", "no", "restricted", "military");
    static final Set<String> CAR_RESTRICTED = set("private", "no", "restricted", "military", "agricultural", "forestry", "delivery", "emergency");
    static final Set<String> INTENDED = set("yes", "permissive", "destination", "permit");
    static final Set<String> HGV_INTENDED = set("yes", "permissive", "destination", "permit", "designated", "agricultural", "forestry",
        "delivery", "bus", "hgv", "goods");
    static final List<String> HGV_ACCESS_KEYS = Arrays.asList("hgv", "goods", "bus", "agricultural", "forestry", "delivery");
    static final Set<String> ONEWAYS = set("yes", "true", "1", "-1");
    static final Set<String> BLOCK_BY_DEFAULT = set("bollard", "stile", "turnstile", "cycle_barrier", "motorcycle_barrier", "block");
    static final Set<String> CAR_BLOCK_BY_DEFAULT = set("bollard", "stile", "turnstile", "cycle_barrier", "motorcycle_barrier", "block",
        "bus_trap", "sump_buster");
    static final Set<String> PASS_BY_DEFAULT = set("gate", "lift_gate", "kissing_gate", "swing_gate");

    // HeavyVehicleAttributes
    static final int GOODS = 1, HGV = 2, BUS = 4, AGRICULTURE = 8, FORESTRY = 16, DELIVERY = 32;
    static final int ANY = GOODS | HGV | BUS | AGRICULTURE | FORESTRY | DELIVERY;

    // PriorityCode
    static final int WORST = 0, AVOID_AT_ALL_COSTS = 1, REACH_DEST = 2, AVOID_IF_POSSIBLE = 3, UNCHANGED = 4, PREFER = 5,
        VERY_NICE = 6, BEST = 7;

    static Map<String, Integer> map(Object... kv) {
      Map<String, Integer> m = new HashMap<>();
      for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
      return m;
    }

    static Set<String> set(String... v) {
      return new HashSet<>(Arrays.asList(v));
    }

    static Map<String, Integer> surface(int cement, int compacted, int fineGravel) {
      return map("asphalt", -1, "concrete", -1, "concrete:plates", -1, "concrete:lanes", -1, "paved", -1, "cement", cement,
          "compacted", compacted, "fine_gravel", fineGravel, "paving_stones", 40, "metal", 40, "bricks", 40, "grass", 30, "wood", 30,
          "sett", 30, "grass_paver", 30, "gravel", 30, "unpaved", 30, "ground", 30, "dirt", 30, "pebblestone", 30, "tartan", 30,
          "cobblestone", 20, "clay", 20, "earth", 15, "stone", 15, "rocky", 15, "sand", 15, "mud", 10, "unknown", 30);
    }

    static Map<String, Integer> zones(boolean hgv) {
      Map<String, Integer> m = new HashMap<>();
      String[] cc = {"AT", "CH", "CZ", "DK", "DE", "FI", "FR", "GR", "HU", "IT", "PL", "RO", "SK", "SI", "ES", "SE", "UA"};
      if (hgv) {
        for (String c : cc) {
          m.put((c + ":urban").toLowerCase(), 50);
          m.put((c + ":rural").toLowerCase(), 80);
          m.put((c + ":trunk").toLowerCase(), 80);
          m.put((c + ":motorway").toLowerCase(), 80);
        }
        m.put("cz:rural", 90); m.put("se:rural", 70); m.remove("dk:trunk"); m.remove("de:trunk"); m.remove("pl:trunk");
        m.put("de:living_street", 7); m.put("pl:living_street", 20); m.put("ua:urban", 60);
        m.put("ru:living_street", 20); m.put("ru:rural", 80); m.put("ru:urban", 60); m.put("ru:motorway", 80);
        m.put("jp:national", 60); m.put("jp:motorway", 80);
        m.put("gb:nsl_single", 80); m.put("gb:nsl_dual", 96); m.put("gb:motorway", 96);
        m.put("uz:living_street", 30); m.put("uz:urban", 70); m.put("uz:rural", 90); m.put("uz:motorway", 90);
      }
      return m;
    }

    static boolean valid(double s) {
      return !Double.isNaN(s);
    }

    /** A maxspeed value in km/h as openrouteservice reads it (OSMValueExtractor.stringToKmh), NaN if not a speed. */
    static double kmh(String v) {
      if (v == null) return Double.NaN;
      String str = v.trim();
      if (str.isEmpty()) return Double.NaN;
      if (str.equals("none")) return 140;
      if (str.endsWith(":rural") || str.endsWith(":trunk")) return 80;
      if (str.endsWith(":urban")) return 50;
      if (str.equals("walk") || str.endsWith(":living_street")) return 6;
      double factor = 1;
      int i = str.indexOf("mp");
      if (i > 0) {
        str = str.substring(0, i).trim();
        factor = 1.609344;
      } else if ((i = str.indexOf("knots")) > 0) {
        str = str.substring(0, i).trim();
        factor = 1.852;
      } else if ((i = str.indexOf("km")) > 0 || (i = str.indexOf("kph")) > 0) {
        str = str.substring(0, i).trim();
      }
      try {
        double s = Double.parseDouble(str) * factor;
        return s <= 0 || Double.isInfinite(s) ? Double.NaN : s;
      } catch (NumberFormatException e) {
        return Double.NaN;
      }
    }

    /** VehicleFlagEncoder.getHighway: "motorroad" for a motorroad=yes road that is not a motorway. */
    static String highway(ReaderWay way) {
      String h = way.getTag("highway");
      if (h != null && !h.isEmpty() && way.hasTag("motorroad", "yes") && !h.equals("motorway") && !h.equals("motorway_link"))
        h = "motorroad";
      return h;
    }

    /** maxspeed, the lowest of maxspeed / :forward / :backward (AbstractFlagEncoder.getMaxSpeed). */
    static double genericMaxSpeed(ReaderWay way) {
      double m = kmh(way.getTag("maxspeed"));
      double f = kmh(way.getTag("maxspeed:forward"));
      if (valid(f) && (!valid(m) || f < m)) m = f;
      double b = kmh(way.getTag("maxspeed:backward"));
      if (valid(b) && (!valid(m) || b < m)) m = b;
      return m;
    }

    /** HeavyVehicleFlagEncoder.getMaxSpeed: maxspeed:hgv, else maxspeed if not above the lorry's default for the road. */
    static double hgvMaxSpeed(ReaderWay way) {
      double m = kmh(way.getTag("maxspeed:hgv"));
      double f = kmh(way.getTag("maxspeed:hgv:forward"));
      if (valid(f) && (!valid(m) || f < m)) m = f;
      double b = kmh(way.getTag("maxspeed:hgv:backward"));
      if (valid(b) && (!valid(m) || b < m)) m = b;
      if (!valid(m)) {
        m = genericMaxSpeed(way);
        if (valid(m)) {
          String h = highway(way);
          if (h != null && !h.isEmpty()) {
            Integer d = HGV_DEFAULT.get(h);
            if (d != null && d < m) m = Double.NaN;
          }
        }
      }
      return m;
    }

    static String[] firstPriorityValues(ReaderWay way, List<String> keys) {
      for (String k : keys) {
        String v = way.getTag(k);
        if (v != null) return v.split(";");
      }
      return new String[0];
    }

    static int trackGrade(String grade) {
      if (grade == null || grade.equals("unknown")) return 0;
      switch (grade) {
        case "grade1": return 1;
        case "grade2": return 2;
        case "grade3": return 3;
        case "grade4": return 4;
        case "grade5": return 5;
        default:
          int max = 0;
          for (String v : grade.split("[;-]")) {
            int p;
            try {
              p = Integer.parseInt(v.replace("grade", "").trim());
            } catch (NumberFormatException e) {
              return 0;
            }
            if (p > max) max = p;
          }
          return Math.min(5, max);
      }
    }

    /** 0 = cannot use, 1 = road, 2 = ferry (HeavyVehicleFlagEncoder / CarFlagEncoder.getAccess). */
    static int access(ReaderWay way, boolean hgv) {
      String h = way.getTag("highway");
      Set<String> restricted = hgv ? RESTRICTED : CAR_RESTRICTED;
      Set<String> intended = hgv ? HGV_INTENDED : INTENDED;
      String[] values = firstPriorityValues(way, RESTRICTIONS);
      if (h == null) {
        if (way.hasTag("route", "ferry", "shuttle_train")) {
          for (String v : values) {
            if (restricted.contains(v)) return 0;
            if (intended.contains(v)) return 2;
          }
          if (values.length == 0 && !way.hasTag("foot") && !way.hasTag("bicycle")) return 2;
        }
        return 0;
      }
      if (h.equals("track")) {
        String tt = way.getTag("tracktype");
        if (hgv) {
          if (trackGrade(tt) > 1) return 0;
        } else if (tt != null && trackGrade(tt) > 3) return 0;
      }
      if (!(hgv ? HGV_DEFAULT : CAR_DEFAULT).containsKey(h)) return 0;
      if (way.hasTag("impassable", "yes") || way.hasTag("status", "impassable") || way.hasTag("smoothness", "impassable")) return 0;
      for (String v : values) {
        if (!v.isEmpty()) {
          if (restricted.contains(v)) return 0;
          if (intended.contains(v)) return 1;
        }
      }
      if (hgv) {
        boolean carsAllowed = way.hasTag(RESTRICTIONS, intended);
        if (way.hasTag(RESTRICTIONS, restricted) && !carsAllowed && !way.hasTag(HGV_ACCESS_KEYS, intended)) return 0;
      }
      String maxwidth = way.getTag("maxwidth");
      if (maxwidth != null) {
        try {
          if (Double.parseDouble(maxwidth) < 2.0) return 0;
        } catch (Exception ignored) {
          // not a plain number
        }
      }
      return 1;
    }

    /** VehicleAccessParser for vehicle type hgv: is the road closed to lorries? */
    static boolean hgvBlocked(ReaderWay way) {
      if (!way.hasTag("highway")) return false;
      Set<String> hgvValues = set("hgv", "goods", "bus", "agricultural", "forestry", "delivery");
      int blocked = 0;
      if (way.hasTag(RESTRICTIONS, RESTRICTED)) blocked = ANY;
      if (way.hasTag(RESTRICTIONS, hgvValues)) {
        int allowed = 0;
        for (String k : RESTRICTIONS) {
          String v = way.getTag(k);
          if (v == null) continue;
          for (String x : v.split(";")) if (hgvValues.contains(x)) allowed |= type(x);
        }
        blocked = ANY & ~allowed;
      }
      for (Map.Entry<String, Object> e : way.getTags().entrySet()) {
        String key = e.getKey();
        if (!hgvValues.contains(key)) continue;
        String value = String.valueOf(e.getValue());
        String vehicle = hgvValues.contains(value) ? value : key;
        String acc = vehicle.equals(value) || value.equals("yes") || value.equals("designated") ? "yes"
            : value.equals("no") || value.equals("private") ? "no" : null;
        blocked = flags(blocked, vehicle, acc);
        if (vehicle.equals(value)) blocked = flags(blocked, key, "no");
      }
      return (blocked & HGV) == HGV;
    }

    static int type(String v) {
      switch (v.toLowerCase()) {
        case "goods": return GOODS;
        case "hgv": return HGV;
        case "bus": return BUS;
        case "agricultural": return AGRICULTURE;
        case "forestry": return FORESTRY;
        case "delivery": return DELIVERY;
        default: return 0;
      }
    }

    static int flags(int blocked, String vehicle, String access) {
      int f = type(vehicle);
      if ("no".equals(access)) return blocked | f;
      if ("yes".equals(access)) return blocked & ~f;
      return blocked;
    }

    /** Access per direction: the road, the lorry's own restrictions, one-ways, barriers. */
    static final class AccessParser extends AbstractAccessParser {
      private final boolean hgv;

      AccessParser(BooleanEncodedValue enc, boolean hgv) {
        super(enc, RESTRICTIONS);
        this.hgv = hgv;
      }

      @Override
      public void handleWayTags(int edgeId, EdgeIntAccess ia, ReaderWay way) {
        int acc = access(way, hgv);
        if (acc == 0 || (acc == 1 && hgv && hgvBlocked(way))) return;
        if (acc == 2) {
          accessEnc.setBool(false, edgeId, ia, true);
          accessEnc.setBool(true, edgeId, ia, true);
        } else {
          boolean roundabout = way.hasTag("junction", "roundabout");
          boolean oneway = way.hasTag("oneway", ONEWAYS) || way.hasTag("vehicle:backward") || way.hasTag("vehicle:forward")
              || way.hasTag("motor_vehicle:backward") || way.hasTag("motor_vehicle:forward");
          if (oneway || roundabout) {
            boolean back = way.hasTag("oneway", "-1") || way.hasTag("vehicle:forward", "no") || way.hasTag("motor_vehicle:forward", "no");
            if (!back) accessEnc.setBool(false, edgeId, ia, true);
            if (back) accessEnc.setBool(true, edgeId, ia, true);
          } else {
            accessEnc.setBool(false, edgeId, ia, true);
            accessEnc.setBool(true, edgeId, ia, true);
          }
        }
        if (way.hasTag("gh:barrier_edge") && way.hasTag("node_tags")) {
          List<Map<String, Object>> nodeTags = way.getTag("node_tags", Collections.emptyList());
          if (!nodeTags.isEmpty()) handleBarrierEdge(edgeId, ia, nodeTags.get(0));
        }
      }

      /** ORSAbstractFlagEncoder.isBarrier, with barriers blocked by default (block_barriers: true). */
      @Override
      public boolean isBarrier(ReaderNode node) {
        Set<String> intended = hgv ? HGV_INTENDED : INTENDED;
        Set<String> restricted = hgv ? RESTRICTED : CAR_RESTRICTED;
        boolean blockByDefault = node.hasTag("barrier", hgv ? BLOCK_BY_DEFAULT : CAR_BLOCK_BY_DEFAULT);
        if (blockByDefault || node.hasTag("barrier", PASS_BY_DEFAULT)) {
          boolean locked = node.hasTag("locked", "yes");
          for (String res : RESTRICTIONS) {
            if (!locked && node.hasTag(res, intended)) return false;
            if (node.hasTag(res, restricted)) return true;
          }
          return true;
        }
        // fords are not blocked (block_fords: false), unless closed by their own tags
        return (node.hasTag("highway", "ford") || node.hasTag("ford", "yes")) && node.hasTag(RESTRICTIONS, restricted);
      }
    }

    /** The speed (VehicleFlagEncoder.handleWayTags with use_acceleration: true). */
    static final class SpeedParser implements TagParser {
      private final DecimalEncodedValue enc, ferry;
      private final boolean hgv;

      SpeedParser(DecimalEncodedValue enc, DecimalEncodedValue ferry, boolean hgv) {
        this.enc = enc;
        this.ferry = ferry;
        this.hgv = hgv;
      }

      @Override
      public void handleWayTags(int edgeId, EdgeIntAccess ia, ReaderWay way, IntsRef relationFlags) {
        int acc = access(way, hgv);
        if (acc == 0) return;
        double speed;
        if (acc == 2) {
          speed = ferry.getDecimal(false, edgeId, ia);
        } else {
          speed = speed(way, hgv, way.getTag(ESTIMATED_DISTANCE, Double.NaN), way.getNodes().size());
        }
        speed = Math.max(MIN_SPEED, Math.min(hgv ? HGV_MAX : CAR_MAX, speed));
        enc.setDecimal(false, edgeId, ia, speed);
        enc.setDecimal(true, edgeId, ia, speed);
      }
    }

    static double speed(ReaderWay way, boolean hgv, double distance, int points) {
      String h = highway(way);
      Map<String, Integer> defaults = hgv ? HGV_DEFAULT : CAR_DEFAULT;
      Integer s = defaults.get(h);
      double speed = s == null ? MIN_SPEED : s;
      double tagged = hgv ? hgvMaxSpeed(way) : genericMaxSpeed(way);
      int maxSpeed = valid(tagged) ? (int) Math.round(tagged) : 0;
      if (maxSpeed <= 0) maxSpeed = zoneSpeed(way, hgv);
      if (maxSpeed > 0) speed = maxSpeed;
      if ("track".equals(h)) {
        String tt = way.getTag("tracktype");
        if (tt != null && !tt.isEmpty()) {
          Integer t = TRACKTYPE.get(tt);
          if (t != null && t != -1) speed = t;
        }
      }
      // applyMaxSpeed
      if (valid(tagged)) speed = tagged * MAX_SPEED_FACTOR;
      // getSurfaceSpeed
      String surface = way.getTag("surface");
      if (surface != null) {
        Integer ss = (hgv ? HGV_SURFACE : CAR_SURFACE).get(surface);
        int sv = ss == null ? -1 : ss;
        if (speed > sv && sv != -1) speed = sv;
      }
      // short roads: residential streets with many nodes, acceleration elsewhere (not on motorways)
      if (valid(distance)) {
        if ("residential".equals(h)) {
          if (speed != 0) {
            double interim = distance;
            int interimNodes = points - 2;
            if (interimNodes > 0) interim = distance / (interimNodes + 1);
            if (interim < 100) speed = speed * 0.5;
          }
        } else if (!"motorway".equals(h) && !"motorroad".equals(h)) {
          speed = new Acceleration().adjust(distance, speed);
        }
      }
      if (way.hasTag("junction", "roundabout")) {
        if (way.hasTag("highway", "mini_roundabout")) speed = Math.min(speed, 25);
        String lanes = way.getTag("lanes");
        if (lanes != null) {
          try {
            speed = Integer.parseInt(lanes) >= 2 ? Math.min(speed, 40) : Math.min(speed, 35);
          } catch (Exception ignored) {
            // not a number
          }
        }
      }
      return speed;
    }

    static int zoneSpeed(ReaderWay way, boolean hgv) {
      if (way.hasTag("maxspeed")) return -1;
      String key = way.getTag("zone:maxspeed");
      if (key == null) key = way.getTag("zone:traffic");
      if (key == null) return -1;
      Integer r = (hgv ? HGV_ZONES : CAR_ZONES).get(key.toLowerCase());
      return r == null ? -1 : r;
    }

    /** VehicleFlagEncoder.adjustSpeedForAcceleration (10 s to 100 km/h, slower for low speeds). */
    static final class Acceleration {
      private double modifier = 0;

      double secsTo100() {
        return 10 + modifier * 10;
      }

      double acc() {
        return 100.0 / secsTo100();
      }

      double adjust(double distance, double max) {
        if (max >= 80.0) return max;
        if (distance <= 0) return max;
        double n = Math.max(max, 20.0);
        n = (n - 20.0) / (80.0 - 20.0);
        modifier = Math.pow(0.01, n);
        double timeToMax = max / acc();
        double accDistance = distanceIn(max, timeToMax);
        double atMax = distance - accDistance * 2;
        if (atMax < 0) {
          double duration = durationFor(max, distance / 2);
          if (duration == 0) duration = 1;
          return mpsToKmh(distance / (duration * 2));
        }
        double timeAtMax = atMax / kmhToMps(max);
        return mpsToKmh(distance / (timeToMax * 2 + timeAtMax));
      }

      private double durationFor(double max, double dist) {
        double secs = 0, travelled = 0, v = 0;
        while (v < max && travelled < dist) {
          v += acc();
          secs += 1;
          travelled += kmhToMps(v);
        }
        double rest = dist - travelled;
        if (rest > 0) secs += rest / kmhToMps(max);
        return secs;
      }

      private double distanceIn(double max, double duration) {
        double secs = 0, travelled = 0, v = 0;
        while (v < max && secs < duration) {
          v += acc();
          secs += 1;
          travelled += kmhToMps(v);
        }
        double rest = duration - secs;
        if (rest > 0) travelled += rest * kmhToMps(max);
        return travelled;
      }

      private static double kmhToMps(double v) {
        return v * 1000 / 3600;
      }

      private static double mpsToKmh(double v) {
        return 3600 * v / 1000;
      }
    }

    /** HeavyVehicleFlagEncoder.collect: how much a lorry prefers the road, as the "recommended" factor. */
    static final class PriorityParser implements TagParser {
      private final DecimalEncodedValue enc;

      PriorityParser(DecimalEncodedValue enc) {
        this.enc = enc;
      }

      @Override
      public void handleWayTags(int edgeId, EdgeIntAccess ia, ReaderWay way, IntsRef relationFlags) {
        if (access(way, true) == 0) return;
        enc.setDecimal(false, edgeId, ia, factor(priority(way)));
      }
    }

    /**
     * ORSPriorityWeighting multiplies the time by 2^((4 - code) / 3); GraphHopper divides it by its
     * priority: 2^((code - 4) / 3), here halved so that the best roads have 1 (all the weights
     * are divided by 2, the same routes).
     */
    static double factor(int code) {
      return Math.pow(2, (code - 7) / 3.0);
    }

    static int priority(ReaderWay way) {
      TreeMap<Double, Integer> m = new TreeMap<>();
      if (way.hasTag("hgv", "designated") || (way.hasTag("access", "designated") && (way.hasTag("goods", "yes")
          || way.hasTag("hgv", "yes") || way.hasTag("bus", "yes") || way.hasTag("agricultural", "yes") || way.hasTag("forestry", "yes")))) {
        m.put(100d, BEST);
      } else {
        String h = highway(way);
        double ms = hgvMaxSpeed(way);
        if (h != null && !h.isEmpty()) {
          switch (h) {
            case "motorway": case "motorway_link": case "motorroad": case "trunk": case "trunk_link":
              m.put(100d, VERY_NICE);
              break;
            case "primary": case "primary_link": case "secondary": case "secondary_link":
              m.put(100d, PREFER);
              break;
            case "tertiary": case "tertiary_link":
              m.put(100d, UNCHANGED);
              break;
            case "residential": case "service": case "road": case "unclassified":
              if (valid(ms) && ms <= 30) m.put(120d, REACH_DEST);
              else m.put(100d, AVOID_IF_POSSIBLE);
              break;
            case "living_street":
              m.put(100d, AVOID_AT_ALL_COSTS);
              break;
            case "track":
              m.put(100d, WORST);
              break;
            default:
              m.put(40d, AVOID_IF_POSSIBLE);
          }
        } else {
          m.put(100d, UNCHANGED);
        }
        if (valid(ms)) {
          if (ms <= 40) m.put(110d, AVOID_IF_POSSIBLE);
          else if (ms <= 50) m.put(110d, UNCHANGED);
        }
      }
      return m.lastEntry().getValue();
    }
  }
}
