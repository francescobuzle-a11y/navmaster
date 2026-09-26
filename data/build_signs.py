#!/usr/bin/env python3
"""Adds the direction signs of the region to limiti.sqlite: what the signs at every junction say
("A14 Bologna", "SS16 Rimini Centro", "Uscita 7 Riccione"), read from the OpenStreetMap
destination tags of the roads that leave the junction. The tablet shows them in the junction view,
for the road it takes and for the road it does not take.

Input: GeoJSON sequence of the ways with destination tags and of the motorway_junction nodes,
produced by  osmium export -f geojsonseq --add-unique-id=type_id <signs.osm.pbf>
Usage: build_signs.py <in.geojsonseq> <limiti.sqlite>
One row per way and direction: where the sign stands (the start of the road it points to), the
bearing of that road, destination, road numbers, colour, exit number and name.
"""
import json
import math
import sqlite3
import sys

CELL = 0.01


def cell_id(lat, lon):
    return int(math.floor(lat / CELL)) * 100000 + int(math.floor(lon / CELL)) + 50000


def dist_m(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320 * math.cos(lat), (b[1] - a[1]) * 110540)


def bearing(a, b):
    lat1, lat2 = math.radians(a[1]), math.radians(b[1])
    dl = math.radians(b[0] - a[0])
    y = math.sin(dl) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def start_bearing(coords):
    """Bearing of the first ~25 m of the line (a single short segment is too noisy)."""
    a = coords[0]
    run = 0.0
    for i in range(1, len(coords)):
        run += dist_m(coords[i - 1], coords[i])
        if run >= 25 or i == len(coords) - 1:
            return bearing(a, coords[i])
    return 0.0


def clean(v):
    if not v:
        return None
    parts = [p.strip() for p in v.replace(";", "|").split("|") if p.strip() and p.strip().lower() not in ("none", "no")]
    # keep the order, drop repetitions
    seen, out = set(), []
    for p in parts:
        if p.lower() not in seen:
            seen.add(p.lower())
            out.append(p)
    return ";".join(out[:5]) or None


def end_bearing(coords):
    """Bearing of the last ~25 m of the line: the way the lanes go into the junction."""
    return (start_bearing(list(reversed(coords))) + 180.0) % 360


def lanes_value(v):
    """destination:lanes "Bologna|Bologna|Milano;Torino" -> the same, cleaned lane by lane (None if empty)."""
    if not v:
        return None
    lanes = []
    for lane in v.split("|"):
        vals = [p.strip() for p in lane.split(";") if p.strip() and p.strip().lower() not in ("none", "no")]
        lanes.append(";".join(vals[:4]))
    return "|".join(lanes) if len(lanes) >= 2 and any(lanes) else None


