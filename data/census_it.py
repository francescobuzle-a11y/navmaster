#!/usr/bin/env python3
"""Italy's national census of speed cameras (Ministero delle Infrastrutture e dei Trasporti,
https://velox.mit.gov.it/dispositivi, public since 28/11/2025): since 30/11/2025 a device that is
not in it cannot fine anybody. The list gives, for every device, the authority that uses it (its
town as a cadastral code), the kind (fixed, mobile, average speed) and a free note that often says
where it stands ("SS10 Padana Inferiore / Direz. Cremona - Mantova / Km 271+703"), whether it was
switched off ("RILEVATORE SPENTO AI SENSI DEL D.M 125/2026") and, for the Tutor of the Polizia
Stradale, the motorway section between two junctions ("A4 ... TRATTA ROVATO DIR EST (km 200+690) -
OSPITALETTO DIR EST (km 205+550) DIREZIONE EST").

What build_limits.py does with it (only for Italy):
  - a speed camera of OpenStreetMap in a town where nothing at all is registered (by the town, by
    its province or union naming the town, nor any province or union device without a place that
    could stand there) becomes "dubbio", name "non nel censimento del ministero": not announced
    unless the driver asks for the unconfirmed ones;
  - the same where every fixed device registered for the town is switched off ("spento secondo il
    censimento del ministero");
  - a camera in a town with enough active fixed devices registered (at least half as many as the
    cameras OpenStreetMap has there: a camera is often mapped once per direction) becomes "doppio";
  - the Tutor sections of the motorways are placed between the two junctions named (OpenStreetMap
    motorway_junction with that name on that motorway, on the carriageway of the direction given):
    a section of OpenStreetMap with the same ends is confirmed, a missing one is added ("ufficiale").
Cameras on motorways are left as they are (the Polizia Stradale has only Tutor and mobile devices).

The pure functions (parse, coverage, classify, place_tutor) are tested on the computer with
the whole list (see the test at the bottom: python3 census_it.py --self-test list.json).
"""
import json
import math
import re
import unicodedata

URL = "https://velox.mit.gov.it/dispositivi/data?draw=1&start=0&length=-1"

ABSENT = "non nel censimento del ministero"
OFF = "spento secondo il censimento del ministero"
TUTOR = "Tutor (censimento del ministero)"


def fetch(timeout=120):
    """The rows of the list (dicts), or None when it cannot be read."""
    import urllib.request
    req = urllib.request.Request(URL, headers={"User-Agent": "NavMaster data build (github.com/francescobuzle-a11y/navmaster)",
                                               "X-Requested-With": "XMLHttpRequest"})
    try:
        data = json.loads(urllib.request.urlopen(req, timeout=timeout).read())
        rows = data.get("data") or []
        return rows if len(rows) > 100 else None
    except Exception as ex:
        print(f"census it: not downloaded ({ex})")
        return None


def norm(s):
    """Lower case, no accents, only letters and digits separated by one space."""
    import html
    s = unicodedata.normalize("NFKD", html.unescape(s or ""))
    # accents dropped, every other sign a space ("D’ADDA" → "d adda", not "dadda")
    s = "".join("" if unicodedata.category(ch).startswith("M") else (ch if ord(ch) < 128 else " ") for ch in s).lower()
    return " ".join(re.sub(r"[^a-z0-9]+", " ", s).split())


def clean_code(s):
    """A cadastral code ("B898;" → "B898", "c_g920" → "G920"); None when there is none."""
    c = codes_of(s)
    return c[0] if c else None


def codes_of(s):
    """Every cadastral code written in the field ("B819 , I802, B539" → three towns of a union)."""
    if (s or "").upper().startswith("Z2RM"):
        return []
    return list(dict.fromkeys(m.upper() for m in re.findall(r"(?<![A-Za-z0-9])([A-Za-z]\d{3})(?![0-9])", s or "")))


