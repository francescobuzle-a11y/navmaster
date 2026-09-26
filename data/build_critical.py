#!/usr/bin/env python3
"""Builds criticita.sqlite: the physical difficulties of the road network for a long or wide
vehicle, found in the OSM geometry and tags. The tablet checks the route against it before the
trip (and shows each point with satellite view and street photos) and asks the driver ahead of a
tight exit ramp during guidance.

Kinds
  narrow  carriageway narrower than a lorry needs (width / est_width / narrow=yes / one lane
          on a two-way road); value = width in metres (0 when unknown)
  rough   unpaved or badly maintained surface; value = 1 (poor) .. 3 (very bad)
  steep   signed or mapped gradient of 8 % or more; value = percent. Where OSM has no incline,
          the terrain model gives it (dem_steep.py, 9 % or more over 200 m; info "dem": true)
  curve   tight curve: exit ramps under 40 m radius, other roads under 22 m (hairpins);
          value = radius in metres
  ford    the road crosses water
Each row keeps what the app needs to judge it for the vehicle of the trip (lanes, width, one-way,
road class) in "info".

Usage: build_critical.py <roads.geojsonseq> <out.sqlite> <region>
"""
import json
import math
import re
import sqlite3
import sys
import time

from nmcommon import cells_for, dist_m, min_radius, read_geojsonseq, simplify

CLASSES = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
           "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street",
           "service", "track", "road"}
LINKS = {"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"}
BAD_SURFACE = {"unpaved": 1, "compacted": 1, "fine_gravel": 1, "gravel": 2, "pebblestone": 2, "ground": 2,
               "dirt": 2, "earth": 2, "grass": 3, "sand": 3, "mud": 3, "rock": 3, "stepping_stones": 3,
               "woodchips": 3, "grass_paver": 2, "unhewn_cobblestone": 2}
BAD_SMOOTH = {"bad": 1, "very_bad": 2, "horrible": 3, "very_horrible": 3, "impassable": 3}
TRACKTYPE = {"grade2": 1, "grade3": 2, "grade4": 3, "grade5": 3}


def num(v):
    if v is None:
        return None
    m = re.match(r"^\s*(-?\d+(?:[.,]\d+)?)", str(v))
    return float(m.group(1).replace(",", ".")) if m else None


def width_of(t):
    for k in ("width:carriageway", "width", "est_width"):
        v = t.get(k)
        if v is None:
            continue
        s = str(v).lower().replace(",", ".").strip()
        m = re.match(r"^(\d+(?:\.\d+)?)\s*(m|cm)?$", s)
        if m:
            w = float(m.group(1)) / (100 if m.group(2) == "cm" else 1)
            if 1.5 <= w <= 40:
                return w
    return None


def oneway(t):
    return t.get("oneway") in ("yes", "1", "-1") or t.get("highway") in ("motorway", "motorway_link") \
        or t.get("junction") == "roundabout"


def turning(coords):
    """Sum of absolute direction changes along the raw vertices (degrees)."""
    total = 0.0
    prev = None
    for i in range(1, len(coords)):
        a, b = coords[i - 1], coords[i]
        k = math.cos(math.radians(b[1]))
        h = math.degrees(math.atan2((b[1] - a[1]) * 110540, (b[0] - a[0]) * 111320 * k))
        if prev is not None:
            d = abs((h - prev + 180) % 360 - 180)
            total += d
        prev = h
    return total


def clip_around(coords, at, radius_m=70.0):
    """The part of a way within radius_m (along the way) of a point: a tight curve only needs the
    curve itself to be matched with the route, not the whole road."""
    i0 = min(range(len(coords)), key=lambda i: dist_m(coords[i], at))
    lo, d = i0, 0.0
    while lo > 0 and d < radius_m:
        d += dist_m(coords[lo - 1], coords[lo])
        lo -= 1
    hi, d = i0, 0.0
    while hi < len(coords) - 1 and d < radius_m:
        d += dist_m(coords[hi], coords[hi + 1])
        hi += 1
    return coords[lo:hi + 1]


