#!/usr/bin/env python3
"""Builds indirizzi.sqlite, the offline address book of a country, for the two ways of searching
on the tablet:
  * guided, as on iGO / Garmin: country -> town -> street -> house number
  * free text, as on Google Maps: "strada mihai eminescu 12 brasov"

Tables
  places(id, name, norm, kind, rank, lat, lon, county, pop, parent)   towns, villages, hamlets, districts
  streets(id, place_id, name, norm, lat, lon, nostreet)              one row per street and town
  houses(street_id, num, lat, lon)                                    house numbers
  place_fts / street_fts                                              FTS4 prefix search (docid = id)

Streets are given to the town whose "influence radius" they fall in (city 12 km, town 6 km,
village 2.5 km, hamlet 1 km); house numbers use their addr:city / addr:place when present.

Usage: build_addresses.py <places.geojsonseq> <streets.geojsonseq> <addr.geojsonseq>
                          <admin.geojsonseq> <out.sqlite> <region>
"""
import math
import sqlite3
import sys
import time

from nmcommon import dist_m, norm, read_geojsonseq, representative_point

RADIUS = {"city": 12000, "town": 6000, "village": 2500, "hamlet": 1000, "isolated_dwelling": 400}
RANK = {"city": 1, "town": 2, "village": 3, "suburb": 3, "quarter": 4, "hamlet": 4, "isolated_dwelling": 5}
DISTRICT = {"suburb", "quarter"}
GRID = 0.1
STREET_TYPES = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
                "living_street", "service", "pedestrian", "road", "track", "busway"}


def gkey(lon, lat):
    return (int(math.floor(lon / GRID)), int(math.floor(lat / GRID)))


class Places:
    def __init__(self):
        self.rows = []          # [id, name, norm, kind, rank, lat, lon, county, pop, parent]
        self.grid = {}          # settlements only
        self.by_norm = {}

    def add(self, name, kind, lon, lat, pop, alt):
        pid = len(self.rows) + 1
        self.rows.append([pid, name, norm(name), kind, RANK.get(kind, 5), lat, lon, None, pop, None, alt])
        if kind in RADIUS:
            self.grid.setdefault(gkey(lon, lat), []).append(pid)
        self.by_norm.setdefault(norm(name), []).append(pid)
        return pid

    def near(self, lon, lat, reach=1):
        gx, gy = gkey(lon, lat)
        for dx in range(-reach, reach + 1):
            for dy in range(-reach, reach + 1):
                yield from self.grid.get((gx + dx, gy + dy), ())

    def assign(self, lon, lat):
        """The settlement a point belongs to: best distance / radius score."""
        best, best_score = None, 9e9
        for pid in self.near(lon, lat, 2):
            r = self.rows[pid - 1]
            d = dist_m((lon, lat), (r[6], r[5]))
            score = d / RADIUS[r[3]]
            if score < best_score:
                best, best_score = pid, score
        if best is not None and best_score <= 2.5:
            return best
        return None

    def by_name_near(self, name, lon, lat, max_m=30000):
        best, best_d = None, max_m
        for pid in self.by_norm.get(norm(name), ()):
            r = self.rows[pid - 1]
            d = dist_m((lon, lat), (r[6], r[5]))
            if d < best_d:
                best, best_d = pid, d
        if best is not None and self.rows[best - 1][3] in DISTRICT and self.rows[best - 1][9]:
            return self.rows[best - 1][9]
        return best


def load_admin(path):
    try:
        from shapely.geometry import Point, shape
        from shapely.strtree import STRtree
    except ImportError:
        print("shapely missing: no county names")
        return None
    geoms, names = [], []
    for f in read_geojsonseq(path):
        t = f.get("properties") or {}
        if t.get("admin_level") != "4" or not t.get("name"):
            continue
        try:
            g = shape(f["geometry"])
            if not g.is_valid:
                g = g.buffer(0)
        except Exception:
            continue
        geoms.append(g)
        names.append(t.get("name"))
    if not geoms:
        return None
    tree = STRtree(geoms)

    def lookup(lon, lat):
        p = Point(lon, lat)
        for i in tree.query(p):
            if geoms[i].contains(p):
                return names[i]
        return None

    return lookup