OFF_WORDS = re.compile(r"\b(spent[oi]|disattivat[oi]|dismess[oi]|rimoss[oi]|non (piu )?attiv[oi]|fuori servizio)\b")
PROVINCE_WORDS = re.compile(r"\b(provincia|provinciale|metropolitana)\b")
UNION_WORDS = re.compile(r"\b(unione|intercomunale|aggregazione|associat[ao]|convenzione|consorzio|distretto|comunita montana|gestione associata|comuni)\b")
MOBILE_MODELS = re.compile(r"\b(telelaser|trucam|scout|laser|velocar|mobile|mobili|portatile)\b")


def road_tokens(text):
    """The roads written in a text as {"SP420", "SS10", "SR53"...}: "S.P. 420", "Sp420 Sabbionetana",
    "SPBS 510" (the province's letters dropped), "SP ex SS 343" (both), "Strada Provinciale 6"."""
    t = re.sub(r"[.\-/]", " ", (text or "").upper())
    out = set()
    for m in re.finditer(r"\bS\s?P\s*EX\s*S\s?S\s*0*(\d{1,3})", t):
        out |= {"SP" + m.group(1), "SS" + m.group(1)}
    for m in re.finditer(r"\bS\s?([SPR])\s*(?:(?!EX)[A-Z]{2}\s*)?0*(\d{1,3})(?!\d)", t):
        out.add("S" + m.group(1) + m.group(2))
    for m in re.finditer(r"\bSTRADA\s+(PROVINCIALE|STATALE|REGIONALE)\s*(?:N\s*)?0*(\d{1,3})(?!\d)", t):
        out.add("S" + {"PROVINCIALE": "P", "STATALE": "S", "REGIONALE": "R"}[m.group(1)] + m.group(2))
    return out


def motorway_tokens(ref):
    """{"A4"} of a motorway ref ("A4;E64;E70")."""
    return {"A" + m.group(1) for m in re.finditer(r"\bA\s?(\d{1,2}[A-Z]?)\b", (ref or "").upper())}


def kind_of(r):
    """'average', 'fixed', 'both' (fixed or mobile), 'mobile' or 'unknown' (an instant detector of either kind)."""
    m = (r.get("matricola_dispositivo") or "").upper()
    if m.startswith("PS_TUTOR"):
        return "average"
    if m.startswith("PS_VELOCAR"):
        return "mobile"
    t = norm(" ".join(r.get(k) or "" for k in ("tipo_dispositivo", "modello_dispositivo", "versione_dispositivo")))
    if "media" in t or "tutor" in t or "sicve" in t or "vergilius" in t:
        return "average"
    fixed = bool(re.search(r"\bfiss", t)) or "postazione fissa" in norm(r.get("note") or "")
    mobile = bool(MOBILE_MODELS.search(t))
    if fixed and mobile:
        return "both"
    if fixed:
        return "fixed"
    if mobile:
        return "mobile"
    return "unknown"


def authority_of(r):
    """'stradale', 'province', 'union' or 'town'."""
    d = norm(r.get("denominazione_accertatore"))
    if (r.get("codice_catastale_accertatore") or "").upper().startswith("Z2RM") or "polizia stradale" in d:
        return "stradale"
    if PROVINCE_WORDS.search(d):
        return "province"
    if UNION_WORDS.search(d):
        return "union"
    return "town"


TUTOR_NOTE = re.compile(r"^\s*(A\s?\d+[A-Z]?|RA\s?\d+|T\s?\d+)\s*,.*?\bTRATTA\s+(.+?)\s*\(\s*km\s*([\d+ .,]+)\)\s*-\s*(.+?)\s*\(\s*km\s*([\d+ .,]+)\)\s*DIREZIONE\s+(\w+)",
                        re.I)


def km_of(s):
    """'200+690' → 200.69, '71+000' → 71.0."""
    m = re.match(r"\s*(\d+)\s*[+.,]\s*(\d+)", s or "")
    if m:
        return int(m.group(1)) + int(m.group(2).ljust(3, "0")[:3]) / 1000.0
    m = re.match(r"\s*(\d+)", s or "")
    return float(m.group(1)) if m else None


