#!/usr/bin/env python3
"""
Pacchetto delle allerte per il tablet (autovelox, Tutor, semafori, ZTL, zone ambientali, controlli
camion, dossi, pericoli) dai dati di Radarbot della cartella «allerts». Si possono dare più fonti:
per ogni Paese vale la prima che lo contiene (le altre completano i Paesi mancanti).

Fonti lette:
  - l'esportazione completa record_completo_decodificato.csv (country, db_header, src_id, lat, lon,
    type, base_limit_raw, road, direction, truck_limit_raw, van_limit_raw, bus_limit_raw, ...);
  - i database grezzi di Radarbot radars_<cc>.db (o <cc>.enc), cifrati con +0x10 su ogni byte;
  - il vecchio autovelox_tutti_paesi.csv (country, src_id, lat, lon, type, maxspeed_kmh, road, direction).

Tutti i limiti sono scritti in km/h: i database in miglia (intestazione «EN»: Regno Unito, Stati
Uniti, Myanmar) sono convertiti come fa Radarbot (round(mph × 1,609)); il vecchio CSV non dice
l'unità e per quei Paesi è convertito lo stesso. L'app mostra km/h o mph secondo l'impostazione.

Limiti per tipo di veicolo (colonna veh, «hgv=80;van=70;bus=60», solo dove differiscono dal limite
dell'autovelox): Radarbot campo 10 camion, 21 furgone, 22 autobus. Il 60 dei camion in Austria
sopra un limite più alto è il limite notturno (22–5), che l'app applica già all'ora giusta
(SpeedRules): non viene copiato, altrimenti varrebbe anche di giorno.

Il file prodotto (allerte-navmaster.sqlite) ha le tabelle di limiti.sqlite (limits + cells, celle
di 0,01°), così la guida lo legge come gli altri dati degli autovelox (LimitsIndex), più le colonne
che servono alla mappa (cc, grp, type, dir, road, lat, lon, veh). Sul tablet si carica da
Impostazioni › Mappa e vista › Allerte sulla mappa, oppure via cavo in
/sdcard/Android/data/app.navmaster.truck/files/allerte/ (AlertPack.kt).

I dati di Radarbot sono proprietari (uso personale): questo script NON va eseguito in CI e il file
NON va pubblicato sulle release pubbliche del repository.

Uso: python3 data/allerte.py <fonte> [<altra fonte>...] <uscita.sqlite>
"""
import csv
import datetime
import json
import math
import os
import re
import sqlite3
import sys

CELL = 0.01

# codice Radarbot → (gruppo sulla mappa, tipo per la guida, nome per la guida)
TYPES = {
    "F": ("fisso", "speed_camera", None),
    "FT": ("fisso", "speed_camera", None),
    "U": ("fisso", "speed_camera", None),
    "SC": ("tutor", "speed_camera", "controllo della velocità media"),
    "S": ("semaforo", "speed_camera", "semaforo rosso"),
    "C": ("mobile", "speed_camera_mobile", None),
    "AR": ("ztl", "ztl", "Varco ZTL"),
    "ME": ("lez", "low_emission_zone", None),
    "TT": ("camion", "truck_control", None),
    "CM": ("cinture", "danger", "Controllo cinture e cellulare"),
    "R": ("dosso", "speed_bump", None),
    "RV": ("dosso", "speed_bump", None),
    "SB": ("dosso", "danger", "Strada dissestata"),
    "B": ("dosso", "danger", "Strada dissestata"),
    "CC": ("pericolo", "danger", "Curva pericolosa"),
    "TC": ("pericolo", "danger", "Tornanti"),
    "PN": ("pericolo", "danger", "Punto nero"),
    "AS": ("pericolo", "danger", "Attraversamento animali"),
    "CP": ("pericolo", "danger", "Incrocio pericoloso"),
    "IP": ("pericolo", "danger", "Innesto pericoloso"),
    "ZC": ("pericolo", "danger", "Zona ciclisti"),
    "SV": ("pericolo", "danger", "Uscita con scarsa visibilità"),
}
# ingresso/uscita dal centro abitato, riduzione del limite: non sono allerte
SKIPPED = {"IU", "FU", "RB"}

# testi UTF-8 letti come Latin-1 una o due volte («BrennerstraÃ\x9fe», «TÃºnel»)
MOJIBAKE_RUN = re.compile("[Â-ô][\u0080-¿]{1,3}")


def fix_text(s):
    if not s:
        return s
    for _ in range(2):
        if not MOJIBAKE_RUN.search(s):
            break
        try:
            s = s.encode("latin-1").decode("utf-8")
            continue
        except (UnicodeEncodeError, UnicodeDecodeError):
            pass

        def one(m):
            try:
                return m.group(0).encode("latin-1").decode("utf-8")
            except (UnicodeEncodeError, UnicodeDecodeError):
                return m.group(0)

        s2 = MOJIBAKE_RUN.sub(one, s)
        if s2 == s:
            break
        s = s2
    # caratteri di controllo rimasti
    return "".join(ch for ch in s if ch >= " " or ch == "\t").strip()