def pts_text(coords):
    # 5 decimals = 1.1 m: plenty to match a route, and a third smaller than 6
    return ";".join(f"{c[1]:.5f},{c[0]:.5f}" for c in coords)


def main():
    src, dst, region = sys.argv[1], sys.argv[2], sys.argv[3]
    db = sqlite3.connect(dst)
    db.executescript("""
        DROP TABLE IF EXISTS meta; DROP TABLE IF EXISTS crit; DROP TABLE IF EXISTS crit_cells;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE crit(id INTEGER PRIMARY KEY, osm TEXT, kind TEXT, value REAL, info TEXT,
                          name TEXT, lat REAL, lon REAL, pts TEXT);
        CREATE TABLE crit_cells(cell INTEGER NOT NULL, cid INTEGER NOT NULL);
    """)
    started = time.time()
    counts = {}
    rows, cells = [], []
    cid = 0

    def add(f, kind, value, t, coords, at=None):
        nonlocal cid
        cid += 1
        info = {"hw": t.get("highway"), "lanes": num(t.get("lanes")), "w": width_of(t), "ow": oneway(t)}
        for k in ("surface", "smoothness", "tracktype", "incline", "narrow", "ref", "hgv", "maxspeed", "junction"):
            if t.get(k):
                info[k] = t[k]
        pts = simplify(clip_around(coords, at) if kind == "curve" and at is not None else coords, 40)
        p = at or coords[len(coords) // 2]
        rows.append((cid, f.get("id"), kind, value, json.dumps(info, separators=(",", ":")), t.get("name") or t.get("ref"),
                     round(p[1], 6), round(p[0], 6), pts_text(pts)))
        for c in cells_for(pts):
            cells.append((c, cid))
        counts[kind] = counts.get(kind, 0) + 1

    # roads for the terrain slopes, grouped by 1° tile so each terrain tile is read once
    import os
    try:
        import numpy  # noqa: F401
    except ImportError:  # the runner's Python may not have it: install it for the terrain slopes
        import subprocess
        for extra in (["--break-system-packages"], []):
            if subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", *extra, "numpy"]).returncode == 0:
                break
    import tempfile
    buckets_dir = tempfile.mkdtemp(prefix="dem_", dir=os.path.dirname(os.path.abspath(dst)) or ".")
    buckets = {}

    def bucket(f, t, coords):
        try:
            from dem_steep import eligible, tile_name
        except Exception:
            return
        if not eligible(t) or len(coords) < 2:
            return
        name = tile_name(coords[0][1], coords[0][0])[1]
        fh = buckets.get(name)
        if fh is None:
            if len(buckets) > 900:
                return
            fh = buckets[name] = open(os.path.join(buckets_dir, name), "w", encoding="utf-8")
        fh.write(json.dumps([f.get("id"), {k: t[k] for k in ("highway", "name", "ref", "lanes", "oneway") if k in t}, coords],
                            separators=(",", ":")) + "\n")

    for f in read_geojsonseq(src):
        t = f.get("properties") or {}
        hw = t.get("highway")
        if hw not in CLASSES:
            continue
        if hw == "service" and t.get("service") in ("parking_aisle", "driveway", "drive-through", "emergency_access"):
            continue
        g = f.get("geometry") or {}
        if g.get("type") != "LineString":
            continue
        coords = g["coordinates"]
        if len(coords) < 2:
            continue

        w = width_of(t)
        two_way = not oneway(t)
        lanes = num(t.get("lanes"))
        if w is not None and ((two_way and w < 5.5) or (not two_way and w < 3.5)):
            add(f, "narrow", w, t, coords)
        elif t.get("narrow") == "yes":
            add(f, "narrow", 0.0, t, coords)
        elif two_way and lanes == 1 and hw in ("primary", "secondary", "tertiary", "unclassified", "residential"):
            add(f, "narrow", 0.0, t, coords)

        rough = max(BAD_SURFACE.get(t.get("surface"), 0), BAD_SMOOTH.get(t.get("smoothness"), 0),
                    TRACKTYPE.get(t.get("tracktype"), 0))
        # tracks are left out: the lorry and camper profiles already avoid unpaved roads, and they
        # would be most of the file
        if rough and hw != "track":
            add(f, "rough", float(rough), t, coords)

        inc = t.get("incline")
        if inc and inc not in ("up", "down", "yes", "no"):
            v = num(inc)
            if v is not None and "°" in inc:
                v = math.tan(math.radians(v)) * 100
            if v is not None and 8 <= abs(v) <= 40:
                add(f, "steep", abs(v), t, coords)

        bucket(f, t, coords)

        if t.get("ford") == "yes":
            add(f, "ford", 0.0, t, coords)

        # roundabouts are tight by design and lorries use the apron: only the really small ones count
        roundabout = t.get("junction") in ("roundabout", "circular")
        if len(coords) >= 3 and hw not in ("track", "service") and turning(coords) >= 70:
            limit = 9.0 if roundabout else 40.0 if hw in LINKS else 22.0
            r, at = min_radius(coords)
            if r < limit and at is not None:
                add(f, "curve", round(r, 1), t, coords, at)

        if len(rows) >= 50000:
            db.executemany("INSERT INTO crit VALUES (?,?,?,?,?,?,?,?,?)", rows)
            db.executemany("INSERT INTO crit_cells VALUES (?,?)", cells)
            rows, cells = [], []

    # terrain slopes (after the OSM ones: a way with a mapped incline is never measured again)
    for fh in buckets.values():
        fh.close()
    dem_started = time.time()
    try:
        from dem_steep import Dem, max_grade
        dem = Dem(os.path.join(buckets_dir, "tiles"))
        n_dem = 0
        for name in sorted(buckets):
            with open(os.path.join(buckets_dir, name), encoding="utf-8") as fh:
                for line in fh:
                    osm_id, t, coords = json.loads(line)
                    r = max_grade(dem, coords)
                    if r is None:
                        continue
                    pct, at = r
                    add({"id": osm_id}, "steep", pct, dict(t, incline=f"{pct}%"), coords, at)
                    json_info = json.loads(rows[-1][4])
                    json_info["dem"] = True
                    rows[-1] = rows[-1][:4] + (json.dumps(json_info, separators=(",", ":")),) + rows[-1][5:]
                    n_dem += 1
            os.remove(os.path.join(buckets_dir, name))
            if len(rows) >= 50000:
                db.executemany("INSERT INTO crit VALUES (?,?,?,?,?,?,?,?,?)", rows)
                db.executemany("INSERT INTO crit_cells VALUES (?,?)", cells)
                rows, cells = [], []
        print(f"criticita: {n_dem} terrain slopes from {len(buckets)} tiles "
              f"({dem.downloaded} downloaded, {len(dem.missing)} missing) in {time.time() - dem_started:.0f}s")
    except Exception as ex:
        print(f"criticita: terrain slopes skipped ({ex})")
    finally:
        import shutil
        shutil.rmtree(buckets_dir, ignore_errors=True)

    db.executemany("INSERT INTO crit VALUES (?,?,?,?,?,?,?,?,?)", rows)
    db.executemany("INSERT INTO crit_cells VALUES (?,?)", cells)
    db.execute("CREATE INDEX crit_cells_cell ON crit_cells(cell)")
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("region", region), ("built", time.strftime("%Y-%m-%d")), ("format", "1"),
        ("counts", json.dumps(counts)),
        ("source", "OpenStreetMap contributors, ODbL; terrain: Terrain Tiles on AWS (SRTM, NASA)"),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"criticita: {cid} in {time.time() - started:.0f}s {counts}")


if __name__ == "__main__":
    main()