def tutor_of(note):
    """(road, from name, from km, to name, to km, direction) of a motorway Tutor note, else None."""
    import html
    m = TUTOR_NOTE.search(html.unescape(note or ""))
    if not m:
        return None
    strip = lambda x: re.sub(r"\s+DIR\.?\s+\w+(\s*-\s*\w)?\s*$", "", x.strip(), flags=re.I).strip()
    return (m.group(1).replace(" ", "").upper(), strip(m.group(2)), km_of(m.group(3)), strip(m.group(4)), km_of(m.group(5)),
            m.group(6).upper())


UNTIL = re.compile(r"\bfino\s+al\s+(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})", re.I)
TOWARDS = re.compile(r"\b(dir|direz|direzione|verso)\b")


def expired(note, today=None):
    """A device hired until a day already gone ("noleggio ... fino al 24/09/2026")."""
    import datetime
    m = UNTIL.search(note or "")
    if not m:
        return False
    try:
        return datetime.date(int(m.group(3)), int(m.group(2)), int(m.group(1))) < (today or datetime.date.today())
    except ValueError:
        return False


def parse(rows, today=None):
    """The devices of the list as dicts: code, authority, kind, off, note (normalised; "place": the
    part before the direction, where the town of the device is written), roads, tutor."""
    out = []
    for r in rows:
        note = r.get("note") or ""
        n = norm(note)
        codes = codes_of(r.get("codice_catastale_accertatore"))
        out.append({
            "codes": codes,
            "code": codes[0] if codes else None,
            # where the code field is a text ("Polizia Locale di Fivizzano (MS)"): the town is looked for in it
            "where": norm((r.get("denominazione_accertatore") or "") + " " + (r.get("codice_catastale_accertatore") or "")),
            "authority": authority_of(r),
            "name": norm(r.get("denominazione_accertatore")),
            "kind": kind_of(r),
            "off": bool(OFF_WORDS.search(n)) or expired(note, today),
            "note": n,
            "place": TOWARDS.split(n, 1)[0].strip(),
            "roads": road_tokens(note),
            "tutor": tutor_of(note),
        })
    return out


def coverage(devices, towns):
    """For every town code: the devices registered for it ("own": by the town itself, or by an
    authority of its province that names it in the note) and the area devices that could stand
    there without saying where ("area": the fixed ones of its province, of a union seated within
    15 km). [towns]: code → {"name": normalised name, "prov": ISTAT province code, "lat", "lon"}."""
    by_prov = {}
    for code, t in towns.items():
        by_prov.setdefault(t.get("prov"), []).append((" " + t["name"] + " ", code))
    own = {}
    area_prov = {}
    unions = []
    all_names = [(" " + t["name"] + " ", code) for code, t in towns.items() if len(t["name"]) > 5]
    for d in devices:
        if d["authority"] == "stradale":
            continue
        codes = [c for c in d.get("codes") or ([d["code"]] if d.get("code") else []) if c in towns]
        if not codes:
            # no code that is a town: the town named in the authority's name or code field
            text = " " + d.get("where", "") + " "
            codes = [code for name, code in all_names if name in text][:3]
        if not codes:
            continue
        seat = towns[codes[0]]
        prov = seat.get("prov")
        named = set()
        if prov is not None and d["authority"] in ("province", "union"):
            text = " " + d.get("place", d["note"]) + " "
            named = {code for name, code in by_prov.get(prov, []) if len(name) > 5 and name in text}
        if d["authority"] == "town" or not named:
            for c in codes:
                own.setdefault(c, []).append(d)
        for code in named:
            own.setdefault(code, []).append(d)
        if not named and d["kind"] != "mobile" and len(codes) == 1:
            if d["authority"] == "province":
                area_prov.setdefault(prov, []).append(d)
            elif d["authority"] == "union":
                unions.append((seat["lat"], seat["lon"], d))
    area = {}
    for code, t in towns.items():
        a = list(area_prov.get(t.get("prov"), []))
        for la, lo, d in unions:
            if abs(la - t["lat"]) < 0.2 and abs(lo - t["lon"]) < 0.3 and _km(la, lo, t["lat"], t["lon"]) <= 15:
                a.append(d)
        if a:
            area[code] = a
    return own, area


