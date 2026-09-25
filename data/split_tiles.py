#!/usr/bin/env python3
"""Splits one Europe-wide Valhalla tile set into per-country tar files.

All tiles come from the same build, so the tablet can put the tiles of several countries in one
folder and route across their borders. A tile goes to every country its box touches (with a small
margin), so tiles on a border are in both packages.

Usage: split_tiles.py <tile dir> <countries.json> <geofabrik index-v1.json> <out dir> <valhalla version>
"""
import json
import os
import sys
import tarfile
import time

from shapely.geometry import box, shape
from shapely.strtree import STRtree

LEVEL_SIZE = {0: 4.0, 1: 1.0, 2: 0.25}


def tile_box(rel):
    parts = rel.replace("\\", "/").split("/")
    level = int(parts[0])
    if level not in LEVEL_SIZE:
        return None
    tid = int("".join(parts[1:]).split(".")[0])
    size = LEVEL_SIZE[level]
    ncols = int(round(360 / size))
    row, col = divmod(tid, ncols)
    minx = -180 + col * size
    miny = -90 + row * size
    return box(minx, miny, minx + size, miny + size)


def main():
    tiles, countries_path, index_path, out, version = sys.argv[1:6]
    countries = json.load(open(countries_path, encoding="utf-8"))
    index = json.load(open(index_path, encoding="utf-8"))
    geoms = {f["properties"]["id"]: f.get("geometry") for f in index["features"]}
    shapes, ids = [], []
    for c in countries:
        g = geoms.get(c["id"])
        if not g:
            continue
        shapes.append(shape(g).buffer(0.1))
        ids.append(c["id"])
    tree = STRtree(shapes)
    os.makedirs(out, exist_ok=True)
    tars, stats = {}, {}
    started = time.time()
    n = 0
    for root, _, files in os.walk(tiles):
        for name in files:
            if not name.endswith(".gph"):
                continue
            full = os.path.join(root, name)
            rel = os.path.relpath(full, tiles)
            b = tile_box(rel)
            if b is None:
                continue
            n += 1
            for i in tree.query(b):
                if not shapes[i].intersects(b):
                    continue
                cid = ids[i]
                if cid not in tars:
                    tars[cid] = tarfile.open(os.path.join(out, f"tiles-{cid}.tar"), "w")
                    stats[cid] = {"tiles": 0, "size": 0}
                tars[cid].add(full, arcname=rel)
                stats[cid]["tiles"] += 1
    for cid, t in tars.items():
        t.close()
        stats[cid]["size"] = os.path.getsize(os.path.join(out, f"tiles-{cid}.tar"))
    idx = {"built": time.strftime("%Y-%m-%d"), "valhalla": version, "tiles": n, "countries": stats}
    json.dump(idx, open(os.path.join(out, "index.json"), "w"), indent=1)
    print(f"{n} tiles split into {len(tars)} countries in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
