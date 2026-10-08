"""Valhalla's answers on the test trips of one area, as the tablet asked them (Ferrostar's request
with the app's options, a 4 m / 40 t articulated lorry): route in OSRM format with Italian voice and
banners, and trace_attributes on that route's shape (what RouteAnalysis reads).

python3 vhref.py trips.json AREA OUTDIR   (valhalla_service listening on localhost:8002)
python3 vhref.py --trace GHDIR AREA trips.json OUTDIR: Valhalla's guidance along GraphHopper's own
paths (GHDIR/<trip>.route.json, written by GuideRun), as the app asked it before GhGuide (map_snap,
points every 300 m): the same roads, so the manoeuvres can be compared one by one.
"""
import json
import os
import sys
import urllib.request

ATTRS = [
    "edge.way_id", "edge.road_class", "edge.use", "edge.toll", "edge.surface", "edge.lane_count",
    "edge.length", "edge.begin_shape_index", "edge.end_shape_index", "edge.names", "edge.tunnel",
    "edge.bridge", "edge.roundabout", "edge.sign.exit_number", "edge.sign.exit_branch", "edge.sign.exit_toward",
    "edge.sign.exit_name", "edge.max_upward_grade", "edge.max_downward_grade", "edge.end_node.admin_index",
    "node.admin_index", "node.type", "node.intersecting_edge.driveability", "node.intersecting_edge.use",
    "node.intersecting_edge.begin_heading", "node.intersecting_edge.road_class", "node.intersecting_edge.lane_count",
    "edge.begin_heading", "edge.end_heading", "edge.speed_limit", "edge.speed",
    "admin.country_code", "admin.country_text", "shape",
]

TRUCK = {
    "height": 4.0, "width": 2.55, "length": 16.5, "weight": 40.0, "axle_load": 11.5, "axle_count": 5,
    "hazmat": False, "use_truck_route": 0.0, "hgv_no_access_penalty": 43200, "top_speed": 140,
    "use_tolls": 0.5, "use_ferry": 0.5, "exclude_unpaved": False,
}


def post(action, body):
    req = urllib.request.Request("http://localhost:8002/" + action, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return {"error": e.code, "body": e.read().decode(errors="replace")}


def decode(s, prec=6):
    idx = lat = lng = 0
    out = []
    while idx < len(s):
        vals = []
        for _ in range(2):
            shift = res = 0
            while True:
                b = ord(s[idx]) - 63
                idx += 1
                res |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20:
                    break
            vals.append(~(res >> 1) if res & 1 else res >> 1)
        lat += vals[0]
        lng += vals[1]
        out.append((lat / 10 ** prec, lng / 10 ** prec))
    return out


def metres(a, b):
    import math
    k = math.cos(math.radians(a[0]))
    return math.hypot((b[0] - a[0]) * 111195, (b[1] - a[1]) * 111195 * k)


def trace(ghdir, area, trips_file, out):
    trips = json.load(open(trips_file))
    os.makedirs(out, exist_ok=True)
    for t in trips["trips"]:
        if t["area"] != area:
            continue
        f = os.path.join(ghdir, t["name"] + ".route.json")
        if not os.path.exists(f):
            continue
        pts = decode(json.load(open(f))["routes"][0]["geometry"])
        shape = []
        for i, p in enumerate(pts):
            if i > 0:
                d = metres(pts[i - 1], p)
                n = int(d // 300) + 1
                for k in range(1, n):
                    fr = k / n
                    shape.append({"lat": pts[i - 1][0] + (p[0] - pts[i - 1][0]) * fr, "lon": pts[i - 1][1] + (p[1] - pts[i - 1][1]) * fr, "type": "through"})
            shape.append({"lat": p[0], "lon": p[1], "type": "break" if i in (0, len(pts) - 1) else "through"})
        relaxed = {k: v for k, v in TRUCK.items() if k not in ("height", "width", "length", "weight", "axle_load", "axle_count", "hazmat")}
        body = {
            "format": "osrm",
            "filters": {"action": "include", "attributes": [
                "shape_attributes.speed", "shape_attributes.speed_limit", "shape_attributes.time", "shape_attributes.length"]},
            "banner_instructions": True, "voice_instructions": True, "costing": "truck",
            "costing_options": {"truck": relaxed}, "language": "it-IT", "units": "kilometers", "directions_type": "instructions",
            "shape": shape, "shape_match": "map_snap",
            "trace_options": {"search_radius": 30, "gps_accuracy": 5, "interpolation_distance": 0},
        }
        r = post("trace_route", body)
        json.dump(r, open(os.path.join(out, t["name"] + ".route.json"), "w"), ensure_ascii=False, indent=1)
        m = r.get("matchings") or []
        print(t["name"], "trace:", len(m), "pieces", [round(x["distance"] / 1000, 1) for x in m])


def main():
    if sys.argv[1] == "--trace":
        trace(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
        return
    trips = json.load(open(sys.argv[1]))
    area = sys.argv[2]
    out = sys.argv[3]
    os.makedirs(out, exist_ok=True)
    for t in trips["trips"]:
        if t["area"] != area:
            continue
        pts = t["points"]
        locs = [{"lat": pts[0][0], "lon": pts[0][1], "street_side_tolerance": 5}] + \
               [{"lat": p[0], "lon": p[1], "type": "break"} for p in pts[1:]]
        body = {
            "format": "osrm",
            "filters": {"action": "include", "attributes": [
                "shape_attributes.speed", "shape_attributes.speed_limit", "shape_attributes.time", "shape_attributes.length"]},
            "banner_instructions": True,
            "voice_instructions": True,
            "costing": "truck",
            "locations": locs,
            "costing_options": {"truck": TRUCK},
            "language": "it-IT",
            "units": "kilometers",
            "directions_type": "instructions",
        }
        route = post("route", body)
        json.dump(route, open(os.path.join(out, t["name"] + ".route.json"), "w"), ensure_ascii=False, indent=1)
        geom = None
        try:
            geom = route["routes"][0]["geometry"]
        except (KeyError, IndexError, TypeError):
            print(t["name"], "no route:", json.dumps(route)[:300])
            continue
        attrs = post("trace_attributes", {
            "encoded_polyline": geom, "shape_match": "edge_walk", "costing": "truck",
            "costing_options": {"truck": TRUCK},
            "filters": {"action": "include", "attributes": ATTRS},
        })
        json.dump(attrs, open(os.path.join(out, t["name"] + ".attr.json"), "w"), ensure_ascii=False, indent=1)
        r0 = route["routes"][0]
        steps = [s for leg in r0["legs"] for s in leg["steps"]]
        print(f"{t['name']}: {r0['distance'] / 1000:.1f} km, {r0['duration'] / 60:.0f} min, {len(steps)} steps, "
              f"{len(attrs.get('edges', []))} edges")


if __name__ == "__main__":
    main()
