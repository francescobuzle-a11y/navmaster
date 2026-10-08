"""
Makes the map tiles of a country smaller without changing what the app draws: in each layer only
the classes the style (data/style/make_style.py) shows. Keep the two in step: a class added to the
style needs the map data built again.

- poi: fuel stations and parking areas (the other places come from poi.sqlite, in the app's lists)
- transportation: no footpaths, cycleways, steps (class "path")
- landcover: woods, fields, grass; landuse: towns and industrial areas; place: cities to hamlets

usage: python3 slim_tiles.py mappa.mbtiles     (the MBTiles written by Planetiler, changed in place)
"""
import gzip
import sqlite3
import sys

# per layer: the classes kept (a feature without a class is kept), or the classes dropped ("-")
KEEP = {
    b"poi": {b"fuel", b"parking"},
    b"landcover": {b"wood", b"farmland", b"grass"},
    b"landuse": {b"industrial", b"commercial", b"retail", b"railway", b"residential", b"suburb", b"neighbourhood"},
    b"place": {b"city", b"town", b"village", b"suburb", b"hamlet", b"neighbourhood", b"country", b"state", b"continent"},
}
DROP = {
    b"transportation": {b"path"},
}


def varint(b, i):
    r = s = 0
    while True:
        x = b[i]
        i += 1
        r |= (x & 0x7F) << s
        s += 7
        if x < 128:
            return r, i


def fields(b, start=0, end=None):
    """(field, wire, value_start, value_end, raw_start) of a protobuf message."""
    i = start
    end = len(b) if end is None else end
    while i < end:
        raw = i
        key, i = varint(b, i)
        f, w = key >> 3, key & 7
        if w == 0:
            _, j = varint(b, i)
            yield f, w, i, j, raw
            i = j
        elif w == 2:
            n, i = varint(b, i)
            yield f, w, i, i + n, raw
            i += n
        elif w == 1:
            yield f, w, i, i + 8, raw
            i += 8
        elif w == 5:
            yield f, w, i, i + 4, raw
            i += 4
        else:
            raise ValueError("wire type %d" % w)


def enc_varint(n):
    out = bytearray()
    while True:
        x = n & 0x7F
        n >>= 7
        if n:
            out.append(x | 0x80)
        else:
            out.append(x)
            return bytes(out)


def slim_layer(b, s, e, name):
    """The layer with only the classes kept, or None when nothing is left."""
    keys, values, feats, other = [], [], [], []
    for f, w, a, z, raw in fields(b, s, e):
        if f == 3:
            keys.append(b[a:z])
        elif f == 4:
            v = None
            for f2, w2, a2, z2, _ in fields(b, a, z):
                if f2 == 1:
                    v = b[a2:z2]
            values.append(v)
        if f == 2:
            feats.append((a, z, raw))
        else:
            other.append(b[raw:z])
    if b"class" not in keys:
        return b[s:e]
    ck = keys.index(b"class")
    if name in KEEP:
        keep_vals = {i for i, v in enumerate(values) if v in KEEP[name]}
    else:
        keep_vals = {i for i, v in enumerate(values) if v not in DROP[name]}
    kept = []
    for a, z, raw in feats:
        ok = True
        for f, w, a2, z2, _ in fields(b, a, z):
            if f == 2:
                j, tags = a2, []
                while j < z2:
                    t, j = varint(b, j)
                    tags.append(t)
                for k in range(0, len(tags) - 1, 2):
                    if tags[k] == ck:
                        ok = tags[k + 1] in keep_vals
        if ok:
            kept.append(b[raw:z])
    if not kept:
        return None
    return b"".join(other[:1] + kept + other[1:])


def slim_tile(data):
    gz = data[:2] == b"\x1f\x8b"
    t = gzip.decompress(data) if gz else data
    out = []
    changed = False
    for f, w, a, z, raw in fields(t):
        if f == 3 and w == 2:
            name = None
            for f2, w2, a2, z2, _ in fields(t, a, z):
                if f2 == 1:
                    name = t[a2:z2]
                    break
            if name in KEEP or name in DROP:
                layer = slim_layer(t, a, z, name)
                changed = True
                if layer is not None:
                    out.append(enc_varint((3 << 3) | 2) + enc_varint(len(layer)) + layer)
                continue
        out.append(t[raw:z])
    if not changed:
        return data
    t2 = b"".join(out)
    return gzip.compress(t2, 9) if gz else t2


def main():
    db = sqlite3.connect(sys.argv[1])
    names = {r[0] for r in db.execute("select name from sqlite_master")}
    table, idcol = ("tiles_data", "tile_data_id") if "tiles_data" in names else ("tiles", "rowid")
    before = after = n = 0
    # a thousand tiles at a time: a big country's map does not fit in memory
    ids = [r[0] for r in db.execute(f"select {idcol} from {table}")]
    for k in range(0, len(ids), 1000):
        chunk = ids[k:k + 1000]
        q = ",".join("?" * len(chunk))
        for rid, data in db.execute(f"select {idcol}, tile_data from {table} where {idcol} in ({q})", chunk).fetchall():
            new = slim_tile(data)
            before += len(data)
            after += len(new)
            if new is not data:
                db.execute(f"update {table} set tile_data=? where {idcol}=?", (new, rid))
            n += 1
        db.commit()
    db.execute("vacuum")
    db.close()
    print(f"slim_tiles: {n} tiles, {before / 1e6:.1f} MB -> {after / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