def cell_id(lat, lon):
    return int(math.floor(lat / CELL)) * 100000 + int(math.floor(lon / CELL)) + 50000


def dist_m(lat1, lon1, lat2, lon2):
    k = math.cos(math.radians((lat1 + lat2) / 2))
    return math.hypot((lon2 - lon1) * 111195.0 * k, (lat2 - lat1) * 111195.0)


# Paesi i cui dati Radarbot sono in miglia orarie (intestazione «EN»)
MPH_COUNTRIES = {"GB", "US", "MM"}
# i campi di Radarbot con il limite per un tipo di veicolo (pp/i.java, pp/f.java)
VEHICLE_FIELDS = {"hgv": 10, "van": 21, "bus": 22}


def speed_of(text, mph=False):
    """Il limite controllato in km/h (5–200), altrimenti 0 (sconosciuto o codice di servizio)."""
    try:
        v = int(float(text))
    except (TypeError, ValueError):
        return 0
    if mph and v > 0:
        v = round(v * 1.609)
    return v if 5 <= v <= 200 else 0


def vehicle_limits(cc, base, values):
    """«hgv=80;van=70» dai limiti per veicolo (km/h) diversi da quello dell'autovelox, None se nessuno."""
    out = []
    for key, v in values.items():
        if not v or v == base:
            continue
        # il 60 notturno dei camion austriaci: lo applica l'app all'ora giusta
        if cc == "AT" and key == "hgv" and v == 60 and base > 60:
            continue
        out.append(f"{key}={v}")
    return ";".join(out) or None


def records_export(path):
    """Le righe dell'esportazione completa (record_completo_decodificato.csv)."""
    with open(path, newline="", encoding="utf-8-sig") as f:
        for r in csv.DictReader(f):
            head = (r.get("db_header") or "").split(";")
            mph = len(head) > 4 and head[4].strip().upper() == "EN"
            cc = (r.get("country") or "").strip().upper()[:2]
            yield {
                "cc": cc, "id": r.get("src_id"), "lat": r.get("lat"), "lon": r.get("lon"), "type": r.get("type"),
                "speed": speed_of(r.get("base_limit_raw"), mph), "road": r.get("road"), "dir": r.get("direction"),
                "veh": {k: speed_of(r.get(f"{n}_limit_raw"), mph) for k, n in (("hgv", "truck"), ("van", "van"), ("bus", "bus"))},
            }


def records_db(path):
    """Le righe di un database grezzo di Radarbot (radars_<cc>.db: ogni byte +0x10, campi separati da «;»)."""
    data = open(path, "rb").read()
    text = bytes((b - 0x10) & 0xFF for b in data).decode("utf-8", errors="replace")
    lines = text.splitlines()
    head = lines[0].split(";") if lines else []
    if len(head) < 4 or not head[1].strip().isdigit():
        return
    cc = head[0].strip().upper()[:2]
    mph = len(head) > 4 and head[4].strip().upper() == "EN"
    for ln in lines[1:]:
        p = ln.split(";")
        if len(p) < 11:
            continue
        yield {
            "cc": cc, "id": p[0], "lat": p[1], "lon": p[2], "type": p[3], "speed": speed_of(p[4], mph),
            "road": p[5], "dir": p[6],
            "veh": {k: (speed_of(p[i], mph) if len(p) > i else 0) for k, i in VEHICLE_FIELDS.items()},
        }


def records_old_csv(path):
    """Le righe del vecchio autovelox_tutti_paesi.csv (senza unità né limiti per veicolo)."""
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            cc = (r.get("country") or "").strip().upper()[:2]
            yield {
                "cc": cc, "id": r.get("src_id"), "lat": r.get("lat"), "lon": r.get("lon"), "type": r.get("type"),
                "speed": speed_of(r.get("maxspeed_kmh"), cc in MPH_COUNTRIES), "road": r.get("road"),
                "dir": r.get("direction"), "veh": {},
            }


def records(path):
    """Le righe di una fonte, riconosciuta dal nome o dall'intestazione."""
    if path.endswith(".db") or path.endswith(".enc"):
        return records_db(path)
    with open(path, encoding="utf-8-sig") as f:
        header = f.readline()
    return records_export(path) if "base_limit_raw" in header else records_old_csv(path)


