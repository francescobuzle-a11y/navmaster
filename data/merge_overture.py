#!/usr/bin/env python3
"""Adds the places of Overture Maps (open data, CDLA Permissive 2.0) to poi.sqlite, where
OpenStreetMap has nothing of the same kind nearby: more fuel stations, restaurants, hotels,
supermarkets, tyre shops ... along the routes. OpenStreetMap stays the main source; Overture fills
the gaps. Places are read straight from the public Parquet files (no account) with DuckDB, only
inside the region's box and for the kinds the app shows.

Usage: merge_overture.py <poi.sqlite> <region-id> [--area=<index-v1.json|.poly>]
(the area: only the places inside the outline Geofabrik cuts the region with, see region_area.py)
Never fails the build: without network or DuckDB it leaves poi.sqlite as it is.
"""
import json
import math
import os
import re
import sqlite3
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nmcommon import cell_id, norm  # noqa: E402
from region_area import allowed_countries, load_area, robust_box  # noqa: E402

BUCKET = "https://overturemaps-us-west-2.s3.amazonaws.com"


def latest_release():
    xml = urllib.request.urlopen(f"{BUCKET}/?list-type=2&prefix=release/&delimiter=/", timeout=60).read().decode()
    rel = sorted(re.findall(r"<Prefix>release/([0-9]{4}-[0-9]{2}-[0-9]{2}\.[0-9]+)/</Prefix>", xml))
    if not rel:
        raise RuntimeError("no Overture release found")
    return rel[-1]


def our_category(cat, name):
    """(category, flags) of the app for an Overture category, or None."""
    if not cat:
        return None
    n = (name or "").lower()
    if cat in ("gas_station", "fuel_station"):
        return "fuel", {}
    if cat in ("truck_stop",):
        return "services", {"hgv": True}
    if cat in ("rest_stop", "rest_area", "highway_rest_stop"):
        return "rest_area", {}
    if cat == "fast_food_restaurant":
        return "food", {"fast_food": True}
    if cat in ("cafe", "coffee_shop", "bar", "coffee_roastery"):
        return "food", {"cafe": True}
    if cat == "restaurant" or cat.endswith("_restaurant") or cat in ("diner", "pizza_place", "trattoria", "osteria"):
        return "food", {"restaurant": True}
    if cat in ("supermarket", "grocery_store", "hypermarket"):
        return "supermarket", {}
    if cat in ("hotel", "motel", "inn"):
        return "hotel", {}
    if cat in ("pharmacy", "drugstore"):
        return "pharmacy", {}
    if cat in ("hospital", "emergency_room"):
        return "hospital", {}
    if cat in ("atms", "atm"):
        return "atm", {}
    if cat in ("tire_dealer_and_repair", "tire_shop", "tire_repair_shop"):
        return "tyres", {}
    if cat in ("truck_repair", "commercial_truck_repair", "truck_repair_shop"):
        return "truck_repair", {}
    if cat in ("car_wash", "truck_wash") and re.search(r"truck|camion|tir\b|lkw|poids", n):
        return "truck_wash", {}
    if cat in ("rv_park", "caravan_site"):
        return "camper_site", {}
    if cat in ("campground", "campsite", "camping"):
        return "campsite", {}
    if cat in ("ev_charging_station",) and re.search(r"truck|camion|lkw|hgv|megawatt|mcs", n):
        return "hgv_charging", {"hgv": True}
    return None


