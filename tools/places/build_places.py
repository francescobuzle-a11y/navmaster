#!/usr/bin/env python3
"""Builds luoghi.sqlite: EVERY named place of a country for the search on the tablet, as a driver
expects to find them in Google Maps: companies, warehouses, factories, shops, offices, workshops,
restaurants, hotels, stations, airports, schools, churches, parks, peaks, farms ... from
OpenStreetMap, then the places of Overture Maps (open data, CDLA Permissive 2.0) that
OpenStreetMap does not have. The addresses (streets and house numbers) stay in indirizzi.sqlite,
the places the driver needs along the way (fuel, parkings, areas) in poi.sqlite: this file only
answers "where is <name>".

Input: osmium export -f geojsonseq --add-unique-id=type_id of the extract filtered on the keys of
KEYS (see dati.yml). Usage: build_places.py <in.geojsonseq> <out.sqlite> <region> [--overture]
With --overture the places of Overture Maps are added (DuckDB and the network needed: without
them the file keeps only OpenStreetMap, never fails).

Tables: place(id, src, osm, name, alt, kind, label, lat, lon, city, street, housenumber, phone,
website), place_cells(cell, pid) on the app's 0.01 degree grid, place_fts (fts4: the search form
of name, other names, town and the Italian label of the kind).
"""
import json
import math
import os
import re
import sqlite3
import sys
import time
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "data"))
from nmcommon import cell_id, norm, read_geojsonseq, representative_point  # noqa: E402

# the OSM keys a place is recognised by (the osmium filter of dati.yml uses the same list)
KEYS = ["amenity", "shop", "office", "craft", "tourism", "leisure", "industrial", "man_made", "healthcare", "historic",
        "club", "emergency", "aeroway", "railway", "public_transport", "landuse", "building", "natural", "place", "military",
        "power", "sport", "attraction", "water"]

