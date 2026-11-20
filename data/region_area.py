"""The area of a region's package: the outline Geofabrik cuts its OpenStreetMap extract with.

The data builders that add places from other sources (Overture Maps) keep only what falls inside
it. Before, they took the box of the region's OpenStreetMap places, and one far object in the extract
(a ferry terminal "Durrës - Trieste" in the Slovenian one) stretched that box over half of Europe:
Slovenia got Rome, Zagreb and Sarajevo (1.0 million places instead of ~0.15), France ran its CI runner
out of memory.

load_area(path, region): path is Geofabrik's index-v1.json (the feature whose id is the region) or an
Osmosis .poly file; None when the file or shapely is missing or the region is not in it.
robust_box(lats, lons): without an outline, the box of the points without the farthest few.
"""
import json


class Area:
    def __init__(self, geom):
        import shapely
        from shapely.geometry import shape
        self._shapely = shapely
        self.geom = shape(geom) if isinstance(geom, dict) else geom
        shapely.prepare(self.geom)
        self.parts = list(self.geom.geoms) if self.geom.geom_type == "MultiPolygon" else [self.geom]

    def boxes(self, margin=0.01):
        """(west, south, east, north) of every part (mainland, islands), overlapping ones merged."""
        bs = [[p.bounds[0] - margin, p.bounds[1] - margin, p.bounds[2] + margin, p.bounds[3] + margin] for p in self.parts]
        merged = True
        while merged:
            merged = False
            out = []
            for b in bs:
                for o in out:
                    if b[0] <= o[2] and o[0] <= b[2] and b[1] <= o[3] and o[1] <= b[3]:
                        o[:] = [min(o[0], b[0]), min(o[1], b[1]), max(o[2], b[2]), max(o[3], b[3])]
                        merged = True
                        break
                else:
                    out.append(b)
            bs = out
        return [tuple(b) for b in bs]

    def contains(self, lon, lat):
        return bool(self._shapely.contains_xy(self.geom, lon, lat))

    def contains_many(self, lons, lats):
        """One bool per point (vectorised: a million points in about a second)."""
        if not lons:
            return []
        return self._shapely.contains_xy(self.geom, lons, lats).tolist()


def _read_poly(path):
    """Osmosis polygon format: a name line, then sections (a "!" before the name = a hole), END."""
    from shapely.geometry import Polygon
    from shapely.ops import unary_union
    outers, holes = [], []
    with open(path, encoding="utf-8") as f:
        lines = [x.strip() for x in f]
    i = 1
    while i < len(lines):
        head = lines[i]
        i += 1
        if head == "END" or not head:
            continue
        ring = []
        while i < len(lines) and lines[i] != "END":
            parts = lines[i].split()
            if len(parts) >= 2:
                ring.append((float(parts[0]), float(parts[1])))
            i += 1
        i += 1
        if len(ring) >= 3:
            (holes if head.startswith("!") else outers).append(Polygon(ring))
    if not outers:
        return None
    g = unary_union(outers)
    for h in holes:
        g = g.difference(h)
    return g


def load_area(path, region):
    if not path:
        return None
    try:
        if path.endswith(".poly"):
            g = _read_poly(path)
            return Area(g) if g is not None and not g.is_empty else None
        index = json.load(open(path, encoding="utf-8"))
        for f in index.get("features", []):
            if (f.get("properties") or {}).get("id") == region and f.get("geometry"):
                return Area(f["geometry"])
    except Exception as e:  # noqa: BLE001  (no shapely, a broken file: the caller falls back)
        print(f"area of {region}: not read ({e})")
    return None


def robust_box(lats, lons, cut=0.001, max_cut=20, margin=0.02):
    """(west, south, east, north) of the points without the farthest `cut` on every side, at most
    `max_cut` points a side: a few lone objects go, islands with hundreds of places (Lampedusa,
    Svalbard) stay."""
    if not lats:
        return None
    la, lo = sorted(lats), sorted(lons)
    k = min(int(len(la) * cut), max_cut)
    return (lo[k] - margin, la[k] - margin, lo[-1 - k] + margin, la[-1 - k] + margin)


# Territories inside a region's extract that have no package of their own: their places carry another
# country code in Overture (counted on release 2026-11-18: 106 142 GB places in Northern Ireland,
# 2 618 JE in Jersey, 1 640 SM in San Marino)
EXTRA_COUNTRIES = {
    "ireland-and-northern-ireland": {"GB"},
    "guernsey-jersey": {"JE"},
    "italy": {"SM", "VA"},
    "spain": {"GI"},
    "finland": {"AX"},
    "norway": {"SJ"},
}


def country_iso(region, countries_json):
    try:
        for c in json.load(open(countries_json, encoding="utf-8")):
            if c["id"] == region:
                return c["iso"]
    except Exception:  # noqa: BLE001
        pass
    return None


def allowed_countries(region, countries_json):
    """The country codes whose places belong to the region's package (None: no filter)."""
    iso = country_iso(region, countries_json)
    return ({iso} | EXTRA_COUNTRIES.get(region, set())) if iso else None
