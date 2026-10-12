#!/usr/bin/env python3
"""Adds the roads of the region to limiti.sqlite, for the guidance online without the country's
map and graph on the tablet: the route of openrouteservice is matched on these roads, which give
what the downloaded graph gives offline (speed limits, lanes and their arrows, road class, way id
for the limits, roundabouts, tunnels, tolls, names).

Kept: every motorway, trunk, primary, secondary and tertiary road and their links; the smaller
roads only when they carry a speed limit or lanes. The line is simplified (about 4 m) and written
as an encoded polyline (5 decimals), so the whole table stays small (a few tens of MB for a large
country: the light package of the online guidance, limiti.sqlite + valichi.json).

Input: GeoJSON sequence produced by
    osmium export -f geojsonseq --add-unique-id=type_id <roads.osm.pbf>
Usage: build_roads.py <in.geojsonseq> <limiti.sqlite>
"""
import json
import math
import re
import sqlite3
import sys

CELL = 0.01
SAMPLE_M = 150
MAIN = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
        "tertiary", "tertiary_link"}
SMALL = {"unclassified", "residential", "living_street", "service", "road"}
# the implicit limits ("IT:urban"): km/h; 0 = no limit (German motorways)
IMPLICIT = {
    "urban": 50, "rural": 90, "motorway": 130, "trunk": 110, "living_street": 20, "walk": 7, "zone30": 30, "zone20": 20,
    "school": 30, "bicycle_road": 30, "nsl_single": 96, "nsl_dual": 112, "nsl_restricted": 48,
}
IMPLICIT_COUNTRY = {
    "DE:rural": 100, "DE:motorway": 0, "AT:rural": 100, "AT:motorway": 130, "CH:rural": 80, "CH:trunk": 100,
    "CH:motorway": 120, "FR:rural": 80, "FR:motorway": 130, "FR:trunk": 110, "ES:rural": 90, "ES:motorway": 120,
    "IT:rural": 90, "IT:motorway": 130, "IT:trunk": 110, "NL:rural": 80, "NL:motorway": 100, "BE:rural": 70,
    "BE-VLG:rural": 70, "BE-WAL:rural": 90, "BE:motorway": 120, "PL:rural": 90, "PL:motorway": 140, "PL:expressway": 120,
    "CZ:rural": 90, "CZ:motorway": 130, "SK:rural": 90, "SK:motorway": 130, "HU:rural": 90, "HU:motorway": 130,
    "SI:rural": 90, "SI:motorway": 130, "HR:rural": 90, "HR:motorway": 130, "RO:rural": 90, "RO:motorway": 130,
    "PT:rural": 90, "PT:motorway": 120, "DK:rural": 80, "DK:motorway": 130, "SE:rural": 70, "SE:motorway": 110,
    "NO:rural": 80, "NO:motorway": 110, "FI:rural": 80, "FI:motorway": 120, "GB:nsl_single": 96, "GB:nsl_dual": 112,
    "GB:motorway": 112, "IE:rural": 80, "IE:motorway": 120, "GR:rural": 90, "GR:motorway": 130, "BG:rural": 90,
    "BG:motorway": 140, "LU:rural": 90, "LU:motorway": 130,
}
NUM = re.compile(r"^\s*(\d+(?:[.,]\d+)?)\s*(mph|km/h|kmh|knots)?\s*$")


def cell_id(lat, lon):
    return int(math.floor(lat / CELL)) * 100000 + int(math.floor(lon / CELL)) + 50000


def dist_m(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320 * math.cos(lat), (b[1] - a[1]) * 110540)


def cells_for(coords):
    out = set()
    prev = None
    for c in coords:
        if prev is not None:
            steps = int(dist_m(prev, c) // SAMPLE_M)
            for i in range(1, steps + 1):
                t = i / (steps + 1)
                out.add(cell_id(prev[1] + (c[1] - prev[1]) * t, prev[0] + (c[0] - prev[0]) * t))
        out.add(cell_id(c[1], c[0]))
        prev = c
    return out


def seg_dist(p, a, b):
    """Distance in metres of p from segment a-b (lon, lat)."""
    k = math.cos(math.radians(p[1]))
    ax, ay = (a[0] - p[0]) * 111320 * k, (a[1] - p[1]) * 110540
    bx, by = (b[0] - p[0]) * 111320 * k, (b[1] - p[1]) * 110540
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    t = 0 if L == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / L))
    return math.hypot(ax + t * dx, ay + t * dy)


def simplify(coords, tol=4.0):
    """Douglas-Peucker, iterative."""
    if len(coords) <= 2:
        return coords
    keep = [False] * len(coords)
    keep[0] = keep[-1] = True
    stack = [(0, len(coords) - 1)]
    while stack:
        i, j = stack.pop()
        best, at = 0.0, -1
        for k in range(i + 1, j):
            d = seg_dist(coords[k], coords[i], coords[j])
            if d > best:
                best, at = d, k
        if at >= 0 and best > tol:
            keep[at] = True
            stack.append((i, at))
            stack.append((at, j))
    return [c for c, k in zip(coords, keep) if k]