# tags that are never a place of their own
SKIP_VALUES = {
    "railway": {"rail", "tram", "subway", "light_rail", "narrow_gauge", "abandoned", "disused", "razed", "construction", "platform",
                "switch", "signal", "buffer_stop", "level_crossing", "crossing", "milestone", "rail_brake", "derail", "turntable"},
    "building": {"residential", "house", "apartments", "garage", "garages", "shed", "roof", "hut", "yes", "detached", "terrace",
                 "semidetached_house", "bungalow", "cabin", "dormitory", "farm_auxiliary", "greenhouse", "carport", "construction",
                 "ruins", "barn", "stable", "sty", "cowshed", "static_caravan", "houseboat", "allotment_house", "ger", "service",
                 "transformer_tower", "shelter", "bridge", "toilets", "parking", "boathouse", "container", "tent"},
    "natural": {"tree", "tree_row", "wood", "scrub", "grassland", "heath", "wetland", "water", "coastline", "cliff", "rock",
                "bare_rock", "scree", "sand", "mud", "shingle", "fell", "tundra", "glacier", "valley", "ridge", "arete", "saddle",
                "stone", "shrub", "shrubbery", "strait", "cape", "reef", "shoal", "isthmus", "peninsula", "hill"},
    "landuse": {"residential", "farmland", "forest", "meadow", "grass", "orchard", "vineyard", "greenhouse_horticulture",
                "cemetery", "allotments", "construction", "brownfield", "greenfield", "recreation_ground", "village_green",
                "garages", "railway", "reservoir", "basin", "salt_pond", "plant_nursery", "flowerbed", "aquaculture", "animal_keeping",
                "logging", "conservation", "churchyard", "education", "religious", "winter_sports", "depot", "fallow", "paddy", "yes"},
    "place": {"city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood", "isolated_dwelling", "borough",
              "continent", "country", "state", "region", "province", "district", "county", "municipality", "sea", "ocean",
              "archipelago", "island", "islet", "plot", "city_block", "square", "locality", "allotments"},
    "amenity": {"bench", "waste_basket", "waste_disposal", "recycling", "bicycle_parking", "grit_bin", "vending_machine",
                "drinking_water", "parking_space", "parking_entrance", "motorcycle_parking", "shelter", "telephone", "post_box",
                "hunting_stand", "clock", "loading_dock", "fountain", "water_point", "watering_place", "bbq", "lounger",
                "smoking_area", "dog_toilet", "sanitary_dump_station", "compressed_air", "device_charging_station", "photo_booth",
                "letter_box", "toilets", "atm", "bicycle_repair_station", "give_box", "kneipp_water_cure", "yes"},
    "man_made": {"pipeline", "cutline", "embankment", "dyke", "groyne", "breakwater", "pier", "survey_point", "flagpole",
                 "street_cabinet", "utility_pole", "manhole", "monitoring_station", "petroleum_well", "mast", "antenna",
                 "surveillance", "water_well", "water_tap", "pole", "cairn", "bollard", "clearcut", "goods_conveyor",
                 "snow_fence", "storage_tank", "silo", "chimney", "tower", "wastewater_plant", "water_works", "yes",
                 "bridge", "tunnel", "embankment", "crane", "gasometer", "planter", "quay", "reservoir_covered", "street_lamp"},
    "power": {"line", "minor_line", "pole", "tower", "portal", "cable", "catenary_mast", "insulator", "switch", "transformer",
              "terminal", "compensator", "converter", "busbar", "bay", "generator", "pole", "substation", "yes"},
    "leisure": {"picnic_table", "slipway", "firepit", "bleachers", "outdoor_seating", "bandstand", "track", "pitch"},
    "emergency": {"fire_hydrant", "defibrillator", "siren", "life_ring", "phone", "fire_extinguisher", "access_point",
                  "assembly_point", "water_tank", "suction_point", "fire_water_pond", "lifeguard", "yes"},
    "tourism": {"information", "artwork", "viewpoint", "picnic_site", "yes"},
    "public_transport": {"stop_position", "platform", "stop_area", "stop_area_group"},
    "historic": {"boundary_stone", "milestone", "wayside_cross", "wayside_shrine", "memorial", "tomb", "stone", "yes",
                 "highwater_mark", "cannon", "charcoal_pile", "pa", "vehicle", "aircraft", "railway_car", "ship", "locomotive"},
    "aeroway": {"runway", "taxiway", "apron", "parking_position", "holding_position", "navigationaid", "windsock", "taxilane",
                "gate", "jet_bridge", "stopway", "fuel", "spaceport", "aircraft_crossing", "helipad"},
    "healthcare": set(),
    "water": {"river", "stream", "canal", "ditch", "drain", "lock", "moat", "wastewater", "basin", "reservoir", "pond", "lake",
              "lagoon", "oxbow", "reflecting_pool", "fishpond", "stream_pool", "harbour", "yes"},
    "sport": set(),
}

