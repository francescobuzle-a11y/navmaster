"""Helpers shared by the NavMaster data builders (grid cells, distances, geometry, text)."""
import json
import math
import re
import unicodedata

CELL = 0.01  # grid step in degrees (~1.1 km), the same grid the app uses


def cell_id(lat, lon):
    return int(math.floor(lat / CELL)) * 100000 + int(math.floor(lon / CELL)) + 50000


def dist_m(a, b):
    """a, b = (lon, lat)"""
    lat = math.radians((a[1] + b[1]) / 2)
    dx = (b[0] - a[0]) * 111320 * math.cos(lat)
    dy = (b[1] - a[1]) * 110540
    return math.hypot(dx, dy)


def cells_for(coords, sample_m=150):
    out = set()
    prev = None
    for c in coords:
        if prev is not None:
            d = dist_m(prev, c)
            steps = int(d // sample_m)
            for i in range(1, steps + 1):
                t = i / (steps + 1)
                out.add(cell_id(prev[1] + (c[1] - prev[1]) * t, prev[0] + (c[0] - prev[0]) * t))
        out.add(cell_id(c[1], c[0]))
        prev = c
    return out


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


def representative_point(geom):
    """(lon, lat): the point itself, the middle vertex of a line, the vertex mean of an area."""
    t = geom["type"]
    if t == "Point":
        return tuple(geom["coordinates"][:2])
    pts = geometry_coords(geom)
    if not pts:
        return None
    if t in ("LineString", "MultiLineString"):
        return tuple(pts[len(pts) // 2][:2])
    ring = pts[:-1] if len(pts) > 1 and pts[0] == pts[-1] else pts
    return (sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring))


def pts_text(coords):
    return ";".join(f"{c[1]:.6f},{c[0]:.6f}" for c in coords)


_KEEP = re.compile(r"[^a-z0-9 ]+")
_SPACES = re.compile(r"\s+")
_SPECIAL = str.maketrans({"ß": "ss", "æ": "ae", "ø": "o", "œ": "oe", "đ": "d", "ł": "l", "ı": "i", "þ": "th", "ð": "d"})


def norm(text):
    """Search form of a name: lower case, no accents, only letters/digits/spaces."""
    if not text:
        return ""
    s = text.lower().translate(_SPECIAL)
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    s = _KEEP.sub(" ", s)
    return _SPACES.sub(" ", s).strip()


def read_geojsonseq(path):
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            try:
                yield json.loads(line)
            except ValueError:
                continue


def circumradius(a, b, c):
    """Radius in metres of the circle through three (lon, lat) points; inf when they are aligned."""
    lat0 = math.radians(b[1])
    k = math.cos(lat0)
    ax, ay = (a[0] - b[0]) * 111320 * k, (a[1] - b[1]) * 110540
    cx, cy = (c[0] - b[0]) * 111320 * k, (c[1] - b[1]) * 110540
    ab = math.hypot(ax, ay)
    bc = math.hypot(cx, cy)
    ac = math.hypot(cx - ax, cy - ay)
    area2 = abs(ax * cy - ay * cx)  # twice the triangle area (b at the origin)
    if area2 < 1e-6:
        return float("inf")
    return ab * bc * ac / (2 * area2)


def resample(coords, step_m):
    """Points every step_m metres along the line (keeps the ends)."""
    if len(coords) < 2:
        return list(coords)
    out = [coords[0]]
    need = step_m  # distance from the start of the next segment to the next point
    for i in range(1, len(coords)):
        a, b = coords[i - 1], coords[i]
        seg = dist_m(a, b)
        pos = need
        while pos <= seg and seg > 0:
            t = pos / seg
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            pos += step_m
        need = pos - seg
    if tuple(out[-1][:2]) != tuple(coords[-1][:2]):
        out.append(coords[-1])
    return out


def min_radius(coords, step_m=10.0, span=2):
    """Tightest radius of a way (metres) and where it is, measured on points step_m apart so that
    the short segments of a mapped curve give a stable value."""
    pts = resample(coords, step_m)
    best = (float("inf"), None)
    for i in range(span, len(pts) - span):
        r = circumradius(pts[i - span], pts[i], pts[i + span])
        if r < best[0]:
            best = (r, pts[i])
    return best
