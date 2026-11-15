#!/usr/bin/env python3
"""
Pacchetto delle allerte per il tablet (autovelox, Tutor, semafori, ZTL, zone ambientali, controlli
camion, dossi, pericoli) dal CSV della cartella «allerts» (autovelox_tutti_paesi.csv: country,
country_name, src_id, lat, lon, type, type_name, maxspeed_kmh, road, direction).

Il file prodotto (allerte-navmaster.sqlite) ha le tabelle di limiti.sqlite (limits + cells, celle
di 0,01°), così la guida lo legge come gli altri dati degli autovelox (LimitsIndex), più le colonne
che servono alla mappa (cc, grp, type, dir, road, lat, lon). Sul tablet si carica da
Impostazioni › Mappa e vista › Allerte sulla mappa, oppure via cavo in
/sdcard/Android/data/app.navmaster.truck/files/allerte/ (AlertPack.kt).

I dati di Radarbot sono proprietari (uso personale): questo script NON va eseguito in CI e il file
NON va pubblicato sulle release pubbliche del repository.

Uso: python3 data/allerte.py <autovelox_tutti_paesi.csv> <uscita.sqlite>
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


def speed_of(text):
    """Il limite controllato: 5–150 km/h a passi di 5, altrimenti 0 (sconosciuto o codice di servizio)."""
    try:
        v = int(float(text))
    except (TypeError, ValueError):
        return 0
    return v if 5 <= v <= 150 and v % 5 == 0 else 0


def read_rows(path):
    """Le righe valide del CSV, ripulite; contatori di quelle scartate."""
    out = []
    dropped = {"tipo": 0, "coordinate": 0, "doppione": 0, "non allerta": 0}
    near = {}
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
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
            d = (r.get("direction") or "").strip()
            out.append({
                "cc": (r.get("country") or "").strip().upper()[:2],
                "id": (r.get("src_id") or "").strip(),
                "lat": lat,
                "lon": lon,
                "type": t,
                "speed": speed_of(r.get("maxspeed_kmh")),
                "road": fix_text((r.get("road") or "").strip())[:80] or None,
                "dir": d if d in ("1", "2", "3", "4") else None,
            })
    return out, dropped


def write_pack(rows, out_path, source_name):
    tmp = out_path + ".new"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.execute("PRAGMA page_size = 4096")
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    db.execute("CREATE TABLE limits(id INTEGER PRIMARY KEY, osm TEXT, kind TEXT, value REAL, raw TEXT, cond TEXT, name TEXT, "
               "pts TEXT, ver TEXT, cc TEXT, grp TEXT, type TEXT, dir TEXT, road TEXT, lat REAL, lon REAL)")
    db.execute("CREATE TABLE cells(cell INTEGER NOT NULL, lid INTEGER NOT NULL)")
    by_cc = {}
    by_grp = {}
    # per Paese, così le righe di un Paese stanno vicine nel file (la mappa legge un Paese alla volta)
    rows.sort(key=lambda r: (r["cc"], r["lat"], r["lon"]))
    for r in rows:
        grp, kind, name = TYPES[r["type"]]
        cur = db.execute(
            "INSERT INTO limits(osm, kind, value, raw, cond, name, pts, ver, cc, grp, type, dir, road, lat, lon) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (f"radarbot:{r['cc']}:{r['id']}", kind, float(r["speed"]), None, None, name,
             f"{r['lat']:.6f},{r['lon']:.6f}", "radarbot", r["cc"], grp, r["type"], r["dir"], r["road"],
             round(r["lat"], 6), round(r["lon"], 6)))
        db.execute("INSERT INTO cells(cell, lid) VALUES (?,?)", (cell_id(r["lat"], r["lon"]), cur.lastrowid))
        by_cc[r["cc"]] = by_cc.get(r["cc"], 0) + 1
        by_grp[grp] = by_grp.get(grp, 0) + 1
    db.execute("CREATE INDEX cells_cell ON cells(cell)")
    db.execute("CREATE INDEX limits_cc ON limits(cc)")
    meta = {
        "format": "navmaster-allerte",
        "version": "1",
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
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    src, out = sys.argv[1], sys.argv[2]
    rows, dropped = read_rows(src)
    by_cc, by_grp = write_pack(rows, out, os.path.basename(src))
    print(f"{len(rows)} allerte in {len(by_cc)} Paesi → {out} ({os.path.getsize(out) / 1e6:.1f} MB)")
    print("scartate:", ", ".join(f"{k} {v}" for k, v in dropped.items()))
    print("per tipo:", ", ".join(f"{k} {v}" for k, v in sorted(by_grp.items(), key=lambda kv: -kv[1])))
    print("Paesi principali:", ", ".join(f"{k} {v}" for k, v in sorted(by_cc.items(), key=lambda kv: -kv[1])[:15]))


if __name__ == "__main__":
    main()