# the Italian label of a kind (key=value): the main ones; the others get their value spelt out
LABELS = {
    "amenity=restaurant": "ristorante", "amenity=fast_food": "fast food", "amenity=cafe": "bar", "amenity=bar": "bar",
    "amenity=pub": "pub", "amenity=ice_cream": "gelateria", "amenity=food_court": "area ristoro", "amenity=biergarten": "birreria",
    "amenity=fuel": "distributore", "amenity=charging_station": "colonnina di ricarica", "amenity=parking": "parcheggio",
    "amenity=car_wash": "autolavaggio", "amenity=bank": "banca", "amenity=pharmacy": "farmacia", "amenity=hospital": "ospedale",
    "amenity=clinic": "ambulatorio", "amenity=doctors": "medico", "amenity=dentist": "dentista", "amenity=veterinary": "veterinario",
    "amenity=school": "scuola", "amenity=kindergarten": "asilo", "amenity=college": "istituto", "amenity=university": "università",
    "amenity=library": "biblioteca", "amenity=townhall": "municipio", "amenity=police": "polizia", "amenity=fire_station": "vigili del fuoco",
    "amenity=post_office": "ufficio postale", "amenity=courthouse": "tribunale", "amenity=prison": "carcere",
    "amenity=place_of_worship": "chiesa", "amenity=cinema": "cinema", "amenity=theatre": "teatro", "amenity=community_centre": "centro civico",
    "amenity=marketplace": "mercato", "amenity=bus_station": "autostazione", "amenity=ferry_terminal": "terminal traghetti",
    "amenity=taxi": "taxi", "amenity=car_rental": "noleggio auto", "amenity=social_facility": "struttura sociale",
    "amenity=nursing_home": "casa di riposo", "amenity=weighbridge": "pesa", "amenity=waste_transfer_station": "stazione rifiuti",
    "amenity=recycling": "riciclo", "amenity=events_venue": "sala eventi", "amenity=conference_centre": "centro congressi",
    "amenity=driving_school": "autoscuola", "amenity=vehicle_inspection": "revisioni", "amenity=animal_shelter": "canile",
    "shop=supermarket": "supermercato", "shop=convenience": "minimarket", "shop=bakery": "panificio", "shop=butcher": "macelleria",
    "shop=greengrocer": "frutta e verdura", "shop=clothes": "abbigliamento", "shop=shoes": "calzature", "shop=hardware": "ferramenta",
    "shop=doityourself": "fai da te", "shop=furniture": "mobili", "shop=electronics": "elettronica", "shop=mobile_phone": "telefonia",
    "shop=car": "concessionaria", "shop=car_repair": "officina", "shop=car_parts": "ricambi auto", "shop=tyres": "gommista",
    "shop=truck": "concessionaria camion", "shop=truck_repair": "officina camion", "shop=trailer": "rimorchi",
    "shop=agrarian": "agraria", "shop=farm": "azienda agricola", "shop=garden_centre": "vivaio", "shop=florist": "fiorista",
    "shop=kiosk": "edicola", "shop=newsagent": "edicola", "shop=tobacco": "tabaccheria", "shop=hairdresser": "parrucchiere",
    "shop=beauty": "estetista", "shop=optician": "ottico", "shop=jewelry": "gioielleria", "shop=mall": "centro commerciale",
    "shop=department_store": "grande magazzino", "shop=wholesale": "ingrosso", "shop=trade": "rivendita", "shop=caravan": "camper",
    "shop=bicycle": "biciclette", "shop=motorcycle": "moto", "shop=fuel": "carburanti", "shop=gas": "bombole", "shop=pet": "animali",
    "shop=chemist": "profumeria", "shop=alcohol": "enoteca", "shop=wine": "enoteca", "shop=deli": "gastronomia",
    "shop=pastry": "pasticceria", "shop=confectionery": "dolciumi", "shop=seafood": "pescheria", "shop=cheese": "formaggi",
    "office=company": "azienda", "office=logistics": "logistica", "office=transport": "trasporti", "office=insurance": "assicurazioni",
    "office=lawyer": "avvocato", "office=accountant": "commercialista", "office=estate_agent": "agenzia immobiliare",
    "office=government": "ufficio pubblico", "office=it": "informatica", "office=architect": "architetto", "office=engineer": "ingegneria",
    "office=employment_agency": "agenzia per il lavoro", "office=financial": "finanziaria", "office=telecommunication": "telecomunicazioni",
    "office=association": "associazione", "office=ngo": "organizzazione", "office=notary": "notaio", "office=travel_agent": "agenzia viaggi",
    "office=courier": "corriere", "office=moving_company": "traslochi", "office=construction_company": "impresa edile",
    "office=energy_supplier": "energia", "office=consulting": "consulenza", "office=advertising_agency": "pubblicità",
    "craft=carpenter": "falegname", "craft=electrician": "elettricista", "craft=plumber": "idraulico", "craft=metal_construction": "carpenteria metallica",
    "craft=welder": "saldatore", "craft=blacksmith": "fabbro", "craft=painter": "imbianchino", "craft=tailor": "sarto",
    "craft=agricultural_engines": "macchine agricole", "craft=truck_repair": "officina camion", "craft=joiner": "falegnameria",
    "craft=sawmill": "segheria", "craft=winery": "cantina", "craft=brewery": "birrificio", "craft=caterer": "catering",
    "craft=upholsterer": "tappezziere", "craft=hvac": "impianti termici", "craft=glaziery": "vetreria", "craft=stonemason": "marmista",
    "craft=tiler": "piastrellista", "craft=roofer": "lattoniere", "craft=cleaning": "pulizie", "craft=printer": "tipografia",
    "tourism=hotel": "hotel", "tourism=motel": "motel", "tourism=guest_house": "affittacamere", "tourism=hostel": "ostello",
    "tourism=apartment": "appartamenti", "tourism=chalet": "chalet", "tourism=camp_site": "campeggio", "tourism=caravan_site": "area camper",
    "tourism=museum": "museo", "tourism=attraction": "attrazione", "tourism=theme_park": "parco divertimenti", "tourism=zoo": "zoo",
    "tourism=alpine_hut": "rifugio", "tourism=wilderness_hut": "bivacco", "tourism=gallery": "galleria", "tourism=aquarium": "acquario",
    "leisure=park": "parco", "leisure=sports_centre": "centro sportivo", "leisure=stadium": "stadio", "leisure=swimming_pool": "piscina",
    "leisure=fitness_centre": "palestra", "leisure=golf_course": "golf", "leisure=marina": "porto turistico", "leisure=garden": "giardino",
    "leisure=nature_reserve": "riserva naturale", "leisure=playground": "parco giochi", "leisure=water_park": "parco acquatico",
    "leisure=horse_riding": "maneggio", "leisure=ice_rink": "pista di pattinaggio", "leisure=dance": "sala da ballo",
    "industrial=factory": "fabbrica", "industrial=warehouse": "magazzino", "industrial=depot": "deposito", "industrial=port": "porto",
    "industrial=refinery": "raffineria", "industrial=scrap_yard": "demolitore", "industrial=sawmill": "segheria", "industrial=mine": "miniera",
    "industrial=distributor": "distribuzione", "industrial=food_processing": "industria alimentare", "industrial=logistics": "logistica",
    "industrial=oil_mill": "frantoio", "industrial=brickyard": "fornace", "industrial=machine_shop": "officina meccanica",
    "landuse=industrial": "zona industriale", "landuse=commercial": "zona commerciale", "landuse=retail": "zona commerciale",
    "landuse=farmyard": "azienda agricola", "landuse=quarry": "cava", "landuse=port": "porto", "landuse=military": "zona militare",
    "landuse=landfill": "discarica",
    "building=industrial": "capannone", "building=warehouse": "magazzino", "building=commercial": "edificio commerciale",
    "building=office": "uffici", "building=retail": "negozio", "building=supermarket": "supermercato", "building=hotel": "hotel",
    "building=factory": "fabbrica", "building=hangar": "hangar", "building=farm": "fattoria", "building=church": "chiesa",
    "building=school": "scuola", "building=hospital": "ospedale", "building=train_station": "stazione", "building=transportation": "trasporti",
    "building=public": "edificio pubblico", "building=civic": "edificio pubblico", "building=sports_hall": "palazzetto",
    "building=stadium": "stadio", "building=university": "università", "building=kindergarten": "asilo", "building=government": "ufficio pubblico",
    "man_made=works": "stabilimento", "man_made=lighthouse": "faro", "man_made=windmill": "mulino a vento", "man_made=watermill": "mulino",
    "man_made=observatory": "osservatorio", "man_made=mineshaft": "miniera", "man_made=adit": "miniera", "man_made=kiln": "fornace",
    "man_made=water_tower": "torre dell'acqua", "man_made=communications_tower": "torre", "man_made=pumping_station": "stazione di pompaggio",
    "healthcare=hospital": "ospedale", "healthcare=clinic": "ambulatorio", "healthcare=doctor": "medico", "healthcare=dentist": "dentista",
    "healthcare=pharmacy": "farmacia", "healthcare=laboratory": "laboratorio analisi", "healthcare=physiotherapist": "fisioterapista",
    "healthcare=centre": "centro medico", "healthcare=rehabilitation": "riabilitazione", "healthcare=blood_donation": "donazione sangue",
    "historic=castle": "castello", "historic=monument": "monumento", "historic=ruins": "rovine", "historic=archaeological_site": "sito archeologico",
    "historic=church": "chiesa", "historic=monastery": "monastero", "historic=fort": "forte", "historic=city_gate": "porta", "historic=tower": "torre",
    "aeroway=aerodrome": "aeroporto", "aeroway=terminal": "terminal", "aeroway=heliport": "eliporto", "aeroway=hangar": "hangar",
    "railway=station": "stazione", "railway=halt": "fermata ferroviaria", "railway=yard": "scalo merci", "railway=tram_stop": "fermata tram",
    "public_transport=station": "stazione", "natural=peak": "cima", "natural=spring": "sorgente", "natural=beach": "spiaggia",
    "natural=cave_entrance": "grotta", "natural=volcano": "vulcano", "natural=bay": "baia", "natural=waterfall": "cascata",
    "place=farm": "cascina", "military=barracks": "caserma", "military=airfield": "aeroporto militare", "power=plant": "centrale",
    "club=sport": "club sportivo", "emergency=ambulance_station": "ambulanze", "water=harbour": "porto",
}