def _km(la1, lo1, la2, lo2):
    k = math.cos(math.radians((la1 + la2) / 2))
    return math.hypot((lo2 - lo1) * 111.32 * k, (la2 - la1) * 110.54)


def classify(cams_by_town, own, area, roads=None):
    """{camera id: (ver, name or None)} for the cameras of the towns ([cams_by_town]: code → [id]);
    [roads]: camera id → the roads it stands on ({"SP420"}; empty on a street without number).
    Only what changes is returned (see the top of the file). A device whose note names roads
    concerns only the cameras on those roads; a province's device without a place only the
    cameras on provincial roads; a union's device without a place every camera of its towns."""
    roads = roads or {}
    out = {}
    for code, ids in cams_by_town.items():
        rows = own.get(code, [])
        near = area.get(code, [])
        counted = []
        for i in ids:
            r = roads.get(i, set())
            provincial = any(x.startswith("SP") for x in r)
            covered = any(d["authority"] == "union" or provincial or i not in roads for d in near)
            on_road = [d for d in rows if d["roads"] & r]
            if on_road:
                if any(d["kind"] != "mobile" and not d["off"] for d in on_road):
                    out[i] = ("doppio", None)
                elif all(d["off"] for d in on_road if d["kind"] != "mobile") and any(d["kind"] != "mobile" for d in on_road) and not covered:
                    out[i] = ("dubbio", OFF)
                continue
            cand = [d for d in rows if not d["roads"]]
            if not cand and not covered and not any(d["kind"] == "mobile" for d in rows):
                # nothing registered here, or only devices on other roads
                out[i] = ("dubbio", ABSENT)
                continue
            fixedish = [d for d in cand if d["kind"] != "mobile"]
            if fixedish and not any(not d["off"] for d in fixedish) and not covered:
                out[i] = ("dubbio", OFF)
                continue
            counted.append((i, [d for d in fixedish if not d["off"] and d["kind"] in ("fixed", "both", "average")]))
        # the cameras no road tells about: confirmed when the town has enough active fixed devices
        if counted:
            sure = max((len(x[1]) for x in counted), default=0)
            if sure and len(counted) <= 2 * sure:
                out.update({i: ("doppio", None) for i, _ in counted})
    return out


def _bearing(a, b):
    k = math.cos(math.radians((a[0] + b[0]) / 2))
    return math.degrees(math.atan2((b[1] - a[1]) * k, b[0] - a[0])) % 360


def _angle(a, b):
    d = abs(a - b) % 360
    return 360 - d if d > 180 else d


# the junctions the Polizia Stradale calls by an older or shorter name than the motorway signs
JUNCTION_ALIASES = {"piacenza nord": ("basso lodigiano",), "milano sud": ("san donato", "melegnano"),
                    "casale": ("casalpusterlengo",), "milano est": ("linate",)}


def _same_name(osm_name, mit_name):
    """A junction of OpenStreetMap ([osm_name]) is the one the list names: the same words, one the
    beginning of the other ("Piacenza Sud - A21"), a short name of five letters or more that starts
    the first word ("CASALE" / "Casalpusterlengo"; the distance between the ends rules out the
    namesakes), or a known other name."""
    a, b = norm(osm_name), norm(mit_name)
    if not a or not b:
        return False
    if a == b or a.startswith(b + " ") or b.startswith(a + " "):
        return True
    if len(b) >= 5 and " " not in b and a.split(" ")[0].startswith(b):
        return True
    return any(a == x or a.startswith(x + " ") for x in JUNCTION_ALIASES.get(b, ()))


