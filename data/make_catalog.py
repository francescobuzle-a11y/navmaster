#!/usr/bin/env python3
"""catalog.json: every European country the tablet can download, with its outline (to know in which
country the driver is), its size and the date of its data.

Usage: make_catalog.py <countries.json> <geofabrik index-v1.json> <manifests dir> <europe graph index or -> <out>
"""
import json
import os
import sys
import time


def outline(geom, tolerance=0.02, max_pts=1200):
    try:
        from shapely.geometry import mapping, shape
    except ImportError:
        return None, None
    g = shape(geom)
    bbox = [round(v, 3) for v in g.bounds]
    s = g.simplify(tolerance, preserve_topology=True)
    polys = list(s.geoms) if s.geom_type == "MultiPolygon" else [s]
    polys = sorted((p for p in polys if p.area > 0.0005), key=lambda p: -p.area)
    rings, used = [], 0
    for p in polys:
        ring = [[round(x, 3), round(y, 3)] for x, y in p.exterior.coords]
        if used + len(ring) > max_pts and rings:
            break
        rings.append(ring)
        used += len(ring)
    return bbox, rings


def main():
    countries = json.load(open(sys.argv[1], encoding="utf-8"))
    index = json.load(open(sys.argv[2], encoding="utf-8"))
    mdir = sys.argv[3]
    graph = None
    if sys.argv[4] != "-" and os.path.exists(sys.argv[4]):
        graph = json.load(open(sys.argv[4], encoding="utf-8"))
    geoms = {f["properties"]["id"]: f.get("geometry") for f in index["features"]}
    out = []
    for c in countries:
        entry = {"id": c["id"], "iso": c["iso"], "name": c["name"], "available": False}
        g = geoms.get(c["id"])
        if g:
            entry["bbox"], entry["poly"] = outline(g)
        mf = os.path.join(mdir, c["id"] + ".json")
        if os.path.exists(mf):
            m = json.load(open(mf, encoding="utf-8"))
            files = {f["file"]: f["size"] for f in m.get("files", [])}
            entry.update(available=True, built=m.get("built"), files=files, size=sum(files.values()))
        if graph and c["id"] in graph.get("countries", {}):
            entry["europe_tiles"] = graph["countries"][c["id"]]
        out.append(entry)
    cat = {"format": 1, "built": time.strftime("%Y-%m-%d"), "countries": out,
           "europe_graph": {"available": bool(graph), "built": graph.get("built") if graph else None,
                            "valhalla": graph.get("valhalla") if graph else None}}
    json.dump(cat, open(sys.argv[5], "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"catalog: {sum(1 for e in out if e['available'])} available of {len(out)}; europe graph: {bool(graph)}")


if __name__ == "__main__":
    main()