# Overture categories (the main ones) in Italian; the others get their words spelt out
OVT_LABELS = {
    "shipping_and_logistics": "logistica", "logistics": "logistica", "freight_forwarding": "spedizioni", "trucking_company": "autotrasporti",
    "transportation": "trasporti", "warehouse": "magazzino", "warehousing": "magazzini", "courier_and_delivery_services": "corriere",
    "industrial_company": "industria", "manufacturer": "produzione", "wholesaler": "ingrosso", "distribution_center": "centro distribuzione",
    "professional_services": "servizi professionali", "business_consultant": "consulenza", "construction_company": "impresa edile",
    "automotive_repair": "officina", "auto_repair": "officina", "truck_repair": "officina camion", "tire_shop": "gommista", "tires": "gommista",
    "auto_parts_and_supplies": "ricambi auto", "car_dealer": "concessionaria", "gas_station": "distributore", "fuel_station": "distributore",
    "truck_stop": "area camion", "truck_dealer": "concessionaria camion", "trailer_dealer": "rimorchi", "car_wash": "autolavaggio",
    "restaurant": "ristorante", "italian_restaurant": "ristorante", "pizza_restaurant": "pizzeria", "pizzeria": "pizzeria",
    "fast_food_restaurant": "fast food", "cafe": "bar", "bar": "bar", "coffee_shop": "bar", "bakery": "panificio", "pastry_shop": "pasticceria",
    "ice_cream_shop": "gelateria", "hotel": "hotel", "motel": "motel", "bed_and_breakfast": "b&b", "supermarket": "supermercato",
    "grocery_store": "alimentari", "hypermarket": "ipermercato", "shopping_center": "centro commerciale", "hardware_store": "ferramenta",
    "pharmacy": "farmacia", "hospital": "ospedale", "doctor": "medico", "dentist": "dentista", "veterinarian": "veterinario",
    "bank": "banca", "insurance_agency": "assicurazioni", "real_estate_agency": "agenzia immobiliare", "lawyer": "avvocato",
    "accountant": "commercialista", "school": "scuola", "elementary_school": "scuola", "high_school": "scuola", "university": "università",
    "church": "chiesa", "cemetery": "cimitero", "park": "parco", "gym": "palestra", "sports_club": "club sportivo", "stadium": "stadio",
    "farm": "azienda agricola", "winery": "cantina", "agricultural_cooperative": "cooperativa agricola", "factory": "fabbrica",
    "public_administration": "ufficio pubblico", "post_office": "ufficio postale", "police_station": "polizia", "fire_department": "vigili del fuoco",
    "train_station": "stazione", "airport": "aeroporto", "bus_station": "autostazione", "parking": "parcheggio", "rest_area": "area di sosto",
    "electrician": "elettricista", "plumber": "idraulico", "carpenter": "falegname", "metal_fabricator": "carpenteria metallica",
    "machine_shop": "officina meccanica", "printing_service": "tipografia", "cleaning_service": "pulizie", "landscaping": "giardinaggio",
    "moving_company": "traslochi", "storage_facility": "deposito", "recycling_center": "riciclo", "scrap_metal": "rottami",
    "clothing_store": "abbigliamento", "shoe_store": "calzature", "furniture_store": "mobili", "electronics_store": "elettronica",
    "beauty_salon": "estetista", "hair_salon": "parrucchiere", "jewelry_store": "gioielleria", "florist": "fiorista", "pet_store": "animali",
}

