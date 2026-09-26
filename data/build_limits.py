#!/usr/bin/env python3
"""Builds limiti.sqlite: every height / weight / width / length / axle-load limit and every
HGV ban from an OSM extract, indexed on a 0.01 degree grid, so the tablet can warn about
low bridges and banned roads ahead on the route without any connection.

Input: GeoJSON sequence produced by
    osmium export -f geojsonseq --add-unique-id=type_id <limits.osm.pbf>
Usage: build_limits.py <in.geojsonseq> <out.sqlite> <region-name>
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


def limits_of(tags):
    """(kind, value, raw) tuples found on one OSM object."""
    found = []
    for k in DIM_KEYS:
        raw = tags.get(k) or tags.get(k + ":physical")
        v = parse_length(raw)
        if v is not None:
            found.append((k, v, raw))
    for k in WEIGHT_KEYS:
        raw = tags.get(k)
        v = parse_weight(raw)
        if v is not None:
            found.append(("maxweight" if k == "maxweightrating" else k, v, raw))
    hgv = tags.get("hgv")
    if hgv in BAN_VALUES:
        found.append(("hgv", 0.0, hgv))
    if tags.get("hazmat") == "no" or tags.get("hazmat:water") == "no":
        found.append(("hazmat", 0.0, tags.get("hazmat") or tags.get("hazmat:water")))
    cat = (tags.get("hazmat:adr_tunnel_cat") or tags.get("hazmat:tunnel_cat") or tags.get("tunnel:adr_category") or "").strip().upper()
    if not cat:
        for letter in "BCDE":
            if tags.get("hazmat:" + letter) == "no":
                cat = letter
    if cat[:1] in ("B", "C", "D", "E"):
        found.append(("adr_tunnel", float("BCDE".index(cat[:1]) + 2), cat[:1]))
    # fixed speed cameras (OpenStreetMap highway=speed_camera): value = the speed they check (0 if
    # not mapped), raw = the direction they face ("forward", "backward" or degrees) when mapped
    if tags.get("highway") == "speed_camera" or tags.get("enforcement") == "maxspeed":
        sp = re.match(r"\s*(\d+)", tags.get("maxspeed") or "")
        found.append(("speed_camera", float(sp.group(1)) if sp else 0.0, tags.get("direction") or tags.get("camera:direction")))
    if tags.get("motorhome") == "no":
        found.append(("motorhome", 0.0, "no"))
    if tags.get("bus") == "no" or tags.get("psv") == "no":
        found.append(("bus", 0.0, "no"))
    return found


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
                            cond TEXT, name TEXT, pts TEXT);
        CREATE TABLE cells(cell INTEGER NOT NULL, lid INTEGER NOT NULL);
    """)
    n_obj = n_lim = 0
    kinds = {}
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
                found.append(("conditional", 0.0, cond))
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
            for kind, value, raw in found:
                cur = db.execute("INSERT INTO limits(osm, kind, value, raw, cond, name, pts) VALUES (?,?,?,?,?,?,?)",
                                 (osm, kind, value, raw, cond, name, pts))
                lid = cur.lastrowid
                db.executemany("INSERT INTO cells(cell, lid) VALUES (?,?)", [(c, lid) for c in cells])
                n_lim += 1
                kinds[kind] = kinds.get(kind, 0) + 1
    db.execute("CREATE INDEX cells_cell ON cells(cell)")
    meta = {"region": region, "built": time.strftime("%Y-%m-%d"), "cell": str(CELL),
            "objects": str(n_obj), "limits": str(n_lim), "kinds": json.dumps(kinds), "version": "1"}
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


def debug_area(region):
    """Test package only: every road around the points under investigation, with all its tags, in
    the build log (the truck avoids the Rimini Nord entry: which road stops it?)."""
    import os
    import subprocess
    if region != "test" or not os.path.exists("region.osm.pbf"):
        return
    try:
        subprocess.run(["osmium", "extract", "-b", "12.455,44.078,12.485,44.097", "region.osm.pbf", "-o", "dbg.osm.pbf", "--overwrite"],
                       check=True)
        out = subprocess.run(["osmium", "export", "dbg.osm.pbf", "-f", "geojsonseq", "--add-unique-id=type_id",
                              "--format-option", "print_record_separator=false"], check=True, capture_output=True, text=True).stdout
        for line in out.splitlines():
            f = json.loads(line)
            t = f.get("properties") or {}
            if "highway" in t or "barrier" in t or t.get("type") == "restriction":
                g = f.get("geometry") or {}
                c = g.get("coordinates")
                first = c[0] if g.get("type") == "LineString" else c
                print("DBG", f.get("id"), g.get("type"), first, json.dumps(t, ensure_ascii=False))
        rel = subprocess.run(["osmium", "tags-filter", "dbg.osm.pbf", "r/type=restriction", "-f", "opl", "-o", "-"],
                             capture_output=True, text=True).stdout
        for line in rel.splitlines():
            print("DBGREL", line[:400])
    except Exception as ex:
        print(f"debug: {ex}")


if __name__ == "__main__":
    main()
    signs(sys.argv[2])
    debug_area(sys.argv[3])
