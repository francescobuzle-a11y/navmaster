"""
Border crossings of a country (valichi.json): where the main roads cross into a neighbouring
country, so that the tablet can join the GraphHopper graphs of two countries (one graph per
country) into one route.

Each country's map extract (Geofabrik) reaches a little beyond the border (its polygon is the
country's, made a bit larger), so near a border the roads are in the extracts of both countries.
A crossing is a point of a road in that strip, inside the polygons of both countries: the route
in the first country goes to it, the route in the second one starts from it.

    python3 build_crossings.py roads.geojsonseq out/valichi.json REGION POLYDIR

roads.geojsonseq: the roads of the country (osmium export, with highway, ref, name, oneway,
junction). POLYDIR: the Geofabrik .poly files of the country and of all the others (<id>.poly).
For each crossing: the neighbour, the point, the kind of road, its number and name, and the
direction of travel there from this country into the neighbour ("ab") and back ("ba"), in
degrees, or null where the road is one-way the other way.
"""

import json
import math
import os
import sys
import time

import numpy as np
import shapely
from shapely.geometry import LineString, MultiPolygon, Point, Polygon
from shapely.ops import unary_union

# the roads a lorry or a camper crosses a border on (small roads are left out: too many, and the
# route between two countries goes on the main ones)
MAJOR = {"motorway", "trunk", "primary", "secondary", "tertiary", "motorway_link", "trunk_link", "primary_link"}
# crossings of the same road nearer than this are one (the vertices of the same stretch)
MERGE_M = 400


def read_poly(path):
    """A Geofabrik .poly file as a (multi)polygon: rings, holes marked with '!'."""
    with open(path) as f:
        lines = [l.strip() for l in f if l.strip()]
    outers, holes = [], []
    i = 1
    while i < len(lines):
        head = lines[i]
        i += 1
        if head == "END":
            break
        pts = []
        while i < len(lines) and lines[i] != "END":
            x, y = lines[i].split()[:2]
            pts.append((float(x), float(y)))
            i += 1
        i += 1
        if len(pts) >= 3:
            (holes if head.startswith("!") else outers).append(pts)
    shell = unary_union([Polygon(p).buffer(0) for p in outers])
    if holes:
        shell = shell.difference(unary_union([Polygon(p).buffer(0) for p in holes]))
    return shell


def metres(a, b):
    k = math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot((b[0] - a[0]) * 111_195 * k, (b[1] - a[1]) * 111_195)


def bearing(a, b):
    k = math.cos(math.radians((a[1] + b[1]) / 2))
    return (math.degrees(math.atan2((b[0] - a[0]) * k, b[1] - a[1])) + 360) % 360