def read_rows(paths):
    """Le righe valide delle fonti, ripulite (per Paese la prima fonte che lo ha); contatori di quelle scartate."""
    out = []
    dropped = {"tipo": 0, "coordinate": 0, "doppione": 0, "non allerta": 0, "Paese già preso": 0}
    near = {}
    taken = set()
    for path in paths:
        here = set()
        for r in records(path):
            cc = r["cc"]
            if cc in taken:
                dropped["Paese già preso"] += 1
                continue
            here.add(cc)
            t = (r.get("type") or "").strip()
            if t in SKIPPED:
                dropped["non allerta"] += 1
                continue
            if t not in TYPES:
                # righe spostate di colonna (tipo numerico, lat 4 lon 1): illeggibili
                dropped["tipo"] += 1
                continue
            try:
                lat = float(r["lat"])
                lon = float(r["lon"])
            except (TypeError, ValueError, KeyError):
                dropped["coordinate"] += 1
                continue
            if not (-85 < lat < 85 and -180 <= lon <= 180) or (lat == 4.0 and lon == 1.0):
                dropped["coordinate"] += 1
                continue
            # lo stesso tipo entro 15 m: un doppione
            key = (round(lat * 2000), round(lon * 2000))
            dup = False
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for (la, lo, tt) in near.get((key[0] + dx, key[1] + dy), ()):
                        if tt == t and dist_m(la, lo, lat, lon) < 15:
                            dup = True
            if dup:
                dropped["doppione"] += 1
                continue
            near.setdefault(key, []).append((lat, lon, t))
            d = (r.get("dir") or "").strip()
            out.append({
                "cc": cc,
                "id": (r.get("id") or "").strip(),
                "lat": lat,
                "lon": lon,
                "type": t,
                "speed": r["speed"],
                "veh": vehicle_limits(cc, r["speed"], r.get("veh") or {}) if r["speed"] else None,
                "road": fix_text((r.get("road") or "").strip())[:80] or None,
                "dir": d if d in ("1", "2", "3", "4") else None,
            })
        taken |= here
    return out, dropped


def write_pack(rows, out_path, source_name):
    tmp = out_path + ".new"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.execute("PRAGMA page_size = 4096")
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    db.execute("CREATE TABLE limits(id INTEGER PRIMARY KEY, osm TEXT, kind TEXT, value REAL, raw TEXT, cond TEXT, name TEXT, "
               "pts TEXT, ver TEXT, cc TEXT, grp TEXT, type TEXT, dir TEXT, road TEXT, lat REAL, lon REAL, veh TEXT)")
    db.execute("CREATE TABLE cells(cell INTEGER NOT NULL, lid INTEGER NOT NULL)")
    by_cc = {}
    by_grp = {}
    # per Paese, così le righe di un Paese stanno vicine nel file (la mappa legge un Paese alla volta)
    rows.sort(key=lambda r: (r["cc"], r["lat"], r["lon"]))
    for r in rows:
        grp, kind, name = TYPES[r["type"]]
        cur = db.execute(
            "INSERT INTO limits(osm, kind, value, raw, cond, name, pts, ver, cc, grp, type, dir, road, lat, lon, veh) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (f"radarbot:{r['cc']}:{r['id']}", kind, float(r["speed"]), None, None, name,
             f"{r['lat']:.6f},{r['lon']:.6f}", "radarbot", r["cc"], grp, r["type"], r["dir"], r["road"],
             round(r["lat"], 6), round(r["lon"], 6), r.get("veh")))
        db.execute("INSERT INTO cells(cell, lid) VALUES (?,?)", (cell_id(r["lat"], r["lon"]), cur.lastrowid))
        by_cc[r["cc"]] = by_cc.get(r["cc"], 0) + 1
        by_grp[grp] = by_grp.get(grp, 0) + 1
    db.execute("CREATE INDEX cells_cell ON cells(cell)")
    db.execute("CREATE INDEX limits_cc ON limits(cc)")
    meta = {
        "format": "navmaster-allerte",
        "version": "2",
        "units": "km/h",
        "source": "Radarbot",
        "file": source_name,
        "built": datetime.date.today().isoformat(),
        "count": str(len(rows)),
        "countries": json.dumps(dict(sorted(by_cc.items(), key=lambda kv: -kv[1])), separators=(",", ":")),
        "groups": json.dumps(dict(sorted(by_grp.items(), key=lambda kv: -kv[1])), separators=(",", ":")),
    }
    db.executemany("INSERT INTO meta(key, value) VALUES (?,?)", list(meta.items()))
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, out_path)
    return by_cc, by_grp


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    srcs, out = sys.argv[1:-1], sys.argv[-1]
    rows, dropped = read_rows(srcs)
    names = [os.path.basename(p) for p in srcs]
    by_cc, by_grp = write_pack(rows, out, names[0] + (f" + {len(names) - 1} altre fonti" if len(names) > 1 else ""))
    print(f"{len(rows)} allerte in {len(by_cc)} Paesi → {out} ({os.path.getsize(out) / 1e6:.1f} MB)")
    print("scartate:", ", ".join(f"{k} {v}" for k, v in dropped.items()))
    print("per tipo:", ", ".join(f"{k} {v}" for k, v in sorted(by_grp.items(), key=lambda kv: -kv[1])))
    print("con limiti per veicolo:", sum(1 for r in rows if r.get("veh")))
    print("Paesi principali:", ", ".join(f"{k} {v}" for k, v in sorted(by_cc.items(), key=lambda kv: -kv[1])[:15]))


if __name__ == "__main__":
    main()