OSM_PLACE_KEYS = ["amenity", "shop", "office", "craft", "tourism", "leisure", "industrial", "healthcare", "historic", "club", "emergency",
                  "aeroway", "railway", "public_transport", "man_made", "military", "power", "sport", "attraction", "water", "natural",
                  "landuse", "building", "place"]


def kind_of(t):
    """The key=value a place is (the most telling tag first), or None when it is no place."""
    for k in OSM_PLACE_KEYS:
        v = t.get(k)
        if not v:
            continue
        v = v.split(";")[0].strip()
        if v in SKIP_VALUES.get(k, set()):
            continue
        if k == "building" and any(t.get(x) for x in ("amenity", "shop", "office", "craft", "tourism", "leisure", "industrial")):
            continue
        return f"{k}={v}"
    return None


def label_of(kind):
    if kind in LABELS:
        return LABELS[kind]
    k, _, v = kind.partition("=")
    if k == "building":
        return "edificio"
    if k == "office":
        return "ufficio " + v.replace("_", " ")
    return v.replace("_", " ")


def ovt_label(cat):
    if not cat:
        return ""
    if cat in OVT_LABELS:
        return OVT_LABELS[cat]
    return cat.replace("_", " ")


def humanize(s):
    return s.replace("_", " ") if s else s


