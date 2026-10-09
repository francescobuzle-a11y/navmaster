# NavMaster — navigatore per mezzi pesanti

App Android per tablet dedicata a **camion, autobus e camper**: calcola percorsi sicuri in base a misure e peso reali del veicolo e funziona **anche senza rete**. Segue il *Documento di progetto* (23/09/2026) e riprende le soluzioni già provate nella versione precedente basata su OsmAnd.

## Come è fatta

| Livello | Componente | Ruolo |
|---|---|---|
| Mappa | MapLibre Native (Compose) | mappa vettoriale, stile NavMaster giorno/notte (`data/style/make_style.py`) |
| Dati mappa | PMTiles generate con Planetiler | mappa offline per regione |
| Calcolo percorso | openrouteservice online (`driving-hgv` / `driving-car`) e GraphHopper 11 sul tablet con le stesse regole di openrouteservice (`tools/gh/OrsRules.java`, `routing/gh/OrsWeighting.java`) | percorsi per camion, autobus e camper con misure e peso del viaggio, anche offline |
| Navigazione | Ferrostar | guida, istruzioni, ricalcolo, uscita dal percorso |
| Voce | Text-to-Speech di Android | istruzioni in italiano |
| Limiti | `limiti.sqlite` (da OSM) | avviso anticipato di sottopassi, ponti, divieti sul percorso |

```
GPS → Ferrostar (guida) → openrouteservice / GraphHopper (percorso, guida da GhGuide) → limiti.sqlite (avvisi)
                  ↘ MapLibre (mappa PMTiles)
```

## Cosa c'è in questa prima versione (Fase 1 – prototipo)

- Mappa offline con stile per mezzi pesanti (strade larghe e gerarchia chiara, numeri di strada sempre leggibili, notte automatica).
- Profili veicolo: autoarticolato, autobus, camper; misure modificabili; **carico del viaggio** impostato alla partenza (peso = tara + carico).
- Preferenze: evita pedaggi, traghetti, sterrate.
- Percorso di openrouteservice quando c'è rete (controllato sul grafo del tablet contro le misure del mezzo), altrimenti calcolato **sul tablet** da GraphHopper con le regole di openrouteservice; il camper usa le regole delle auto con le sue misure. Regole: `claude/ors-logica.md` nel progetto.
- Riepilogo prima della partenza con tutti i limiti di altezza/peso/larghezza/lunghezza e i divieti sul percorso, e se il mezzo passa.
- Guida svolta per svolta con voce italiana, ricalcolo automatico, simulazione.
- Dalla versione NavMaster/OsmAnd: barra verde con distanza, manovra e **lane assist** (frecce di tutte le corsie), cartello dei limiti con il valore nel segnale rotondo e «NON PASSI» in rosso, barra in basso sottile con arrivo/km/minuti, limite di velocità e velocità attuale che lampeggia oltre il limite, mappa inclinata con il mezzo in basso sullo schermo, pulsanti grandi.
- Ricerca indirizzi (online, tollerante agli errori, parole attaccate separate) e coordinate (offline); tieni premuto sulla mappa per scegliere la destinazione.
- Scaricamento del pacchetto Italia dal tablet (preferibilmente in Wi-Fi, con ripresa e controllo di integrità).

## Dati offline

Il workflow **Dati offline** (`.github/workflows/dati.yml`) prepara:

- `mappa.pmtiles` — Planetiler, schema OpenMapTiles, zoom 0–14
- `limiti.sqlite` — `maxheight`, `maxweight`, `maxwidth`, `maxlength`, `maxaxleload`, `hgv`, `hazmat`, `motorhome`, `bus/psv` e divieti a orario (`*:conditional`), indicizzati su una griglia di 0,01°

per **Italia** (`dati-italia`) e per una piccola zona di prova Rimini/San Marino (`dati-test`, usata dall'emulatore). I file oltre 1,9 GB sono divisi in parti; l'app le riunisce. Il grafo GraphHopper di ogni Paese (`gh.tar.gz` + landmark `gh-camion.tar.gz` / `gh-auto.tar.gz`, con `valichi.json` per i viaggi tra Paesi) viene dal workflow **Grafo GraphHopper** (`grafo-gh.yml`).

## Build

Ogni modifica compila l'APK (`App Android`), lo pubblica come release `app-N` e lo prova su un emulatore con i dati di prova (screenshot nella release). Le modifiche arrivano come pacchetto in `inbox/` e vengono scompattate dal workflow `Scompatta pacchetto`.

## Licenze e dati

- Codice NavMaster: © Francesco. Librerie: MapLibre (BSD-2), Ferrostar (BSD-3), GraphHopper (Apache-2.0), regole di calcolo riprese da openrouteservice (LGPL-2.1, HeiGIT) e dal suo fork di GraphHopper (Apache-2.0), parser di ch.poole (MIT), Planetiler (Apache-2.0), Noto Sans (OFL). Percorsi online: © openrouteservice.org by HeiGIT.
- Dati: © OpenStreetMap contributors (ODbL) — l'attribuzione è sempre visibile sulla mappa; i dati derivati distribuiti vanno attribuiti ed eventualmente condivisi.
- Non si usano dati o grafica di Garmin, iGO, Sygic o Waze.