def place_tutor(t, junctions):
    """The ends ((lat, lon), (lat, lon)) of a Tutor ([t] from tutor_of) between the junctions of
    its motorway, on the carriageway of its direction, or None. [junctions]: [(lat, lon, name,
    refs (set of "A4"...), bearing of the carriageway at the junction)]."""
    road, a_name, a_km, b_name, b_km, _ = t
    on_road = [j for j in junctions if road in j[3]]
    starts = [j for j in on_road if _same_name(j[2], a_name)]
    ends = [j for j in on_road if _same_name(j[2], b_name)]
    want = abs(b_km - a_km) if a_km is not None and b_km is not None else None
    best = None
    for a in starts:
        for b in ends:
            d = _km(a[0], a[1], b[0], b[1])
            if d < 0.3 or (want is not None and not (0.4 * want - 1 <= d <= 1.3 * want + 2)):
                continue
            ab = _bearing(a, b)
            # both junctions on the carriageway that goes from the first to the second
            off = max(_angle(a[4], ab), _angle(b[4], ab)) if a[4] is not None and b[4] is not None else 999
            if off > 70:
                continue
            score = off + (abs(d - want) * 5 if want is not None else 0)
            if best is None or score < best[0]:
                best = (score, (a[0], a[1]), (b[0], b[1]))
    return (best[1], best[2]) if best else None


def point_in_rings(lat, lon, rings):
    """Inside a polygon given as rings [[(lon, lat)...]...] (outer and holes): even-odd rule."""
    inside = False
    for ring in rings:
        n = len(ring)
        j = n - 1
        for i in range(n):
            xi, yi = ring[i][0], ring[i][1]
            xj, yj = ring[j][0], ring[j][1]
            if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / ((yj - yi) or 1e-12) + xi:
                inside = not inside
            j = i
    return inside


def polygon_rings(geom):
    """The rings of a GeoJSON Polygon / MultiPolygon, all together (even-odd covers the holes)."""
    if geom.get("type") == "Polygon":
        return [r for r in geom["coordinates"]]
    if geom.get("type") == "MultiPolygon":
        return [r for poly in geom["coordinates"] for r in poly]
    return []


def read_osm(features, cams):
    """From the GeoJSON features of the region (comuni, roads with a number, motorway junctions):
    the towns {code: {name, prov, lat, lon}}, the town of every camera {id: code}, the roads every
    camera stands on {id: {"SP420"}} (empty: a street without number), the cameras on motorways,
    and the junctions [(lat, lon, name, motorways, bearing of the carriageway)].
    [cams]: {id: (lat, lon)}. The features come nodes first (as osmium writes them)."""
    grid = {}
    for i, (la, lo) in cams.items():
        grid.setdefault((math.floor(la * 100), math.floor(lo * 100)), []).append(i)
    coarse = {}
    for i, (la, lo) in cams.items():
        coarse.setdefault((math.floor(la * 10), math.floor(lo * 10)), []).append(i)
    junc = {}
    junctions = []
    towns, cam_town = {}, {}
    best = {}   # camera id -> (metres, roads, motorway)
    for f in features:
        props = f.get("properties") or {}
        geom = f.get("geometry") or {}
        t = geom.get("type")
        if t == "Point":
            if props.get("highway") == "motorway_junction" and props.get("name"):
                lo, la = geom["coordinates"][:2]
                junc[(round(la, 6), round(lo, 6))] = props["name"]
            continue
        if t in ("LineString", "MultiLineString") and props.get("highway"):
            hw = props["highway"]
            mw = hw in ("motorway", "motorway_link")
            refs = road_tokens(props.get("ref")) | (motorway_tokens(props.get("ref")) if mw else set())
            lines = [geom["coordinates"]] if t == "LineString" else geom["coordinates"]
            for line in lines:
                if hw == "motorway" and junc:
                    for k, c in enumerate(line):
                        key = (round(c[1], 6), round(c[0], 6))
                        if key in junc:
                            a, b = (line[k], line[k + 1]) if k + 1 < len(line) else (line[k - 1], line[k])
                            junctions.append((c[1], c[0], junc[key], motorway_tokens(props.get("ref")), _bearing((a[1], a[0]), (b[1], b[0]))))
                for a, b in zip(line, line[1:]):
                    near = set()
                    n = max(1, int(abs(b[1] - a[1]) * 200 + abs(b[0] - a[0]) * 140))
                    for s in range(n + 1):
                        la = a[1] + (b[1] - a[1]) * s / n
                        lo = a[0] + (b[0] - a[0]) * s / n
                        cy, cx = math.floor(la * 100), math.floor(lo * 100)
                        for dy in (-1, 0, 1):
                            for dx in (-1, 0, 1):
                                near.update(grid.get((cy + dy, cx + dx), ()))
                    for i in near:
                        d = _seg_m(cams[i], (a[1], a[0]), (b[1], b[0]))
                        if d <= 30 and (i not in best or d < best[i][0]):
                            best[i] = (d, refs, mw)
            continue
        if t in ("Polygon", "MultiPolygon") and props.get("admin_level") == "8" and props.get("ref:catasto"):
            code = props["ref:catasto"].strip().upper()
            rings = polygon_rings(geom)
            if not rings:
                continue
            xs = [p[0] for r in rings for p in r]
            ys = [p[1] for r in rings for p in r]
            towns[code] = {"name": norm(props.get("name")), "prov": (props.get("ref:ISTAT") or "")[:3] or None,
                           "lat": (min(ys) + max(ys)) / 2, "lon": (min(xs) + max(xs)) / 2}
            for cy in range(math.floor(min(ys) * 10), math.floor(max(ys) * 10) + 1):
                for cx in range(math.floor(min(xs) * 10), math.floor(max(xs) * 10) + 1):
                    for i in coarse.get((cy, cx), ()):
                        la, lo = cams[i]
                        if i not in cam_town and min(ys) <= la <= max(ys) and min(xs) <= lo <= max(xs) and point_in_rings(la, lo, rings):
                            cam_town[i] = code
    roads = {i: (best[i][1] if i in best else set()) for i in cams}
    on_motorway = {i for i, b in best.items() if b[2]}
    return towns, cam_town, roads, on_motorway, junctions