class Writer:
    def __init__(self, db):
        self.db = db
        self.rows, self.cells, self.fts = [], [], []
        self.pid = 0
        # for the Overture merge: (norm first words, rounded cell) of the OSM places
        self.keys = set()

    def add(self, src, osm, name, alt, kind, label, lat, lon, city, street, number, phone, website):
        self.pid += 1
        self.rows.append((self.pid, src, osm, name, alt, kind, label, round(lat, 6), round(lon, 6), city, street, number, phone, website))
        self.cells.append((cell_id(lat, lon), self.pid))
        words = " ".join(w for w in (norm(name), norm(alt), norm(city), norm(label)) if w)
        self.fts.append((self.pid, words))
        self.keys.add(key_of(name, lat, lon))
        if len(self.rows) >= 50000:
            self.flush()

    def flush(self):
        self.db.executemany("INSERT INTO place VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", self.rows)
        self.db.executemany("INSERT INTO place_cells VALUES (?,?)", self.cells)
        self.db.executemany("INSERT INTO place_fts(docid, norm) VALUES (?,?)", self.fts)
        self.rows, self.cells, self.fts = [], [], []


def key_of(name, lat, lon):
    """The first two words of the name and a 100 m cell: two sources naming the same place meet here."""
    w = norm(name).split()
    return (" ".join(w[:2]), round(lat * 1000), round(lon * 1000))


def near_keys(name, lat, lon):
    w = " ".join(norm(name).split()[:2])
    a, b = round(lat * 1000), round(lon * 1000)
    for da in (-1, 0, 1):
        for db_ in (-1, 0, 1):
            yield (w, a + da, b + db_)


def build_osm(src, w):
    n = 0
    skipped = 0
    for f in read_geojsonseq(src):
        t = f.get("properties") or {}
        name = t.get("name") or t.get("brand") or t.get("operator") or t.get("official_name")
        if not name:
            continue
        kind = kind_of(t)
        if kind is None:
            skipped += 1
            continue
        p = representative_point(f.get("geometry") or {"type": "None"})
        if p is None:
            continue
        alt = " ".join(x for x in (t.get("brand"), t.get("alt_name"), t.get("official_name"), t.get("old_name"), t.get("short_name"),
                                   t.get("name:it"), t.get("name:en"), t.get("operator")) if x and x != name)
        w.add("osm", f.get("id"), name, alt or None, kind, label_of(kind), p[1], p[0], t.get("addr:city"), t.get("addr:street"),
              t.get("addr:housenumber"), t.get("phone") or t.get("contact:phone"), t.get("website") or t.get("contact:website"))
        n += 1
    return n, skipped


BUCKET = "https://overturemaps-us-west-2.s3.amazonaws.com"