def main():
    places_src, streets_src, addr_src, admin_src, dst, region = sys.argv[1:7]
    started = time.time()
    P = Places()

    # 1. towns, villages, districts
    for f in read_geojsonseq(places_src):
        t = f.get("properties") or {}
        kind = t.get("place")
        name = t.get("name")
        if kind not in RANK or not name:
            continue
        p = representative_point(f.get("geometry") or {"type": "None"})
        if p is None:
            continue
        try:
            pop = int(str(t.get("population", "0")).replace(".", "").replace(" ", "").split(";")[0] or 0)
        except ValueError:
            pop = 0
        alt = " ".join(filter(None, (norm(t.get(k)) for k in ("name:it", "name:en", "int_name", "alt_name", "official_name", "old_name", "short_name"))))
        P.add(name, kind, p[0], p[1], pop, alt)
    # districts belong to the nearest city or town
    for r in P.rows:
        if r[3] in DISTRICT:
            best, bd = None, 15000
            for pid in P.near(r[6], r[5], 2):
                q = P.rows[pid - 1]
                if q[3] not in ("city", "town"):
                    continue
                d = dist_m((r[6], r[5]), (q[6], q[5]))
                if d < bd:
                    best, bd = pid, d
            r[9] = best
    county = load_admin(admin_src)
    if county:
        for r in P.rows:
            r[7] = county(r[6], r[5])
    print(f"places: {len(P.rows)} ({time.time() - started:.0f}s)")

    # 2. streets, one row per (town, name)
    streets = {}        # (place_id, norm) -> [id, place_id, name, norm, lat, lon, nostreet]
    by_name = {}        # norm -> [street ids] for house numbers without a matching town
    srows = []

    def street_row(place_id, name, lon, lat, nostreet=0):
        key = (place_id, norm(name))
        row = streets.get(key)
        if row is None:
            row = [len(srows) + 1, place_id, name, norm(name), round(lat, 6), round(lon, 6), nostreet]
            streets[key] = row
            srows.append(row)
            by_name.setdefault(row[3], []).append(row[0])
        return row

    for f in read_geojsonseq(streets_src):
        t = f.get("properties") or {}
        name = t.get("name")
        if not name or t.get("highway") not in STREET_TYPES:
            continue
        p = representative_point(f.get("geometry") or {"type": "None"})
        if p is None:
            continue
        pid = P.assign(p[0], p[1])
        if pid is None:
            continue
        street_row(pid, name, p[0], p[1])
    print(f"streets: {len(srows)} ({time.time() - started:.0f}s)")

    # 3. house numbers
    houses = []
    for f in read_geojsonseq(addr_src):
        t = f.get("properties") or {}
        num = t.get("addr:housenumber")
        if not num:
            continue
        p = representative_point(f.get("geometry") or {"type": "None"})
        if p is None:
            continue
        sname = t.get("addr:street")
        nostreet = 0
        if not sname:
            sname = t.get("addr:place")
            nostreet = 1
        if not sname:
            continue
        pid = None
        if t.get("addr:city"):
            pid = P.by_name_near(t["addr:city"], p[0], p[1])
        if pid is None and nostreet and t.get("addr:place"):
            pid = P.by_name_near(t["addr:place"], p[0], p[1], 10000)
        if pid is None:
            pid = P.assign(p[0], p[1])
        if pid is None:
            continue
        row = streets.get((pid, norm(sname)))
        if row is None and not nostreet:
            # same street name mapped under a neighbouring town: take it if close
            best, bd = None, 3000
            for sid in by_name.get(norm(sname), ()):
                s = srows[sid - 1]
                d = dist_m((p[0], p[1]), (s[5], s[4]))
                if d < bd:
                    best, bd = s, d
            row = best
        if row is None:
            row = street_row(pid, sname, p[0], p[1], nostreet)
        for n in num.replace(",", ";").split(";"):
            n = n.strip()
            if n and len(n) <= 12:
                houses.append((row[0], n, round(p[1], 6), round(p[0], 6)))
    print(f"houses: {len(houses)} ({time.time() - started:.0f}s)")

    # 4. write
    db = sqlite3.connect(dst)
    db.executescript("""
        DROP TABLE IF EXISTS meta; DROP TABLE IF EXISTS places; DROP TABLE IF EXISTS streets;
        DROP TABLE IF EXISTS houses; DROP TABLE IF EXISTS place_fts; DROP TABLE IF EXISTS street_fts;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE places(id INTEGER PRIMARY KEY, name TEXT, norm TEXT, kind TEXT, rank INTEGER,
                            lat REAL, lon REAL, county TEXT, pop INTEGER, parent INTEGER);
        CREATE TABLE streets(id INTEGER PRIMARY KEY, place_id INTEGER, name TEXT, norm TEXT,
                             lat REAL, lon REAL, nostreet INTEGER);
        CREATE TABLE houses(street_id INTEGER NOT NULL, num TEXT NOT NULL, lat REAL, lon REAL);
        CREATE VIRTUAL TABLE place_fts USING fts4(norm);
        CREATE VIRTUAL TABLE street_fts USING fts4(norm);
    """)
    db.executemany("INSERT INTO places VALUES (?,?,?,?,?,?,?,?,?,?)", [r[:10] for r in P.rows])
    db.executemany("INSERT INTO place_fts(docid, norm) VALUES (?,?)",
                   [(r[0], (r[2] + " " + (r[10] or "")).strip()) for r in P.rows])
    db.executemany("INSERT INTO streets VALUES (?,?,?,?,?,?,?)", srows)
    pnorm = {r[0]: r[2] for r in P.rows}
    db.executemany("INSERT INTO street_fts(docid, norm) VALUES (?,?)",
                   [(s[0], s[3] + " " + pnorm.get(s[1], "")) for s in srows])
    db.executemany("INSERT INTO houses VALUES (?,?,?,?)", houses)
    db.executescript("""
        CREATE INDEX streets_place ON streets(place_id);
        CREATE INDEX houses_street ON houses(street_id);
        CREATE INDEX places_rank ON places(rank);
    """)
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("region", region), ("built", time.strftime("%Y-%m-%d")), ("format", "1"),
        ("places", str(len(P.rows))), ("streets", str(len(srows))), ("houses", str(len(houses))),
        ("source", "OpenStreetMap contributors, ODbL"),
    ])
    db.commit()
    db.execute("INSERT INTO place_fts(place_fts) VALUES('optimize')")
    db.execute("INSERT INTO street_fts(street_fts) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"indirizzi done in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