def _seg_m(p, a, b):
    """Metres from p to the segment a-b ((lat, lon) each, flat earth)."""
    k = math.cos(math.radians(p[0]))
    ax, ay = (a[1] - p[1]) * 111320 * k, (a[0] - p[0]) * 110540
    bx, by = (b[1] - p[1]) * 111320 * k, (b[0] - p[0]) * 110540
    dx, dy = bx - ax, by - ay
    l2 = dx * dx + dy * dy
    t = 0.0 if l2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / l2))
    return math.hypot(ax + dx * t, ay + dy * t)


def run(dst, today=None):
    """The census applied to limiti.sqlite [dst], from the region extract region.osm.pbf next to it
    (osmium): see the top of the file. Never stops the build: on any error the file stays as it was."""
    import os
    import sqlite3
    import subprocess
    if not os.path.exists("region.osm.pbf"):
        print("census it: no region.osm.pbf here, skipped")
        return
    rows = fetch()
    if rows is None:
        return
    devices = parse(rows, today)
    db = sqlite3.connect(dst)
    try:
        cams = {}
        for lid, pts, name in db.execute("SELECT id, pts, name FROM limits WHERE kind = 'speed_camera'"):
            # red light, level crossing, average speed devices: not what the census lists one by one
            if name and norm(name).startswith(("semaforo", "passaggio", "controllo", "distanza")):
                continue
            try:
                la, lo = (float(x) for x in pts.split(";")[0].split(","))
            except (ValueError, AttributeError):
                continue
            cams[lid] = (la, lo)
        subprocess.run(["osmium", "tags-filter", "region.osm.pbf", "n/highway=motorway_junction", "w/ref", "r/admin_level=8",
                        "-o", "census.osm.pbf", "--overwrite"], check=True)
        subprocess.run(["osmium", "export", "census.osm.pbf", "-f", "geojsonseq", "--add-unique-id=type_id",
                        "--format-option", "print_record_separator=false", "-o", "census.geojsonseq", "--overwrite"], check=True)

        def features():
            with open("census.geojsonseq", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip().lstrip("\x1e")
                    if line:
                        yield json.loads(line)
        towns, cam_town, roads, on_motorway, junctions = read_osm(features(), cams)
        own, area = coverage(devices, towns)
        by_town = {}
        for i, code in cam_town.items():
            if i not in on_motorway:
                by_town.setdefault(code, []).append(i)
        result = classify(by_town, own, area, roads)
        for i, (ver, name) in result.items():
            if ver == "dubbio":
                db.execute("UPDATE limits SET ver = 'dubbio', name = ? WHERE id = ?", (name, i))
            elif ver == "doppio":
                db.execute("UPDATE limits SET ver = 'doppio' WHERE id = ? AND ver <> 'dubbio'", (i,))
        added, confirmed = tutors(db, devices, junctions)
        counts = {"census_devices": len(devices), "census_towns": len(towns), "census_cameras": len(cam_town),
                  "census_confirmed": sum(1 for v in result.values() if v[0] == "doppio"),
                  "census_absent": sum(1 for v in result.values() if v[1] == ABSENT),
                  "census_off": sum(1 for v in result.values() if v[1] == OFF),
                  "census_tutor_added": added, "census_tutor_confirmed": confirmed, "census_junctions": len(junctions)}
        row = db.execute("SELECT value FROM meta WHERE key = 'checks'").fetchone()
        checks = json.loads(row[0]) if row else {}
        checks.update(counts)
        db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('checks', ?)", (json.dumps(checks),))
        db.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('census', ?)", (time_now(),))
        db.commit()
        print("census it: " + json.dumps(counts))
    except Exception as ex:
        db.rollback()
        print(f"census it: skipped ({ex})")
    finally:
        db.close()
        for f in ("census.osm.pbf", "census.geojsonseq"):
            if os.path.exists(f):
                os.remove(f)


def time_now():
    import time
    return time.strftime("%Y-%m-%d")


def tutors(db, devices, junctions):
    """The Tutor of the motorways placed between their junctions: an OpenStreetMap section with
    the same ends (2.5 km) confirmed, a missing one added. (added, confirmed)."""
    cell = lambda la, lo: int(math.floor(la / 0.01)) * 100000 + int(math.floor(lo / 0.01)) + 50000
    db.executescript("""
        CREATE TABLE IF NOT EXISTS sections(id INTEGER PRIMARY KEY, osm TEXT, kind TEXT, value REAL, hgv REAL, name TEXT,
                                            pts TEXT, ver TEXT);
        CREATE TABLE IF NOT EXISTS section_cells(cell INTEGER NOT NULL, sid INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS section_cells_cell ON section_cells(cell);
    """)
    osm = []
    for sid, pts in db.execute("SELECT id, pts FROM sections"):
        try:
            a, b = [tuple(float(x) for x in p.split(",")) for p in pts.split(";")[:2]]
            osm.append((sid, a, b))
        except (ValueError, AttributeError):
            continue
    seen = set()
    added = confirmed = 0
    for d in devices:
        t = d.get("tutor")
        if not t or (t[0], t[1], t[3], t[5]) in seen or d["off"]:
            continue
        seen.add((t[0], t[1], t[3], t[5]))
        ends = place_tutor(t, junctions)
        if ends is None:
            continue
        a, b = ends
        match = [sid for sid, sa, sb in osm if _km(sa[0], sa[1], a[0], a[1]) <= 2.5 and _km(sb[0], sb[1], b[0], b[1]) <= 2.5]
        if match:
            db.executemany("UPDATE sections SET ver = 'doppio' WHERE id = ?", [(m,) for m in match])
            confirmed += len(match)
            continue
        cur = db.execute("INSERT INTO sections(osm, kind, value, hgv, name, pts, ver) VALUES (?,?,?,?,?,?,?)",
                         ("", "average_speed", 0.0, None, f"{TUTOR} {t[0]} {t[1].title()} - {t[3].title()}",
                          f"{a[0]:.6f},{a[1]:.6f};{b[0]:.6f},{b[1]:.6f}", "ufficiale"))
        for c in {cell(*a), cell(*b)}:
            db.execute("INSERT INTO section_cells(cell, sid) VALUES (?,?)", (c, cur.lastrowid))
        added += 1
    return added, confirmed


if __name__ == "__main__":
    # self-test on the computer: python3 census_it.py --self-test list.json
    import sys
    if len(sys.argv) >= 3 and sys.argv[1] == "--self-test":
        rows = json.load(open(sys.argv[2]))["data"]
        devs = parse(rows)
        from collections import Counter
        print("devices", len(devs), Counter(d["kind"] for d in devs).most_common(), Counter(d["authority"] for d in devs).most_common())
        tut = [d["tutor"] for d in devs if d["tutor"]]
        print("tutor notes", len(tut), tut[:3])
        print("off", sum(d["off"] for d in devs))
        assert km_of("200+690") == 200.69 and km_of("71+000") == 71.0 and km_of("4+95") == 4.95
        assert clean_code("B898;") == "B898" and clean_code("Z2RMP000") is None and clean_code("c_g920") == "G920"
        assert codes_of("B819 , I802, B539 , F966  ") == ["B819", "I802", "B539", "F966"] and codes_of("Bussolengo → B296") == ["B296"]
        assert codes_of("CMLU0014") == [] and codes_of("2706560345") == []
        assert tutor_of("A4, AUTOSTRADA TORINO - TRIESTE TRATTA ROVATO DIR EST (km 200+690) - OSPITALETTO DIR EST (km 205+550) DIREZIONE EST") == \
            ("A4", "ROVATO", 200.69, "OSPITALETTO", 205.55, "EST")
        import datetime
        assert norm("SPINO D’ADDA") == "spino d adda" and norm("Velocità Città") == "velocita citta"
        assert expired("MATR: AP0162K ... FINO AL 24/09/2026", datetime.date(2026, 11, 8)) and not expired("fino al 27/05/2027", datetime.date(2026, 11, 8))
        assert parse([{"note": "SPINO D’ADDA SP415 dir MILANO km 20+300", "codice_catastale_accertatore": "D150"}])[0]["place"] == "spino d adda sp415"
        assert road_tokens("Sp420 Sabbionetana / Direz. Mantova - Parma / Km 13+ 657") == {"SP420"}
        assert road_tokens("SS10 Padana Inferiore / Direz. Cremona - Mantova / Km 271+703") == {"SS10"}
        assert road_tokens("S.S. 343 Asolana 30+677 Casalmaggiore") == {"SS343"}
        assert road_tokens("MARONE (BS), SPBS 510 SEBINA ORIENTALE, km 32+450") == {"SP510"}
        assert road_tokens("SP ex SS 343") == {"SP343", "SS343"} and road_tokens("SP06 Km 004+355") == {"SP6"}
        assert road_tokens("STRADA REGIONALE N.6 22+544 FERRARA") == {"SR6"} and road_tokens("Via De Gasperi") == set()
        assert motorway_tokens("A4;E64;E70") == {"A4"} and motorway_tokens("A1") == {"A1"}
        assert point_in_rings(0.5, 0.5, [[(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)]])
        assert not point_in_rings(0.5, 0.5, [[(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)], [(0.2, 0.2), (0.8, 0.2), (0.8, 0.8), (0.2, 0.8), (0.2, 0.2)]])
        j = [(45.58, 9.95, "Rovato", {"A4"}, 95.0), (45.57, 9.96, "Rovato", {"A4"}, 275.0),
             (45.57, 10.03, "Ospitaletto", {"A4"}, 95.0), (45.56, 10.03, "Ospitaletto", {"A4"}, 275.0)]
        e = place_tutor(("A4", "ROVATO", 200.69, "OSPITALETTO", 205.55, "EST"), j)
        assert e == ((45.58, 9.95), (45.57, 10.03)), e
        w = place_tutor(("A4", "OSPITALETTO", 206.97, "ROVATO", 202.3, "OVEST"), j)
        assert w == ((45.56, 10.03), (45.57, 9.96)), w
        print("ok")
