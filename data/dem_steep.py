#!/usr/bin/env python3
"""Steep roads from the terrain, for the roads where OpenStreetMap has no "incline" tag.

The elevation comes from the public Terrain Tiles on AWS (Skadi format, 1 degree SRTM-based tiles,
open data: https://registry.opendata.aws/terrain-tiles/). Each road of the kinds a lorry uses on
hills (primary to residential) is sampled every 20 m; the gradient is measured over 200 m so that
the few metres of error of the terrain model do not make false slopes, and roads on bridges, in
tunnels or on embankments (where the road is not on the ground) are left out.

Used by build_critical.py; never fails the build: without the network there are simply no
terrain slopes.
"""
import gzip
import io
import math
import os
import urllib.request

import numpy as np

BASE = "https://elevation-tiles-prod.s3.amazonaws.com/skadi"
CLASSES = {"primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "road"}
STEP_M = 20.0
WINDOW_M = 200.0
MIN_PCT = 9.0           # a bit above the 8 % of the mapped slopes: the model is less precise
MAX_PCT = 30.0          # above this it is the valley side, not the road


def tile_name(lat, lon):
    la = math.floor(lat)
    lo = math.floor(lon)
    ns = f"N{la:02d}" if la >= 0 else f"S{-la:02d}"
    ew = f"E{lo:03d}" if lo >= 0 else f"W{-lo:03d}"
    return ns, f"{ns}{ew}"


class Dem:
    def __init__(self, cache_dir, max_tiles=12):
        self.cache_dir = cache_dir
        os.makedirs(cache_dir, exist_ok=True)
        self.tiles = {}
        self.order = []
        self.max_tiles = max_tiles
        self.missing = set()
        self.downloaded = 0

    def _load(self, key):
        ns, name = key
        path = os.path.join(self.cache_dir, name + ".hgt.gz")
        if not os.path.exists(path):
            try:
                data = urllib.request.urlopen(f"{BASE}/{ns}/{name}.hgt.gz", timeout=120).read()
            except Exception:
                return None  # sea, or no network
            with open(path, "wb") as fh:
                fh.write(data)
            self.downloaded += 1
        raw = gzip.open(path).read()
        n = int(math.isqrt(len(raw) // 2))
        if n * n * 2 != len(raw):
            return None
        return np.frombuffer(raw, dtype=">i2").reshape((n, n)).astype(np.int16)

    def tile(self, lat, lon):
        key = tile_name(lat, lon)
        if key in self.missing:
            return None
        t = self.tiles.get(key)
        if t is None:
            t = self._load(key)
            if t is None:
                self.missing.add(key)
                return None
            self.tiles[key] = t
            self.order.append(key)
            if len(self.order) > self.max_tiles:
                self.tiles.pop(self.order.pop(0), None)
        return t

    def heights(self, pts):
        """Heights of many (lon, lat) points (numpy, one tile at a time), or None if any is missing."""
        lons = np.array([p[0] for p in pts])
        lats = np.array([p[1] for p in pts])
        out = np.empty(len(pts), dtype=np.float64)
        keys = np.floor(lats).astype(int) * 1000 + np.floor(lons).astype(int)
        for key in np.unique(keys):
            sel = keys == key
            la = lats[sel]
            lo = lons[sel]
            t = self.tile(float(la[0]), float(lo[0]))
            if t is None:
                return None
            n = t.shape[0]
            fy = (np.floor(la) + 1 - la) * (n - 1)
            fx = (lo - np.floor(lo)) * (n - 1)
            r = np.minimum(fy.astype(int), n - 2)
            c = np.minimum(fx.astype(int), n - 2)
            dy = fy - r
            dx = fx - c
            q00 = t[r, c].astype(np.float64)
            q01 = t[r, c + 1].astype(np.float64)
            q10 = t[r + 1, c].astype(np.float64)
            q11 = t[r + 1, c + 1].astype(np.float64)
            if (np.minimum(np.minimum(q00, q01), np.minimum(q10, q11)) < -1000).any():
                return None
            out[sel] = q00 * (1 - dx) * (1 - dy) + q01 * dx * (1 - dy) + q10 * (1 - dx) * dy + q11 * dx * dy
        return out

    def height(self, lat, lon):
        """Bilinear height in metres, or None (no data)."""
        t = self.tile(lat, lon)
        if t is None:
            return None
        n = t.shape[0]
        fy = (math.floor(lat) + 1 - lat) * (n - 1)
        fx = (lon - math.floor(lon)) * (n - 1)
        r = min(int(fy), n - 2)
        c = min(int(fx), n - 2)
        dy = fy - r
        dx = fx - c
        q = t[r:r + 2, c:c + 2].astype(np.float64)
        if (q < -1000).any():
            return None
        return float(q[0, 0] * (1 - dx) * (1 - dy) + q[0, 1] * dx * (1 - dy) + q[1, 0] * (1 - dx) * dy + q[1, 1] * dx * dy)


def _dist(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320 * math.cos(lat), (b[1] - a[1]) * 110540)


def resample(coords, step=STEP_M):
    """Points every `step` metres along the line (lon, lat), the last point included."""
    cum = [0.0]
    for i in range(1, len(coords)):
        cum.append(cum[-1] + _dist(coords[i - 1], coords[i]))
    total = cum[-1]
    out = []
    j = 0
    s = 0.0
    while s <= total + 1e-6:
        while j < len(coords) - 2 and cum[j + 1] < s:
            j += 1
        seg = cum[j + 1] - cum[j]
        t = 0.0 if seg <= 0 else min(1.0, max(0.0, (s - cum[j]) / seg))
        a, b = coords[j], coords[j + 1]
        out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
        s += step
    return out


def eligible(t):
    if t.get("highway") not in CLASSES:
        return False
    if t.get("incline"):
        return False  # the mapped value is better
    for k in ("bridge", "tunnel", "embankment", "covered"):
        if t.get(k) not in (None, "no"):
            return False
    if t.get("layer") not in (None, "0"):
        return False
    return True


def max_grade(dem, coords):
    """(percent, index of the steepest window start) over WINDOW_M, or None."""
    pts = resample(coords)
    if len(pts) * STEP_M < WINDOW_M:
        return None
    hs = dem.heights(pts)
    if hs is None:
        return None
    w = int(WINDOW_M / STEP_M)
    if len(hs) <= w:
        return None
    g = np.abs(hs[w:] - hs[:-w]) / WINDOW_M * 100
    at = int(np.argmax(g))
    best = float(g[at])
    if best < MIN_PCT or best > MAX_PCT:
        return None
    return round(best, 1), pts[at + w // 2]