def polyline(coords):
    out = []
    plat = plon = 0
    for c in coords:
        lat, lon = int(round(c[1] * 1e5)), int(round(c[0] * 1e5))
        for d in (lat - plat, lon - plon):
            v = ~(d << 1) if d < 0 else d << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = lat, lon
    return "".join(out)


def speed(v):
    """A maxspeed value in km/h; 0 for no limit; None when not readable."""
    if not v:
        return None
    v = v.strip()
    if v in ("none", "unlimited"):
        return 0.0
    if v in ("signals", "variable", "walk") or ";" in v:
        return 7.0 if v == "walk" else None
    m = NUM.match(v)
    if m:
        x = float(m.group(1).replace(",", "."))
        if m.group(2) == "mph":
            x *= 1.609
        elif m.group(2) == "knots":
            x *= 1.852
        return round(x) if 3 <= x <= 200 else None
    if ":" in v:
        code = v.split(";")[0]
        if code in IMPLICIT_COUNTRY:
            return float(IMPLICIT_COUNTRY[code])
        tail = code.split(":")[-1]
        m = re.match(r"zone:?(\d+)", tail)
        if m:
            return float(m.group(1))
        if tail in IMPLICIT:
            return float(IMPLICIT[tail])
    return None


def ints(v):
    try:
        x = int(str(v).split(";")[0].strip())
        return x if 0 < x < 16 else None
    except (ValueError, TypeError):
        return None


def lanes_text(v):
    """turn:lanes with at least two lanes ("left|through|through;right"), else None."""
    if not v or "|" not in v:
        return None
    return v.strip()[:120]


def yes(v):
    return 1 if v in ("yes", "true", "1") else 0


def main():
    src, dst = sys.argv[1], sys.argv[2]
    db = sqlite3.connect(dst)
    db.executescript("""
        DROP TABLE IF EXISTS roads; DROP TABLE IF EXISTS road_cells;
        CREATE TABLE roads(id INTEGER PRIMARY KEY, osm INTEGER, hw TEXT, ms REAL, msf REAL, msb REAL, mshgv REAL,
                           ow INTEGER, ln INTEGER, lnf INTEGER, lnb INTEGER, tl TEXT, tlf TEXT, tlb TEXT,
                           rb INTEGER, toll INTEGER, tun INTEGER, br INTEGER, ref TEXT, name TEXT, pts TEXT);
        CREATE TABLE road_cells(cell INTEGER NOT NULL, rid INTEGER NOT NULL);
    """)
    n = 0
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            feat = json.loads(line)
            geom = feat.get("geometry") or {}
            if geom.get("type") != "LineString":
                continue
            t = feat.get("properties") or {}
            hw = t.get("highway")
            if hw not in MAIN and hw not in SMALL:
                continue
            ms = speed(t.get("maxspeed"))
            msf = speed(t.get("maxspeed:forward"))
            msb = speed(t.get("maxspeed:backward"))
            mshgv = speed(t.get("maxspeed:hgv") or t.get("maxspeed:hgv:forward"))
            ln, lnf, lnb = ints(t.get("lanes")), ints(t.get("lanes:forward")), ints(t.get("lanes:backward"))
            tl = lanes_text(t.get("turn:lanes"))
            tlf = lanes_text(t.get("turn:lanes:forward"))
            tlb = lanes_text(t.get("turn:lanes:backward"))
            if hw in SMALL and ms is None and msf is None and msb is None and tl is None and tlf is None and tlb is None:
                continue
            ow_tag = t.get("oneway")
            rb = 1 if t.get("junction") in ("roundabout", "circular") else 0
            ow = -1 if ow_tag == "-1" else 1 if (yes(ow_tag) or rb or (hw in ("motorway", "motorway_link") and ow_tag != "no")) else 0
            coords = simplify(geom["coordinates"])
            osm = feat.get("id") or ""
            try:
                osm_id = int(str(osm).lstrip("w"))
            except ValueError:
                osm_id = 0
            toll = 1 if yes(t.get("toll")) or yes(t.get("toll:hgv")) else 0
            tun = 1 if t.get("tunnel") in ("yes", "building_passage", "culvert") else 0
            br = 1 if t.get("bridge") not in (None, "no") else 0
            cur = db.execute(
                "INSERT INTO roads(osm, hw, ms, msf, msb, mshgv, ow, ln, lnf, lnb, tl, tlf, tlb, rb, toll, tun, br, ref, name, pts) "
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (osm_id, hw, ms, msf, msb, mshgv, ow, ln, lnf, lnb, tl, tlf, tlb, rb, toll, tun, br,
                 (t.get("ref") or "")[:40] or None, (t.get("name") or "")[:80] or None, polyline(coords)))
            rid = cur.lastrowid
            db.executemany("INSERT INTO road_cells(cell, rid) VALUES (?,?)", [(c, rid) for c in cells_for(coords)])
            n += 1
    db.execute("CREATE INDEX road_cells_cell ON road_cells(cell)")
    db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('roads', ?)", (str(n),))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"roads: {n}")


if __name__ == "__main__":
    main()