def main():
    roads, out, region, polydir = sys.argv[1:5]
    started = time.time()
    own_path = os.path.join(polydir, region + ".poly")
    if not os.path.exists(own_path):
        print("no polygon for", region)
        json.dump({"region": region, "crossings": []}, open(out, "w"))
        return
    A = read_poly(own_path)
    # the neighbours: the countries whose polygon overlaps this one
    strips = {}
    for f in sorted(os.listdir(polydir)):
        if not f.endswith(".poly") or f == region + ".poly":
            continue
        b = f[:-5]
        B = read_poly(os.path.join(polydir, f))
        if not A.envelope.intersects(B.envelope):
            continue
        ov = A.intersection(B)
        if ov.is_empty or ov.area == 0:
            continue
        # the part of each country outside the strip, near it (to tell on which side a road goes)
        near = ov.buffer(0.2)
        a_only = A.difference(B).intersection(near)
        b_only = B.difference(A).intersection(near)
        shapely.prepare(ov)
        strips[b] = (ov, a_only, b_only)
        print("neighbour", b, "strip %.0f km2" % (ov.area * 111 * 111 * math.cos(math.radians(ov.centroid.y))))
    found = []
    n = 0
    with open(roads) as fh:
        for line in fh:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            f = json.loads(line)
            p = f.get("properties") or {}
            hw = p.get("highway")
            if hw not in MAJOR:
                continue
            g = f.get("geometry") or {}
            if g.get("type") != "LineString":
                continue
            coords = g["coordinates"]
            if len(coords) < 2:
                continue
            n += 1
            ls = LineString(coords)
            for b, (ov, a_only, b_only) in strips.items():
                if not shapely.intersects(ov, ls):
                    continue
                xs = np.array([c[0] for c in coords])
                ys = np.array([c[1] for c in coords])
                inside = shapely.contains_xy(ov, xs, ys)
                runs = []
                i = 0
                while i < len(coords):
                    if inside[i]:
                        j = i
                        while j + 1 < len(coords) and inside[j + 1]:
                            j += 1
                        runs.append((i, j))
                        i = j + 1
                    else:
                        i += 1
                if not runs:
                    # a long stretch across the strip without a vertex in it: its middle there
                    piece = ls.intersection(ov)
                    if piece.is_empty:
                        continue
                    mid = piece.interpolate(0.5, normalized=True) if hasattr(piece, "interpolate") else piece.representative_point()
                    # the stretch it is on
                    best, bi = None, 0
                    for k in range(len(coords) - 1):
                        d = LineString([coords[k], coords[k + 1]]).distance(mid)
                        if best is None or d < best:
                            best, bi = d, k
                    runs.append((bi, bi + 1, (mid.x, mid.y)))
                for run in runs:
                    s, e = run[0], run[1]
                    if len(run) == 3:
                        pt = run[2]
                        k0, k1 = s, e
                    else:
                        m = (s + e) // 2
                        pt = tuple(coords[m])
                        k0, k1 = max(0, m - 1), min(len(coords) - 1, m + 1)
                    # on which side the road comes from and goes to (the vertices next to the run)
                    before = Point(coords[max(0, s - 1)])
                    after = Point(coords[min(len(coords) - 1, e + 1)])

                    def side(q):
                        da, db = a_only.distance(q), b_only.distance(q)
                        return "A" if da < db else "B"

                    s0, s1 = side(before), side(after)
                    if s0 == s1:
                        continue  # along the border, not across it
                    h = bearing(coords[k0], coords[k1])
                    oneway = str(p.get("oneway", "")).lower()
                    fwd_only = oneway in ("yes", "true", "1") or p.get("junction") in ("roundabout", "circular") or \
                        (hw in ("motorway", "motorway_link") and oneway not in ("no", "-1"))
                    back_only = oneway == "-1"
                    # along the line: from s0 to s1
                    along_ab = s0 == "A"
                    ab = ba = None
                    if not back_only:
                        if along_ab:
                            ab = round(h)
                        else:
                            ba = round(h)
                    if not fwd_only:
                        if along_ab:
                            ba = round((h + 180) % 360)
                        else:
                            ab = round((h + 180) % 360)
                    if ab is None and ba is None:
                        continue
                    found.append({"to": b, "lat": round(pt[1], 6), "lon": round(pt[0], 6), "hw": hw,
                                  "ref": p.get("ref") or "", "name": p.get("name") or "", "ab": ab, "ba": ba})
    # one point per stretch of road and direction
    merged = []
    for c in found:
        dup = False
        for m in merged:
            if m["to"] == c["to"] and (m["ab"] is None) == (c["ab"] is None) and (m["ba"] is None) == (c["ba"] is None) \
                    and m["ref"] == c["ref"] and m["name"] == c["name"] \
                    and metres((m["lon"], m["lat"]), (c["lon"], c["lat"])) < MERGE_M:
                dup = True
                break
        if not dup:
            merged.append(c)
    rank = {"motorway": 0, "trunk": 1, "motorway_link": 2, "trunk_link": 2, "primary": 3, "primary_link": 4,
            "secondary": 5, "tertiary": 6}
    merged.sort(key=lambda c: (c["to"], rank.get(c["hw"], 9), c["lat"], c["lon"]))
    json.dump({"region": region, "built": time.strftime("%Y-%m-%d"), "crossings": merged}, open(out, "w"),
              ensure_ascii=False, separators=(",", ":"))
    per = {}
    for c in merged:
        per[c["to"]] = per.get(c["to"], 0) + 1
    print("%d roads, %d crossings %s in %.0f s" % (n, len(merged), per, time.time() - started))


if __name__ == "__main__":
    main()