CATS = ["gas_station", "fuel_station", "truck_stop", "rest_stop", "rest_area", "highway_rest_stop", "fast_food_restaurant",
        "cafe", "coffee_shop", "bar", "restaurant", "diner", "pizza_place", "supermarket", "grocery_store", "hypermarket", "hotel",
        "motel", "inn", "pharmacy", "drugstore", "hospital", "emergency_room", "atms", "atm", "tire_dealer_and_repair", "tire_shop",
        "tire_repair_shop", "truck_repair", "commercial_truck_repair", "truck_repair_shop", "car_wash", "truck_wash", "rv_park", "campground",
        "ev_charging_station"]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dbp, region = args[0], args[1]
    area_file = next((a.split("=", 1)[1] for a in sys.argv[1:] if a.startswith("--area=")), None)
    started = time.time()
    db = sqlite3.connect(dbp)
    here = os.path.dirname(os.path.abspath(__file__))
    countries = allowed_countries(region, os.path.join(here, "countries.json"))
    area = load_area(area_file, region)
    if area is not None:
        boxes = area.boxes()
    else:
        # the box of the OSM places less the farthest 0.1 % (one far object stretched it over Europe)
        pts = db.execute("SELECT lat, lon FROM poi").fetchall()
        box = robust_box([p[0] for p in pts], [p[1] for p in pts])
        if box is None:
            print("overture: no OSM places to take the box from, skipped")
            return
        boxes = [box]
    print("overture boxes:", ", ".join(f"{b[0]:.2f},{b[1]:.2f},{b[2]:.2f},{b[3]:.2f}" for b in boxes))
    import duckdb  # noqa: E402  (installed by the workflow)

    rel = latest_release()
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2';")
    path = f"s3://overturemaps-us-west-2/release/{rel}/theme=places/type=place/*"
    cats = ",".join("'" + c + "'" for c in CATS)
    # the schema changes between releases: pick what this one has
    cols = {r[0]: r[1] for r in con.execute(f"DESCRIBE SELECT * FROM read_parquet('{path}', hive_partitioning=1) LIMIT 0").fetchall()}
    print("overture columns:", ", ".join(sorted(cols)))

    def has(c):
        return c in cols

    if has("categories"):
        cat_expr = "categories.primary"
    elif has("taxonomy"):
        cat_expr = "taxonomy.primary"
    elif has("basic_category"):
        cat_expr = "basic_category"
    else:
        raise RuntimeError("no category column")
    name_expr = "names.primary" if has("names") else "NULL"
    brand_expr = "brand.names.primary" if has("brand") else "NULL"
    country_expr = "addresses[1].country" if has("addresses") else "NULL"
    web_expr = "websites[1]" if has("websites") else "NULL"
    phone_expr = "phones[1]" if has("phones") else "NULL"
    conf_expr = "confidence" if has("confidence") else "1.0"
    rows, seen, outside = [], set(), 0
    for w, s, e, n in boxes:
        q = f"""
          SELECT id, {name_expr} AS name, {cat_expr} AS cat, bbox.xmin AS lon, bbox.ymin AS lat, {conf_expr} AS confidence,
                 {brand_expr} AS brand, {country_expr} AS country, {web_expr} AS web, {phone_expr} AS phone
          FROM read_parquet('{path}', hive_partitioning=1)
          WHERE bbox.xmin BETWEEN {w} AND {e} AND bbox.ymin BETWEEN {s} AND {n} AND {conf_expr} >= 0.55
            AND ({cat_expr} IN ({cats}) OR {cat_expr} LIKE '%\\_restaurant' ESCAPE '\\')
        """
        cur = con.execute(q)
        while True:
            batch = cur.fetchmany(20000)
            if not batch:
                break
            batch = [r for r in batch if r[3] is not None and r[4] is not None]
            inside = area.contains_many([r[3] for r in batch], [r[4] for r in batch]) if area is not None else [True] * len(batch)
            for r, ok in zip(batch, inside):
                if not ok:
                    outside += 1
                elif r[0] not in seen:
                    seen.add(r[0])
                    rows.append(r)
    print(f"overture {rel}: {len(rows)} candidates ({outside} outside the region left out) in {time.time() - started:.0f}s")

    # OpenStreetMap places by cell, to skip what OSM already has
    existing = {}
    for pid, cat, name, lat, lon in db.execute("SELECT id, cat, name, lat, lon FROM poi"):
        existing.setdefault(cell_id(lat, lon), []).append((cat, norm(name or ""), lat, lon))
    next_id = (db.execute("SELECT max(id) FROM poi").fetchone()[0] or 0) + 1
    added = {}
    out, cells, fts = [], [], []
    for oid, name, cat, lon, lat, conf, brand, country, web, phone in rows:
        if lat is None or lon is None:
            continue
        # the region's countries only (Northern Ireland GB in "ireland-and-northern-ireland", Jersey JE
        # in "guernsey-jersey": data/region_area.py)
        if countries and country and country.upper() not in countries:
            continue
        mapped = our_category(cat, name)
        if mapped is None:
            continue
        ours, flags = mapped
        nn = norm(name or "")
        dup = False
        c0 = cell_id(lat, lon)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for ecat, ename, elat, elon in existing.get(c0 + dy * 100000 + dx, []):
                    d = math.hypot((elon - lon) * 111320 * math.cos(math.radians(lat)), (elat - lat) * 110540)
                    if (ecat == ours and d < 80) or (nn and ename == nn and d < 200):
                        dup = True
                        break
                if dup:
                    break
            if dup:
                break
        if dup:
            continue
        flags = {k: v for k, v in flags.items() if v}
        flags["ovt"] = True
        extra = {"source": "Overture Maps", "confidence": round(conf or 0, 2)}
        if web:
            extra["website"] = web
        out.append((next_id, "ovt:" + str(oid), ours, name, brand, round(lat, 6), round(lon, 6), ",".join(sorted(flags)), None, phone,
                    json.dumps(extra, ensure_ascii=False)))
        cells.append((c0, next_id))
        words = " ".join(filter(None, [nn, norm(brand or "")]))
        if words:
            fts.append((next_id, words))
        existing.setdefault(c0, []).append((ours, nn, lat, lon))
        added[ours] = added.get(ours, 0) + 1
        next_id += 1
    db.executemany("INSERT INTO poi VALUES (?,?,?,?,?,?,?,?,?,?,?)", out)
    db.executemany("INSERT INTO poi_cells VALUES (?,?)", cells)
    db.executemany("INSERT INTO poi_fts(docid, norm) VALUES (?,?)", fts)
    db.execute("INSERT OR REPLACE INTO meta VALUES ('overture', ?)", (json.dumps({"release": rel, "added": added}),))
    db.execute("INSERT OR REPLACE INTO meta VALUES ('source', 'OpenStreetMap contributors (ODbL); Overture Maps Foundation (CDLA Permissive 2.0)')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"overture: {sum(added.values())} places added {added} in {time.time() - started:.0f}s")


if __name__ == "__main__":
    try:
        main()
    except Exception as ex:  # the OSM places stay as they are
        print(f"overture: skipped ({ex})")
