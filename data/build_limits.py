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
  France: radars automatiques, data.gouv.fr (Licence Ouverte 2.0), only to confirm OSM's;
  Spain: radares fijos y de tramo, DGT NAP (DATEX II, CC BY 4.0), to confirm and to add.
Italy publishes the list of the approved devices (velox.mit.gov.it) without their positions:
there the cameras stay "osm".
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
    "france": ("fr", "https://www.data.gouv.fr/api/1/datasets/r/8a22b5a8-4b65-41be-891a-7c0aead4ba51"),
    "spain": ("es", "https://nap.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml"),
}
MATCH_M = 80         # an official camera and OSM's are the same within this distance


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
    code, url = OFFICIAL[region]
    import urllib.request
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "NavMaster data build (github.com/francescobuzle-a11y/navmaster)"})
        raw = urllib.request.urlopen(req, timeout=120).read()
    except Exception as ex:
        print(f"official cameras {code}: not downloaded ({ex})")
        return []
    out = []
    try:
        if code == "fr":
            import csv
            import io
            text = raw.decode("utf-8-sig", errors="replace")
            dialect = csv.Sniffer().sniff(text[:4000], delimiters=",;")
            for row in csv.DictReader(io.StringIO(text), dialect=dialect):
                try:
                    lat, lon = float(row["latitude"]), float(row["longitude"])
                except (KeyError, ValueError):
                    continue
                sp = row.get("vitesse_vehicules_legers_kmh") or ""
                out.append((lat, lon, float(sp) if sp.replace(".", "").isdigit() else 0.0, "radar " + (row.get("type") or ""), False))
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
    return out


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
        for lat, lon, speed, what, add in official:
            near = None
            c0 = cell_id(lat, lon)
            for dc in (-100001, -100000, -99999, -1, 0, 1, 99999, 100000, 100001):
                for la, lo, lid in grid.get(c0 + dc, []):
                    if dist_m((lo, la), (lon, lat)) <= MATCH_M:
                        near = lid
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


if __name__ == "__main__":
    main()
    signs(sys.argv[2])
