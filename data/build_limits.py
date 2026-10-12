#!/usr/bin/env python3
"""Builds limiti.sqlite: every height / weight / width / length / axle-load limit and every
HGV ban from an OSM extract, indexed on a 0.01 degree grid, so the tablet can warn about
low bridges and banned roads ahead on the route without any connection.

Input: GeoJSON sequence produced by
    osmium export -f geojsonseq --add-unique-id=type_id <limits.osm.pbf>
Usage: build_limits.py <in.geojsonseq> <out.sqlite> <region-name>

Every limit gets a check ("ver"), what the app tells the driver about how sure it is:
  "doppio"    two tags of the object agree (maxheight and maxheight:physical, the value and its
              traffic_sign), or a speed camera of OpenStreetMap is on an official list;
  "ufficiale" a speed camera only an official open list has (Spain, DGT, CC BY 4.0);
  "osm"       one tag of OpenStreetMap, nothing against it;
  "dubbio"    not plausible for that road (a height under 2.2 m or a weight under 3 t on a
              motorway, trunk or primary road, a height over 6.5 m), or two tags disagree (the
              smaller value is kept, the safe one).
The official lists used for the speed cameras (fetched on GitHub, only for their country):
  France: «Liste des radars fixes en France», Ministère de l'Intérieur, data.gouv.fr (Licence
          Ouverte 2.0, about 3,300 radars, updated 30/12/2025): to confirm and to add;
  Spain: radares fijos y de tramo, DGT NAP (DATEX II, CC BY 4.0), to confirm and to add.
Italy publishes the list of the approved devices (velox.mit.gov.it) without their positions:
there the cameras stay "osm".

The sections under average speed control (Tutor in Italy, ETVM in France, trajectcontrole…) go in
their own table «sections» (start and end point in the direction of travel, the limit), from the
OpenStreetMap relations type=enforcement + enforcement=average_speed: the apps made before it do
not read that table.
"""
import json
import math
import re
import sqlite3
import sys
import time

CELL = 0.01          # grid step in degrees (~1.1 km)
SAMPLE_M = 150       # a long way is registered in every cell it crosses

DIM_KEYS = ("maxheight", "maxwidth", "maxlength")
WEIGHT_KEYS = ("maxweight", "maxaxleload", "maxweightrating")
SKIP_VALUES = {"default", "none", "no", "unsigned", "below_default", "no_indications", "unknown", "fixme"}
BAN_VALUES = {"no", "destination", "delivery", "private", "agricultural", "discouraged"}
MAIN_ROADS = {"motorway", "trunk", "primary", "motorway_link", "trunk_link"}
# a sign value in traffic_sign: "IT:Fig.48(3.5)", "DE:265[3.8]", "FR:B12(3,5)", "maxheight=3.8"
SIGN_VALUE = re.compile(r"[\[(]\s*(\d+(?:[.,]\d+)?)\s*(?:m|t)?\s*[\])]")
OFFICIAL = {
    # the dataset (its newest CSV is taken), and the CSV of 12/2025 when the dataset cannot be read
    "france": ("fr", "https://www.data.gouv.fr/api/1/datasets/6712583387af110196942793/",
               "https://www.data.gouv.fr/api/1/datasets/r/17f7cfd9-a5fe-4b6a-9f5d-3625feaa396e"),
    "spain": ("es", "https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml", None),
}
# an official camera and OSM's are the same within this distance (the French list gives the
# positions with 3 to 6 decimals: up to about 100 m off)
MATCH_M = {"fr": 150, "es": 80}
# the kinds of the French radars, as the driver is told about them
FR_TYPES = {"ETF": "autovelox", "ETD": "autovelox (per categoria di veicolo)", "ETT": "autovelox", "ETU": "autovelox urbano",
            "ETVM": "controllo della velocità media", "ETFR": "semaforo rosso", "ETPN": "passaggio a livello"}


def parse_length(raw):
    """metres, or None. Handles 3.8 / 3,8 / 3.8 m / 380 cm / 12'6\" / 12 ft."""
    if raw is None:
        return None
    s = raw.strip().lower().replace(",", ".")
    if s in SKIP_VALUES:
        return None
    m = re.match(r"^(\d+(?:\.\d+)?)\s*'\s*(\d+(?:\.\d+)?)?\s*\"?$", s)
    if m:
        feet = float(m.group(1))
        inches = float(m.group(2) or 0)
        return round(feet * 0.3048 + inches * 0.0254, 2)
    m = re.match(r"^(\d+(?:\.\d+)?)\s*(m|cm|ft|mt|metri)?$", s)
    if not m:
        return None
    v = float(m.group(1))
    unit = m.group(2) or "m"
    if unit == "cm":
        v /= 100.0
    elif unit == "ft":
        v *= 0.3048
    if v <= 0.5 or v > 30:
        return None
    return round(v, 2)