def latest_release():
    xml = urllib.request.urlopen(f"{BUCKET}/?list-type=2&prefix=release/&delimiter=/", timeout=60).read().decode()
    rel = sorted(re.findall(r"<Prefix>release/([0-9]{4}-[0-9]{2}-[0-9]{2}\.[0-9]+)/</Prefix>", xml))
    if not rel:
        raise RuntimeError("no Overture release found")
    return rel[-1]


def build_overture(db, w, region):
    """Adds every Overture place of the region's box (the box of its OSM places) not in OpenStreetMap."""
    import duckdb  # noqa: F401  (import error = nothing added)
    row = db.execute("SELECT min(lat), max(lat), min(lon), max(lon), count(*) FROM place").fetchone()
    if not row or row[4] == 0:
        return 0
    s, n, we, e = row[0] - 0.02, row[1] + 0.02, row[2] - 0.02, row[3] + 0.02
    rel = latest_release()
    path = f"s3://overturemaps-us-west-2/release/{rel}/theme=places/type=place/*"
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2'; SET enable_progress_bar=false;")
    q = f"""
      SELECT id, names.primary AS name, categories.primary AS cat, bbox.xmin AS lon, bbox.ymin AS lat, confidence,
             addresses[1].locality AS city, addresses[1].freeform AS street, phones[1] AS phone, websites[1] AS website,
             brand.names.primary AS brand
      FROM read_parquet('{path}', hive_partitioning=1)
      WHERE bbox.xmin BETWEEN {we} AND {e} AND bbox.ymin BETWEEN {s} AND {n} AND confidence >= 0.5 AND names.primary IS NOT NULL
    """
    added = 0
    for oid, name, cat, lon, lat, conf, city, street, phone, website, brand in con.execute(q).fetchall():
        if not name:
            continue
        if any(k in w.keys for k in near_keys(name, lat, lon)):
            continue
        kind = "overture=" + (cat or "place")
        w.add("ovt", oid, name, brand if brand and brand != name else None, kind, ovt_label(cat), lat, lon, city, street, None, phone, website)
        added += 1
    return added


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    src, dst, region = args[0], args[1], args[2]
    overture = "--overture" in sys.argv
    db = sqlite3.connect(dst)
    db.executescript("""
        DROP TABLE IF EXISTS meta; DROP TABLE IF EXISTS place; DROP TABLE IF EXISTS place_cells; DROP TABLE IF EXISTS place_fts;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE place(id INTEGER PRIMARY KEY, src TEXT NOT NULL, osm TEXT, name TEXT NOT NULL, alt TEXT, kind TEXT NOT NULL,
                           label TEXT, lat REAL NOT NULL, lon REAL NOT NULL, city TEXT, street TEXT, housenumber TEXT,
                           phone TEXT, website TEXT);
        CREATE TABLE place_cells(cell INTEGER NOT NULL, pid INTEGER NOT NULL);
        CREATE VIRTUAL TABLE place_fts USING fts4(norm);
    """)
    started = time.time()
    w = Writer(db)
    n_osm, skipped = build_osm(src, w)
    w.flush()
    db.commit()
    print(f"luoghi: {n_osm} places from OpenStreetMap ({skipped} named features that are no place) in {time.time() - started:.0f}s")
    n_ovt = 0
    if overture:
        try:
            n_ovt = build_overture(db, w, region)
            w.flush()
            db.commit()
            print(f"luoghi: {n_ovt} places added from Overture Maps in {time.time() - started:.0f}s")
        except Exception as e:  # noqa: BLE001
            print(f"luoghi: Overture Maps left out ({e})")
    db.execute("CREATE INDEX place_cells_cell ON place_cells(cell)")
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("region", region), ("built", time.strftime("%Y-%m-%d")), ("format", "1"), ("osm", str(n_osm)), ("overture", str(n_ovt)),
        ("source", "OpenStreetMap contributors (ODbL)" + ("; Overture Maps Foundation (CDLA Permissive 2.0)" if n_ovt else "")),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"luoghi: {n_osm + n_ovt} places in {time.time() - started:.0f}s, {os.path.getsize(dst) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
