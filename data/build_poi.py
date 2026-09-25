#!/usr/bin/env python3
"""Builds poi.sqlite: places a driver of a truck, bus or camper needs along the way (fuel with HGV
pumps and AdBlue, truck parkings, rest areas, food, showers, tyre and truck repair, weighbridges,
camper service points ...), indexed on the same 0.01 degree grid the app uses.

Input: osmium export -f geojsonseq --add-unique-id=type_id of the POI-filtered extract.
Usage: build_poi.py <in.geojsonseq> <out.sqlite> <region>
"""
import json
import sqlite3
import sys
import time

from nmcommon import cell_id, norm, read_geojsonseq, representative_point

YES = {"yes", "designated", "only", "permissive"}


def truthy(v):
    return v is not None and v.split(";")[0].strip().lower() in YES


def category(t):
    """(category, flags) or None. Flags: hgv, adblue, lpg, h24, free, guarded, restaurant/fast_food/cafe."""
    amenity = t.get("amenity")
    highway = t.get("highway")
    shop = t.get("shop")
    tourism = t.get("tourism")
    hgv = truthy(t.get("hgv")) or truthy(t.get("fuel:HGV_diesel")) or truthy(t.get("hgv:lanes"))
    if amenity == "fuel":
        return "fuel", {"hgv": hgv, "adblue": truthy(t.get("fuel:adblue")), "lpg": truthy(t.get("fuel:lpg"))}
    if amenity == "parking":
        if hgv or t.get("parking") in ("truck", "lorry") or t.get("capacity:hgv") not in (None, "0"):
            return "truck_parking", {"hgv": True, "guarded": truthy(t.get("supervised")) or t.get("surveillance") == "guard"}
        if truthy(t.get("motorhome")) or truthy(t.get("caravan")):
            return "camper_parking", {}
        return None
    if highway == "rest_area":
        return "rest_area", {"hgv": hgv}
    if highway == "services":
        return "services", {"hgv": hgv}
    if tourism == "caravan_site":
        return "camper_site", {}
    if amenity == "sanitary_dump_station":
        return "camper_service", {}
    if amenity in ("restaurant", "fast_food", "cafe"):
        # the kind is a flag too: the driver may want only restaurants, or only a quick coffee
        return "food", {amenity: True}
    if shop == "supermarket":
        return "supermarket", {}
    if amenity == "toilets":
        return "toilets", {}
    if amenity == "shower":
        return "shower", {}
    if amenity == "car_wash" and (hgv or t.get("car_wash") == "truck"):
        return "truck_wash", {}
    if shop == "tyres":
        return "tyres", {"hgv": hgv}
    if shop == "truck_repair" or (shop == "car_repair" and hgv) or t.get("craft") == "truck_repair":
        return "truck_repair", {}
    if tourism in ("hotel", "motel"):
        return "hotel", {}
    if amenity == "weighbridge":
        return "weighbridge", {}
    if t.get("barrier") == "border_control":
        return "border", {}
    if amenity in ("atm", "bank"):
        return "atm", {}
    if amenity == "pharmacy":
        return "pharmacy", {}
    if amenity == "hospital":
        return "hospital", {}
    if amenity == "charging_station" and hgv:
        return "hgv_charging", {"hgv": True}
    return None


def main():
    src, dst, region = sys.argv[1], sys.argv[2], sys.argv[3]
    db = sqlite3.connect(dst)
    db.executescript("""
        DROP TABLE IF EXISTS meta; DROP TABLE IF EXISTS poi; DROP TABLE IF EXISTS poi_cells; DROP TABLE IF EXISTS poi_fts;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE poi(id INTEGER PRIMARY KEY, osm TEXT, cat TEXT NOT NULL, name TEXT, brand TEXT,
                         lat REAL NOT NULL, lon REAL NOT NULL, flags TEXT, hours TEXT, phone TEXT,
                         extra TEXT);
        CREATE TABLE poi_cells(cell INTEGER NOT NULL, pid INTEGER NOT NULL);
        CREATE VIRTUAL TABLE poi_fts USING fts4(norm);
    """)
    started = time.time()
    counts = {}
    rows, cells, fts = [], [], []
    pid = 0
    for f in read_geojsonseq(src):
        t = f.get("properties") or {}
        c = category(t)
        if c is None:
            continue
        cat, flags = c
        p = representative_point(f.get("geometry") or {"type": "None"})
        if p is None:
            continue
        pid += 1
        if t.get("opening_hours", "").strip() == "24/7":
            flags["h24"] = True
        if t.get("fee") in ("no",):
            flags["free"] = True
        flags = {k: v for k, v in flags.items() if v}
        extra = {k: t[k] for k in ("capacity:hgv", "capacity", "website", "operator", "fuel:diesel", "maxstay",
                                   "toilets", "shower", "internet_access", "motorhome", "fee") if k in t}
        name = t.get("name") or t.get("brand") or t.get("operator")
        rows.append((pid, f.get("id"), cat, name, t.get("brand"), round(p[1], 6), round(p[0], 6),
                     ",".join(sorted(flags)) or None, t.get("opening_hours"), t.get("phone") or t.get("contact:phone"),
                     json.dumps(extra, ensure_ascii=False) if extra else None))
        cells.append((cell_id(p[1], p[0]), pid))
        words = " ".join(filter(None, [norm(name), norm(t.get("brand")), norm(t.get("addr:city"))]))
        if words:
            fts.append((pid, words))
        counts[cat] = counts.get(cat, 0) + 1
        if len(rows) >= 50000:
            db.executemany("INSERT INTO poi VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
            db.executemany("INSERT INTO poi_cells VALUES (?,?)", cells)
            db.executemany("INSERT INTO poi_fts(docid, norm) VALUES (?,?)", fts)
            rows, cells, fts = [], [], []
    db.executemany("INSERT INTO poi VALUES (?,?,?,?,?,?,?,?,?,?,?)", rows)
    db.executemany("INSERT INTO poi_cells VALUES (?,?)", cells)
    db.executemany("INSERT INTO poi_fts(docid, norm) VALUES (?,?)", fts)
    db.execute("CREATE INDEX poi_cells_cell ON poi_cells(cell)")
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("region", region), ("built", time.strftime("%Y-%m-%d")), ("format", "1"),
        ("counts", json.dumps(counts)), ("source", "OpenStreetMap contributors, ODbL"),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"poi: {pid} in {time.time() - started:.0f}s {counts}")


if __name__ == "__main__":
    main()