def parse_weight(raw):
    """tonnes, or None. Handles 7.5 / 7,5 t / 3500 kg / 7.5 st."""
    if raw is None:
        return None
    s = raw.strip().lower().replace(",", ".")
    if s in SKIP_VALUES:
        return None
    m = re.match(r"^(\d+(?:\.\d+)?)\s*(t|kg|st|lbs|ton|tonnes)?$", s)
    if not m:
        return None
    v = float(m.group(1))
    unit = m.group(2) or "t"
    if unit == "kg":
        v /= 1000.0
    elif unit == "st":
        v *= 0.907
    elif unit == "lbs":
        v *= 0.000453592
    if v <= 0.2 or v > 200:
        return None
    return round(v, 2)


def dist_m(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    dx = (b[0] - a[0]) * 111320 * math.cos(lat)
    dy = (b[1] - a[1]) * 110540
    return math.hypot(dx, dy)


def cells_for(coords):
    out = set()
    prev = None
    for c in coords:
        if prev is not None:
            d = dist_m(prev, c)
            steps = int(d // SAMPLE_M)
            for i in range(1, steps + 1):
                t = i / (steps + 1)
                p = (prev[0] + (c[0] - prev[0]) * t, prev[1] + (c[1] - prev[1]) * t)
                out.add(cell_id(p[1], p[0]))
        out.add(cell_id(c[1], c[0]))
        prev = c
    return out


def cell_id(lat, lon):
    return int(math.floor(lat / CELL)) * 100000 + int(math.floor(lon / CELL)) + 50000


def simplify(coords, max_pts=40):
    if len(coords) <= max_pts:
        return coords
    step = (len(coords) - 1) / (max_pts - 1)
    return [coords[round(i * step)] for i in range(max_pts)]


def geometry_coords(geom):
    t = geom["type"]
    if t == "Point":
        return [geom["coordinates"]]
    if t == "LineString":
        return geom["coordinates"]
    if t == "MultiLineString":
        return [p for line in geom["coordinates"] for p in line]
    if t == "Polygon":
        return geom["coordinates"][0]
    if t == "MultiPolygon":
        return geom["coordinates"][0][0]
    return []


def sign_values(tags):
    """The values written on the signs of the object (traffic_sign and its :forward/:backward)."""
    out = []
    for k, v in tags.items():
        if k.startswith("traffic_sign") and isinstance(v, str):
            out += [float(x.replace(",", ".")) for x in SIGN_VALUE.findall(v)]
    return out


def check(kind, value, tags, other=None):
    """The check of one limit (see the top of the file); [other] the value of a second tag."""
    hw = tags.get("highway") or ""
    if other is not None and abs(other - value) > 0.11:
        return "dubbio"
    if kind == "maxheight" and (value > 6.5 or (value < 2.2 and hw in MAIN_ROADS)):
        return "dubbio"
    if kind in ("maxweight", "maxaxleload") and value < 3 and hw in MAIN_ROADS:
        return "dubbio"
    if kind == "maxwidth" and value < 1.9 and hw in MAIN_ROADS:
        return "dubbio"
    if other is not None:
        return "doppio"
    if any(abs(s - value) < 0.06 for s in sign_values(tags)):
        return "doppio"
    return "osm"


def limits_of(tags):
    """(kind, value, raw, check) tuples found on one OSM object."""
    found = []
    for k in DIM_KEYS:
        a, b = parse_length(tags.get(k)), parse_length(tags.get(k + ":physical"))
        if a is None and b is None:
            continue
        v = min(x for x in (a, b) if x is not None)
        found.append((k, v, tags.get(k) or tags.get(k + ":physical"), check(k, v, tags, max(a, b) if a is not None and b is not None else None)))
    for k in WEIGHT_KEYS:
        raw = tags.get(k)
        v = parse_weight(raw)
        if v is not None:
            kind = "maxweight" if k == "maxweightrating" else k
            found.append((kind, v, raw, check(kind, v, tags)))
    hgv = tags.get("hgv")
    if hgv in BAN_VALUES:
        found.append(("hgv", 0.0, hgv, "osm"))
    if tags.get("hazmat") == "no" or tags.get("hazmat:water") == "no":
        found.append(("hazmat", 0.0, tags.get("hazmat") or tags.get("hazmat:water"), "osm"))
    cat = (tags.get("hazmat:adr_tunnel_cat") or tags.get("hazmat:tunnel_cat") or tags.get("tunnel:adr_category") or "").strip().upper()
    if not cat:
        for letter in "BCDE":
            if tags.get("hazmat:" + letter) == "no":
                cat = letter
    if cat[:1] in ("B", "C", "D", "E"):
        found.append(("adr_tunnel", float("BCDE".index(cat[:1]) + 2), cat[:1], "osm"))
    # fixed speed cameras (OpenStreetMap highway=speed_camera): value = the speed they check (0 if
    # not mapped), raw = the direction they face ("forward", "backward" or degrees) when mapped
    if tags.get("highway") == "speed_camera" or tags.get("enforcement") == "maxspeed":
        sp = re.match(r"\s*(\d+)", tags.get("maxspeed") or "")
        found.append(("speed_camera", float(sp.group(1)) if sp else 0.0, tags.get("direction") or tags.get("camera:direction"), "osm"))
    if tags.get("motorhome") == "no":
        found.append(("motorhome", 0.0, "no", "osm"))
    if tags.get("bus") == "no" or tags.get("psv") == "no":
        found.append(("bus", 0.0, "no", "osm"))
    return found


def official_cameras(region):
    """[(lat, lon, speed, what, add)] of the official open list of the country of [region]:
    add = the camera is added where OpenStreetMap has none (a list kept up to date)."""
    if region not in OFFICIAL:
        return []
    code, url, fallback = OFFICIAL[region]
    import urllib.request
    ua = {"User-Agent": "NavMaster data build (github.com/francescobuzle-a11y/navmaster)"}
    def get(u):
        return urllib.request.urlopen(urllib.request.Request(u, headers=ua), timeout=120).read()
    try:
        if code == "fr":
            # the newest CSV of the dataset (a new file is added every year)
            try:
                ds = json.loads(get(url))
                csvs = [r for r in ds.get("resources", []) if (r.get("format") or "").lower() == "csv"]
                csvs.sort(key=lambda r: r.get("last_modified") or "", reverse=True)
                url = csvs[0]["url"]
                print(f"official cameras fr: {csvs[0].get('title')} ({csvs[0].get('last_modified', '')[:10]})")
            except Exception as ex:
                print(f"official cameras fr: dataset not read ({ex}), the 12/2025 file")
                url = fallback
        raw = get(url)
    except Exception as ex:
        print(f"official cameras {code}: not downloaded ({ex})")
        return []
    out = []
    try:
        if code == "fr":
            import csv
            import io
            import unicodedata
            text = raw.decode("utf-8-sig", errors="replace")
            if text.count("\ufffd") > 20:
                text = raw.decode("latin-1")
            head = text.split("\n", 1)[0]
            dialect = "excel" if head.count(",") > head.count(";") else None
            if dialect is None:
                class dialect(csv.excel):
                    delimiter = ";"
            # the column names change from file to file ("Latitude", " Longitude", "VMA ", "latitude"…)
            def norm(k):
                k = unicodedata.normalize("NFKD", k or "").encode("ascii", "ignore").decode().strip().lower()
                return re.sub(r"[^a-z0-9]+", "_", k).strip("_")
            def num(v):
                try:
                    return float((v or "").strip().replace(",", ".").lstrip("+"))
                except ValueError:
                    return None
            for row in csv.DictReader(io.StringIO(text), dialect=dialect):
                r = {norm(k): v for k, v in row.items() if k is not None}
                lat = num(next((v for k, v in r.items() if k.startswith("latitude")), None))
                lon = num(next((v for k, v in r.items() if k.startswith("longitude")), None))
                if lat is None or lon is None or not (41 < lat < 52 and -6 < lon < 10):
                    continue
                sp = num(next((v for k, v in r.items() if k in ("vma", "vitesse_vehicules_legers_kmh")), None)) or 0.0
                kind = (next((v for k, v in r.items() if k in ("type_de_radar", "type")), None) or "").strip().upper()
                out.append((lat, lon, sp if 0 < sp <= 130 else 0.0, FR_TYPES.get(kind, "autovelox") + (f" ({kind})" if kind else ""), True))
        elif code == "es":
            import xml.etree.ElementTree as ET
            root = ET.fromstring(raw)
            local = lambda e: e.tag.rsplit("}", 1)[-1]
            def coords(pc):
                la = lo = None
                for c in pc:
                    if local(c) == "latitude":
                        la = float(c.text)
                    elif local(c) == "longitude":
                        lo = float(c.text)
                return (la, lo) if la is not None and lo is not None else None
            seen = set()
            for loc in root.iter():
                # the radars: the predefinedLocation elements with an id (inside, another one without)
                if local(loc) != "predefinedLocation" or loc.get("id") is None:
                    continue
                start = None
                points = []
                for el in loc.iter():
                    if local(el) == "from":
                        for pc in el.iter():
                            if local(pc) == "pointCoordinates":
                                start = start or coords(pc)
                    elif local(el) == "pointCoordinates":
                        p = coords(el)
                        if p:
                            points.append(p)
                # a section (from - to): the camera where it starts; a point: the camera itself
                p = start or (points[0] if points else None)
                if p is None or (round(p[0], 5), round(p[1], 5)) in seen:
                    continue
                seen.add((round(p[0], 5), round(p[1], 5)))
                out.append((p[0], p[1], 0.0, "radar DGT" + (" (tramo)" if start else ""), True))
    except Exception as ex:
        print(f"official cameras {code}: not readable ({ex})")
        return []
    print(f"official cameras {code}: {len(out)}")
    return [(la, lo, sp, what, add, MATCH_M.get(code, 80)) for la, lo, sp, what, add in out]


def conditional_of(tags):
    parts = [f"{k}={v}" for k, v in tags.items() if k.endswith(":conditional") and
             k.split(":")[0] in ("hgv", "maxweight", "maxheight", "maxlength", "motor_vehicle", "goods")]
    return "; ".join(parts) or None


def main():
    src, dst, region = sys.argv[1], sys.argv[2], sys.argv[3]
    db = sqlite3.connect(dst)
    db.executescript("""
        DROP TABLE IF EXISTS meta; DROP TABLE IF EXISTS limits; DROP TABLE IF EXISTS cells;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE limits(id INTEGER PRIMARY KEY, osm TEXT, kind TEXT, value REAL, raw TEXT,
                            cond TEXT, name TEXT, pts TEXT, ver TEXT);
        CREATE TABLE cells(cell INTEGER NOT NULL, lid INTEGER NOT NULL);
    """)
    n_obj = n_lim = 0
    kinds = {}
    checks = {}
    cameras = []   # (lat, lon, limit id) of OpenStreetMap's speed cameras, for the official lists
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            feat = json.loads(line)
            tags = feat.get("properties") or {}
            found = limits_of(tags)
            cond = conditional_of(tags)
            if cond and not found:
                found.append(("conditional", 0.0, cond, "osm"))
            if not found:
                continue
            coords = geometry_coords(feat.get("geometry") or {})
            if not coords:
                continue
            n_obj += 1
            pts = ";".join(f"{c[1]:.6f},{c[0]:.6f}" for c in simplify(coords))
            cells = cells_for(coords)
            osm = str(feat.get("id") or tags.get("@id") or "")
            name = tags.get("name") or tags.get("ref")
            for kind, value, raw, ver in found:
                cur = db.execute("INSERT INTO limits(osm, kind, value, raw, cond, name, pts, ver) VALUES (?,?,?,?,?,?,?,?)",
                                 (osm, kind, value, raw, cond, name, pts, ver))
                lid = cur.lastrowid
                db.executemany("INSERT INTO cells(cell, lid) VALUES (?,?)", [(c, lid) for c in cells])
                n_lim += 1
                kinds[kind] = kinds.get(kind, 0) + 1
                checks[ver] = checks.get(ver, 0) + 1
                if kind == "speed_camera" and len(coords) == 1:
                    cameras.append((coords[0][1], coords[0][0], lid))
    # the official lists of speed cameras: OSM's confirmed, the missing ones added (where the list is kept up to date)
    official = official_cameras(region)
    if official:
        grid = {}
        for lat, lon, lid in cameras:
            grid.setdefault(cell_id(lat, lon), []).append((lat, lon, lid))
        confirmed = set()
        added = 0
        for lat, lon, speed, what, add, match_m in official:
            near = None
            best = match_m
            c0 = cell_id(lat, lon)
            for dc in (-100001, -100000, -99999, -1, 0, 1, 99999, 100000, 100001):
                for la, lo, lid in grid.get(c0 + dc, []):
                    d = dist_m((lo, la), (lon, lat))
                    if d <= best:
                        near, best = lid, d
            if near is not None:
                confirmed.add(near)
            elif add:
                cur = db.execute("INSERT INTO limits(osm, kind, value, raw, cond, name, pts, ver) VALUES (?,?,?,?,?,?,?,?)",
                                 ("", "speed_camera", speed, None, None, what, f"{lat:.6f},{lon:.6f}", "ufficiale"))
                db.execute("INSERT INTO cells(cell, lid) VALUES (?,?)", (cell_id(lat, lon), cur.lastrowid))
                added += 1
        db.executemany("UPDATE limits SET ver = 'doppio' WHERE id = ?", [(i,) for i in confirmed])
        checks["camera_confirmed"] = len(confirmed)
        checks["camera_added"] = added
        n_lim += added
        kinds["speed_camera"] = kinds.get("speed_camera", 0) + added
    db.execute("CREATE INDEX cells_cell ON cells(cell)")
    meta = {"region": region, "built": time.strftime("%Y-%m-%d"), "cell": str(CELL),
            "objects": str(n_obj), "limits": str(n_lim), "kinds": json.dumps(kinds), "checks": json.dumps(checks),
            "version": "2"}
    db.executemany("INSERT INTO meta(key, value) VALUES (?,?)", meta.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(json.dumps(meta))


def signs(dst):
    """The direction signs go in the same file: from the region extract next to it (the workflow
    works in the folder of region.osm.pbf), with osmium. Without them the file is complete anyway."""
    import os
    import subprocess
    here = os.path.dirname(os.path.abspath(__file__))
    if not os.path.exists("region.osm.pbf"):
        print("signs: no region.osm.pbf here, skipped")
        return
    try:
        subprocess.run(["osmium", "tags-filter", "region.osm.pbf", "w/destination", "w/destination:ref", "w/destination:forward",
                        "w/destination:backward", "w/destination:ref:forward", "w/destination:ref:backward", "w/destination:street",
                        "w/destination:lanes", "w/destination:ref:lanes", "w/destination:lanes:forward",
                        "w/destination:lanes:backward", "w/destination:ref:lanes:forward", "w/destination:ref:lanes:backward",
                        "n/highway=motorway_junction", "-o", "signs.osm.pbf", "--overwrite"], check=True)
        subprocess.run(["osmium", "export", "signs.osm.pbf", "-f", "geojsonseq", "--add-unique-id=type_id",
                        "--format-option", "print_record_separator=false", "-o", "signs.geojsonseq", "--overwrite"], check=True)
        subprocess.run([sys.executable, os.path.join(here, "build_signs.py"), "signs.geojsonseq", dst], check=True)
    except Exception as ex:
        print(f"signs: skipped ({ex})")
    finally:
        for f in ("signs.osm.pbf", "signs.geojsonseq"):
            if os.path.exists(f):
                os.remove(f)


def opl_unescape(s):
    """OPL text: %hex% stands for one character."""
    return re.sub(r"%([0-9a-fA-F]+)%", lambda m: chr(int(m.group(1), 16)), s)


def read_sections(opl_path):
    """[(name, maxspeed, maxspeed_hgv, (lat, lon) start, (lat, lon) end, relation id)] of the
    relations type=enforcement + enforcement=average_speed of an OPL file: start = member "from",
    end = member "to" (a node, or the first node of a way)."""
    nodes, ways, rels = {}, {}, []
    with open(opl_path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split(" ")
            if not parts or not parts[0]:
                continue
            kind, oid = parts[0][0], parts[0][1:]
            fields = {p[0]: p[1:] for p in parts[1:] if p}
            if kind == "n" and "x" in fields and "y" in fields and fields["x"]:
                nodes[oid] = (float(fields["y"]), float(fields["x"]))
            elif kind == "w":
                ways[oid] = [r[1:] for r in fields.get("N", "").split(",") if r.startswith("n")]
            elif kind == "r":
                tags = {}
                for kv in fields.get("T", "").split(","):
                    if "=" in kv:
                        k, v = kv.split("=", 1)
                        tags[opl_unescape(k)] = opl_unescape(v)
                members = []
                for m in fields.get("M", "").split(","):
                    if "@" in m and len(m) > 1:
                        ref, role = m.split("@", 1)
                        members.append((ref[0], ref[1:], opl_unescape(role)))
                rels.append((oid, tags, members))
    def where(t, ref):
        if t == "n":
            return nodes.get(ref)
        if t == "w" and ways.get(ref):
            return nodes.get(ways[ref][0])
        return None
    out = []
    for oid, tags, members in rels:
        if tags.get("type") != "enforcement" or tags.get("enforcement") != "average_speed":
            continue
        a = next((where(t, ref) for t, ref, role in members if role == "from" and where(t, ref)), None)
        b = next((where(t, ref) for t, ref, role in members if role == "to" and where(t, ref)), None)
        if a is None or b is None or dist_m((a[1], a[0]), (b[1], b[0])) < 300:
            continue
        sp = re.match(r"\s*(\d+)", tags.get("maxspeed") or "")
        hgv = re.match(r"\s*(\d+)", tags.get("maxspeed:hgv") or "")
        out.append((tags.get("name") or tags.get("description"), float(sp.group(1)) if sp else 0.0,
                    float(hgv.group(1)) if hgv else None, a, b, oid))
    return out


def sections(dst):
    """The sections under average speed control (Tutor) go in the same file, table «sections»,
    from the region extract next to it, with osmium (as the signs)."""
    import os
    import subprocess
    if not os.path.exists("region.osm.pbf"):
        print("sections: no region.osm.pbf here, skipped")
        return
    try:
        subprocess.run(["osmium", "tags-filter", "region.osm.pbf", "r/enforcement=average_speed",
                        "-o", "sections.osm.pbf", "--overwrite"], check=True)
        subprocess.run(["osmium", "cat", "sections.osm.pbf", "-f", "opl", "-o", "sections.opl", "--overwrite"], check=True)
        found = read_sections("sections.opl")
        db = sqlite3.connect(dst)
        db.executescript("""
            DROP TABLE IF EXISTS sections; DROP TABLE IF EXISTS section_cells;
            CREATE TABLE sections(id INTEGER PRIMARY KEY, osm TEXT, kind TEXT, value REAL, hgv REAL, name TEXT,
                                  pts TEXT, ver TEXT);
            CREATE TABLE section_cells(cell INTEGER NOT NULL, sid INTEGER NOT NULL);
        """)
        for name, sp, hgv, a, b, oid in found:
            cur = db.execute("INSERT INTO sections(osm, kind, value, hgv, name, pts, ver) VALUES (?,?,?,?,?,?,?)",
                             ("r" + oid, "average_speed", sp, hgv, name, f"{a[0]:.6f},{a[1]:.6f};{b[0]:.6f},{b[1]:.6f}", "osm"))
            for c in {cell_id(*a), cell_id(*b)}:
                db.execute("INSERT INTO section_cells(cell, sid) VALUES (?,?)", (c, cur.lastrowid))
        db.execute("CREATE INDEX section_cells_cell ON section_cells(cell)")
        db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('sections', ?)", (str(len(found)),))
        db.commit()
        db.close()
        print(f"sections: {len(found)} under average speed control")
    except Exception as ex:
        print(f"sections: skipped ({ex})")
    finally:
        for f in ("sections.osm.pbf", "sections.opl"):
            if os.path.exists(f):
                os.remove(f)


def roads(dst):
    """The roads for the guidance online without the map (build_roads.py): from the region extract
    next to it, with osmium (as the signs). Without them the file is complete anyway."""
    import os
    import subprocess
    here = os.path.dirname(os.path.abspath(__file__))
    if not os.path.exists("region.osm.pbf"):
        print("roads: no region.osm.pbf here, skipped")
        return
    try:
        subprocess.run(["osmium", "tags-filter", "region.osm.pbf",
                        "w/highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary,secondary_link,tertiary,tertiary_link",
                        "w/maxspeed", "w/maxspeed:forward", "w/maxspeed:backward", "w/turn:lanes", "w/turn:lanes:forward",
                        "w/turn:lanes:backward", "-o", "roads.osm.pbf", "--overwrite"], check=True)
        subprocess.run(["osmium", "export", "roads.osm.pbf", "-f", "geojsonseq", "--add-unique-id=type_id",
                        "--format-option", "print_record_separator=false", "-o", "roads.geojsonseq", "--overwrite"], check=True)
        subprocess.run([sys.executable, os.path.join(here, "build_roads.py"), "roads.geojsonseq", dst], check=True)
    except Exception as ex:
        print(f"roads: skipped ({ex})")
    finally:
        for f in ("roads.osm.pbf", "roads.geojsonseq"):
            if os.path.exists(f):
                os.remove(f)


if __name__ == "__main__":
    main()
    signs(sys.argv[2])
    sections(sys.argv[2])
    roads(sys.argv[2])