def main():
    src, dst = sys.argv[1], sys.argv[2]
    db = sqlite3.connect(dst)
    db.executescript("""
        DROP TABLE IF EXISTS signs; DROP TABLE IF EXISTS sign_cells;
        CREATE TABLE signs(id INTEGER PRIMARY KEY, osm TEXT, lat REAL, lon REAL, brg REAL, hw TEXT,
                           dest TEXT, dref TEXT, colour TEXT, jref TEXT, jname TEXT);
        CREATE TABLE sign_cells(cell INTEGER NOT NULL, sid INTEGER NOT NULL);
        DROP TABLE IF EXISTS lane_signs; DROP TABLE IF EXISTS lane_sign_cells;
        CREATE TABLE lane_signs(id INTEGER PRIMARY KEY, osm TEXT, lat REAL, lon REAL, brg REAL, hw TEXT,
                                dest TEXT, dref TEXT, colour TEXT);
        CREATE TABLE lane_sign_cells(cell INTEGER NOT NULL, sid INTEGER NOT NULL);
    """)
    junctions = {}
    jcells = {}
    ways = []
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            feat = json.loads(line)
            tags = feat.get("properties") or {}
            geom = feat.get("geometry") or {}
            if geom.get("type") == "Point":
                if tags.get("highway") == "motorway_junction":
                    lon, lat = geom["coordinates"]
                    v = (tags.get("ref") or tags.get("junction:ref"), tags.get("name"))
                    junctions[(round(lat, 5), round(lon, 5))] = v
                    jcells.setdefault(cell_id(lat, lon), []).append((lat, lon, v))
                continue
            if geom.get("type") != "LineString":
                continue
            ways.append((str(feat.get("id") or ""), tags, geom["coordinates"]))
    # the signs over the lanes (destination:lanes): stored at the END of the road that carries them,
    # where its lanes split, with the bearing the lanes have there
    nl = 0
    for osm, tags, coords in ways:
        if len(coords) < 2:
            continue
        oneway = tags.get("oneway") in ("yes", "1", "true") or tags.get("highway") in ("motorway", "motorway_link")
        for direction in ("forward", "backward"):
            if direction == "backward" and oneway:
                continue
            def tl(k):
                return tags.get(f"{k}:lanes:{direction}") or (tags.get(f"{k}:lanes") if direction == "forward" else None)
            dest = lanes_value(tl("destination"))
            dref = lanes_value(tl("destination:ref"))
            if not dest and not dref:
                continue
            colour = lanes_value(tl("destination:colour"))
            pts = coords if direction == "forward" else list(reversed(coords))
            lon, lat = pts[-1]
            cur = db.execute("INSERT INTO lane_signs(osm, lat, lon, brg, hw, dest, dref, colour) VALUES (?,?,?,?,?,?,?,?)",
                             (osm, round(lat, 6), round(lon, 6), round(end_bearing(pts), 1), tags.get("highway"), dest, dref, colour))
            db.execute("INSERT INTO lane_sign_cells(cell, sid) VALUES (?,?)", (cell_id(lat, lon), cur.lastrowid))
            nl += 1
    n = 0
    for osm, tags, coords in ways:
        if len(coords) < 2:
            continue
        hw = tags.get("highway")
        for direction in ("forward", "backward"):
            def t(k):
                return tags.get(f"{k}:{direction}") or (tags.get(k) if direction == "forward" else None)
            dest = clean(t("destination"))
            dref = clean(t("destination:ref"))
            if not dest and not dref:
                continue
            pts = coords if direction == "forward" else list(reversed(coords))
            if direction == "backward" and tags.get("oneway") in ("yes", "1", "true"):
                continue
            street = clean(t("destination:street"))
            if street and not dest:
                dest = street
            colour = clean(t("destination:colour"))
            lon, lat = pts[0]
            is_link = (hw or "").endswith("_link")
            j = junctions.get((round(lat, 5), round(lon, 5))) if is_link else None
            if j is None and is_link:
                # the exit node is often a few metres before the start of the ramp
                c0 = cell_id(lat, lon)
                near = [x for dy in (-1, 0, 1) for dx in (-1, 0, 1) for x in jcells.get(c0 + dy * 100000 + dx, [])]
                best = min(near, key=lambda x: dist_m((lon, lat), (x[1], x[0])), default=None)
                if best is not None and dist_m((lon, lat), (best[1], best[0])) < 60:
                    j = best[2]
            cur = db.execute("INSERT INTO signs(osm, lat, lon, brg, hw, dest, dref, colour, jref, jname) VALUES (?,?,?,?,?,?,?,?,?,?)",
                             (osm, round(lat, 6), round(lon, 6), round(start_bearing(pts), 1), hw, dest, dref, colour,
                              j[0] if j else None, j[1] if j else None))
            db.execute("INSERT INTO sign_cells(cell, sid) VALUES (?,?)", (cell_id(lat, lon), cur.lastrowid))
            n += 1
    db.execute("CREATE INDEX sign_cells_cell ON sign_cells(cell)")
    db.execute("CREATE INDEX lane_sign_cells_cell ON lane_sign_cells(cell)")
    db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('signs', ?)", (str(n),))
    db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('lane_signs', ?)", (str(nl),))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(json.dumps({"signs": n, "lane_signs": nl, "exits": len(junctions)}))


if __name__ == "__main__":
    try:
        main()
    except Exception as ex:  # the limits stay as they are
        print(f"signs: skipped ({ex})")
