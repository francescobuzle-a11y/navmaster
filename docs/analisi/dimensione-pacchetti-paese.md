# Perché i pacchetti per Paese sono così grandi, e come ridurli

**Indagine del 17-18 novembre 2026.** Nessun codice è stato modificato. Le misure vengono dalle release `dati-<paese>` di `francescobuzle-a11y/navmaster` (build del 1° novembre per mappa e ricerca, grafo del motore dell'8 novembre), dai registri CI di `grafo-nm.yml`, da una lettura in streaming dei pacchetti (mai salvati interi sul Mac) e da prove di schema su copie degli SQLite. Ogni proposta è stata stimata leggendo il codice del motore e dell'app e poi verificata da un secondo esame che cercava di confutarla. Infine un controllo di completezza ha tolto i doppi conteggi.

## Esito in breve

La Germania mostra 10,2 GB da scaricare e occupa 13,4 GB sul tablet. Quasi metà si toglie senza cambiare nessun percorso, istruzione, mappa o funzione.

| Germania | oggi | solo misure «sicure» | sicure + «prestazioni» |
|---|---|---|---|
| numero mostrato nell'app | 10,2 GB | 5,7 GB | 4,1 GB |
| scaricato davvero | 9,6 GB | 5,7 GB | 4,1 GB |
| occupato sul tablet | 13,4 GB | 7,9 GB | 6,3 GB |

Per un Paese piccolo come la Slovenia le misure sicure valgono −34 % sul disco e −28 % sul download, perché lì pesa di più la mappa, che si riduce meno.

Le cause principali, in ordine di peso:

1. **Il grafo del motore porta strutture che l'app non legge mai.** Le quattro CH «semplici» (1,4 GB su disco, 0,8 GB scaricati) servono solo a richieste senza divieti di svolta, che l'app non fa mai.
2. **Due profili, due pesi e otto insiemi di landmark.** Le preparazioni di auto e «più corto» valgono metà delle preparazioni. I landmark da soli sono il 39 % del grafo scaricato (1,6 GB), e comprimono male.
3. **I civici tedeschi sono doppi.** Il 47 % delle righe di `houses` ripete la stessa via e lo stesso numero a pochi metri. Con l'indice fanno 1,7 GB su 1,9.
4. **File con molti zeri o ridondanti.** La guida (`guidance`) è 1,1 GB sul tablet ma per il 60 % sono righe vuote. Anche gli SQLite contengono colonne e tabelle che l'app non legge.
5. **Il numero mostrato sbaglia.** L'app conta anche `limiti.sqlite` e `criticita.sqlite`, che non scarica più: 0,65 GB in Germania.

## 1. Da cosa è composto un pacchetto

### File scaricati per Paese (MB)

| file | Germania | Italia | Slovenia | Estonia | Lussemburgo |
|---|---|---|---|---|---|
| `nm.tar.gz` (grafo del motore) | 4 250 | 2 244 | 135 | 80 | 29 |
| `mappa.pmtiles` | 2 582 | 1 404 | 200 | 131 | 28 |
| `indirizzi.sqlite` | 1 916 | 367 | 38 | 40 | 14 |
| `luoghi.sqlite` | 699 | 275 | 16 | 9 | 5 |
| `poi.sqlite` | 122 | 104 | 3 | 2 | 1 |
| `limiti.sqlite` (pubblicato, non scaricato) | 428 | 149 | 7 | 6 | 5 |
| `criticita.sqlite` (pubblicato, non scaricato) | 226 | 150 | 14 | 6 | 1 |
| **mostrato dall'app** | **10 222** | **4 693** | **414** | **274** | **84** |
| **scaricato davvero** | **9 568** | **4 394** | **393** | **262** | **77** |

Sul tablet il grafo viene scompattato e il `.tar.gz` cancellato. La Germania occupa quindi 8 130 MB di grafo, 2 582 di mappa e 2 737 di SQLite: 13 449 MB in tutto.

### Il grafo del motore, per componente

Byte dei file scompattati e gzip misurato file per file. In Germania i due insiemi completi di landmark del camion sono stimati con il rapporto dell'auto, perché un download si è interrotto.

| componente | Germania MB | Germania gzip MB | Slovenia MB | Slovenia gzip MB |
|---|---|---|---|---|
| core: 4 file di shortcut + 4 di livelli | 1 919 | 1 021 | 66,1 | 33,0 |
| CH semplici: 4 + 4 file | 1 416 | 807 | 53,5 | 27,3 |
| landmark: 4 insiemi completi, con indice | 1 193 | 852 | 22,0 | 12,3 |
| `guidance`: dati di guida | 1 126 | 17 | 43,0 | 0,3 |
| landmark «evita pedaggi»: 4 insiemi | 976 | 793 | 21,0 | 14,5 |
| `edges`: archi | 751 | 288 | 28,3 | 9,9 |
| `geometry`: punti intermedi | 366 | 306 | 33,6 | 26,5 |
| `nodes`: nodi | 162 | 136 | 6,3 | 4,9 |
| `location_index` | 86 | 39 | 12,6 | 1,8 |
| `turn_heads` + `turn_costs` | 77 | 2 | 3,1 | 0,0 |
| `names`: nomi e stringhe di guida | 57 | 22 | 1,0 | 0,2 |
| altri | 1 | 0 | 1,1 | 0,0 |
| **totale** | **8 130** | **4 282** | **291,4** | **130,8** |

Il pacchetto pubblicato con un solo gzip del tar pesa 4 250 MB in Germania, quasi come la somma per file.

Cifre utili del grafo tedesco:

| voce | valore |
|---|---|
| nodi | 13 533 037 |
| archi | 15 638 056 |
| punti intermedi (pillar) | 40 968 672 |
| nodi nel core | 13-14 % |
| righe di guida non vuote | 40,1 % |
| archi di classe `track` | 19 %, con il 40 % dei pillar |
| archi «solo auto» | 10 % |

Ogni profilo (`car-ors` per camper e auto, `heavyvehicle` per camion e bus) ha due pesi: il principale e «più corto». Per ognuno c'è una CH, un core, 16 landmark e l'insieme «evita pedaggi». Sono quindi 4 CH, 4 core e 8 insiemi di landmark (`RoutingGraph.prepareCH`).

### La mappa

Germania: 2 582 MB, 256 622 tile, zoom massimo 14.

| zoom | MB | quota |
|---|---|---|
| 14 | 1 668 | 64,6 % |
| 13 | 547 | 21,2 % |
| 12 | 213 | 8,3 % |
| 0-11 | 154 | 5,9 % |

A zoom 14 gli edifici fanno il 33 % dei byte, i boschi e i campi (`landcover`) il 21 %, le strade il 14 % e i nomi delle strade l'11 %. In Slovenia a zoom 14 il `landcover` è il 75 %.

Ogni elemento con un nome porta 5-9 varianti del nome (`name_int`, `name:latin`, `name_de`, `name_en`, `name:en`…), ma lo stile legge solo `name` e `name:it`. Le tile portano anche gli identificativi degli elementi e i dizionari dei POI già tolti da `slim_tiles.py`: nessuno li legge.

### Gli SQLite di ricerca

| Germania | MB | contenuto |
|---|---|---|
| `indirizzi`: tabella `houses` + indice | 1 722 | 37 610 391 civici, di cui 17 527 216 doppioni della stessa via e numero |
| `indirizzi`: vie, località, testo cercabile | 194 | 1 383 615 vie |
| `luoghi`: tabella `place` | 379 | 2 850 303 luoghi, tutti OSM: Overture manca per un errore dello script |
| `luoghi`: testo cercabile (FTS4) | 236 | |
| `luoghi`: griglia `place_cells` | 84 | nessuna parte dell'app la interroga |
| `poi` | 122 | |

Il 97 % dei civici doppi sta entro 30 m dall'altro: è lo stesso edificio mappato due volte, tipico delle importazioni catastali tedesche. L'app oggi li scarta comunque e ne mostra il primo (`AddressIndex.houses`, `distinctBy`).

## 2. Proposte ordinate per guadagno e rischio

Le categorie sono quattro:

- **Sicura:** nessun percorso, istruzione, mappa o funzione cambia. Serve solo lavoro.
- **Prestazioni:** stessi risultati, ma alcune ricerche sul tablet sono più lente.
- **Cambia-percorsi:** i percorsi possono cambiare, anche di poco.
- **Cambia-funzioni:** una funzione dell'app o la resa della mappa cambia.

I MB sono per la Germania, disco del tablet e download, al netto delle sovrapposizioni con le misure che stanno sopra nella stessa tabella.

### 2.1 Misure sicure

| # | misura | disco MB | download MB | dove |
|---|---|---|---|---|
| S1 | **Civici senza doppioni, una riga per via** con i numeri in un blob e le coordinate come scarto dal punto della via (misurato sulla Germania: 156 MB invece di 1 722) | −1 566 | −1 566 | `build_addresses.py`, `AddressIndex` |
| S2 | **Non pubblicare le CH semplici.** `RoutingGraph.router` le usa solo senza divieti di svolta, che l'app non chiede mai. Il core è un file a parte e non cambia. | −1 416 | −807 | `grafo-nm.yml` (togliere gli 8 file dal tar) o `prepareCH` |
| S3 | **Guida rada:** una bitmap degli archi con dati e record solo per quelli. 60 % delle righe vuote, 26 % con soli campi da un byte. | −920 | 0 | `GuidanceStore` |
| S4 | **Mappa: togliere ciò che nessuno legge.** Dizionari dei POI tolti, identificativi, chiavi dei nomi in altre lingue, nomi di classi mai disegnate, confini sotto il livello 4, deflate migliore. Misurato su un campione di tile: −22 % a zoom 14. | −510 | −510 | `slim_tiles.py` |
| S5 | **Comprimere gli SQLite per il download** (gzip, già nella libreria Java) e scompattarli sul tablet. Valore dopo S1, S7, S8, S9. | 0 | −265 | `dati.yml`, `RegionManager` |
| S6 | **Indice del core nel file dei livelli:** il rango del nodo nel campo livello, così i landmark non portano più il loro indice. | −216 | −10 | `CHPreparation`, `CHRouting`, `CoreLandmarks` |
| S7 | **Testo cercabile FTS4 senza copia del testo** (`content=""`). Misurato: stessi risultati su 600 ricerche e sui termini vicini. | −210 | −210 | i tre script SQLite |
| S8 | **Luoghi:** via `osm`, `src`, `label`, telefono e sito (mai mostrati), tipo come intero, coordinate intere. | −201 | −201 | `build_places.py`, `PlaceIndex` |
| S9 | **Geometria a scarti varint**, valori identici al bit. Serve una lettura in blocco, non byte per byte. | −170 | −135 | `BaseGraph` |
| S10 | **Luoghi senza la griglia `place_cells`**, mai interrogata. | −84 | −84 | `build_places.py` |
| S11 | **`turn_heads` come bitmap**: il 99,4 % delle voci è vuoto. Più veloce sul tablet: un bit in memoria invece di una lettura nativa. | −72 | 0 | `TurnCostStorage` |
| S12 | **Nomi deduplicati davvero:** oggi solo le ultime 1000 stringhe. Formato invariato. | −42 | −16 | `StringStore` |
| S13 | **POI senza `osm` ed `extra`**, mai mostrati. | −42 | −42 | `build_poi.py`, `PoiIndex` |
| S14 | Indice spaziale a scarti, riferimenti al nome radi, gzip -9 | −56 | −54 | vari |
| | **Totale sicure** | **≈ −5 500** | **≈ −3 900** | |

Due misure sicure non cambiano nessun formato e funzionano anche con l'app già sui tablet. Si possono fare subito:

- **S2 nella forma «escludi dal tar»** (−1,4 GB su disco, −0,8 GB scaricati).
- **I civici senza doppioni con il formato di oggi** (−805 MB). Toglie già metà del guadagno di S1.

Altre due correzioni non riducono i byte scaricati, ma rendono giusto il numero mostrato:

- **Smettere di pubblicare `criticita.sqlite` e `limiti.sqlite`.** Nessuna parte viva dell'app li legge. Le criticità vengono dal grafo, e la guida online «leggera» che usava i limiti è spenta in `MainScreen`. Il catalogo perde così −654 MB in Germania.
- **`Catalog.downloadSize`** dovrebbe usare la stessa regola di `RegionManager.skipped`.

Le altre misure sicure cambiano un formato. Serve prima un'app che riconosca il formato nuovo e rifiuti quelli sconosciuti, poi i dati. Oggi `CHStorage.loadExisting` non controlla la versione e il campo `engine` del manifesto non viene letto: conviene un solo salto di formato per tutte.

### 2.2 Misure «prestazioni» (stessi risultati, alcune ricerche più lente)

| # | misura | disco MB | download MB | costo |
|---|---|---|---|---|
| P1 | **«Più corto» senza landmark:** per il peso «più corto» il costo di un arco è la sua lunghezza. La distanza in linea d'aria è un limite esatto e consistente: la ricerca resta esatta, i 4 insiemi di landmark «più corto» spariscono (compresi i 2 «evita pedaggi»). | −1 084 | −810 | Ricerche «più corto» da 2 a 4 volte più nodi nel core (da misurare; la variante ha 15 s). Lettura delle coordinate dei nodi a ogni visita. |
| P2 | **8 landmark invece di 16** sugli insiemi rimasti. Stesso formato, ricerca esatta, limiti più deboli. | −488 | −400 | +30-100 % di nodi esplorati sul peso principale, cioè su tutti i percorsi, i valichi e le varianti. Da misurare sul tablet. |
| P3 | **xz invece di gzip per il grafo**, decompressione in Java sul tablet. | 0 | −200 | Installazione 2-4 minuti più lenta. Il guadagno scende dopo S2 e S9. |
| P4 | **zstd invece di gzip per gli SQLite**, in aggiunta a S5. | 0 | −30 | Una libreria in più. |
| P5 | Vie senza la colonna `norm` (filtro della ricerca guidata in Kotlin). | −34 | −34 | Normalizzazione Python e Kotlin da allineare. |
| | **Totale con le sicure** | **≈ −7 100** | **≈ −5 400** | |

P1 contiene la proposta «evita pedaggi solo per il peso principale» (−488 / −396), che ne è il primo passo.

### 2.3 Scelte che cambiano funzioni (a decisione dell'utente)

I valori sono quelli che restano dopo le misure sicure.

| misura | disco MB | download MB | cosa cambia |
|---|---|---|---|
| **Pacchetto «solo camion»:** le preparazioni dell'auto come aggiunta da scaricare solo se il garage ha un camper o un'auto | −2 149 | −1 410 | Senza l'aggiunta, un camper non ha percorsi offline oltre poche decine di km: la ricerca senza core si ferma al milione di nodi e i valichi danno errore. L'app deve proporre il download. |
| **«Più corto» come aggiunta** | −2 079 (−995 dopo P1) | −1 350 (−540 dopo P1) | Senza l'aggiunta il «più corto» e la sua variante non ci sono offline. |
| **Ricerca offline facoltativa** per Paese (indirizzi e luoghi, POI tenuti) | ≈ −570 (−2 615 oggi) | ≈ −310 | Senza rete niente ricerca di indirizzi e luoghi in quel Paese. Utile per i Paesi di transito. |
| **Mappa leggera senza zoom 14** | −1 300 | −1 300 | Spariscono distributori e parcheggi sulla mappa, edifici reali in 3D, viali di accesso; meno dettaglio alla vista di guida. |
| **Boschi e campi da zoom 13 a zoom alti** | −345 | −345 | Macchie di verde sotto i 100 m assenti, bordi più grezzi. In Slovenia vale circa il 25 % del download. |
| Edifici solo da zoom 14 | −88 | −88 | Isolati in dissolvenza assenti fra zoom 13 e 14. |
| Fossi e canali di scolo tolti | −60 | −60 | Meno linee blu nei campi. |
| Semplificazione più forte a zoom 14 | −130 | −130 | Angoli degli edifici e curve smussati alla vista di guida. |

Con tutte queste scelte, per un tablet solo camion con mappa leggera, la Germania scenderebbe a circa 2,3 GB su disco e 1,5 GB scaricati. È una stima con incertezza di circa ±15 %.

### 2.4 Misure che cambiano i percorsi (sconsigliate)

| misura | disco MB | download MB | perché no |
|---|---|---|---|
| Distanze dei landmark a 16 bit | −976 | −600 | Già provato in memoria e spento: «il percorso può discostarsi un poco dal migliore (visto su Canneto → Innsbruck)». P2 dà lo stesso risparmio restando esatta. |
| Togliere i `track` dal grafo | −470 | −235 | Rompe la parità con ORS per l'auto in campagna e l'ultimo miglio del camion. Toglie anche la strada sotto il mezzo e la vista dell'incrocio su quei tratti. |
| Shortcut senza `skip1/skip2` | −212 | −120 | Con archi paralleli fra gli stessi incroci lo scompattamento può scegliere un altro arco nei pareggi. |
| Rinumerazione spaziale dei nodi | −70 | −60 | I pareggi di peso si rompono per numero di nodo. Il vantaggio vero sarebbe la località sul tablet, da provare con un prototipo. |
| Geometria a 1e-6 | −29 | −25 | Sposta i punti di centimetri: può cambiare un'istruzione al limite o un aggancio. |

### 2.5 Scartate

| misura | motivo |
|---|---|
| zstd per il grafo | Misurato: non comprime meglio di gzip e serve una libreria nativa. |
| File del grafo compressi a blocchi sul tablet | Ogni lettura fuori cache costerebbe 16 pagine di flash più la decompressione: ricerche 5-10 volte più lente. |
| PMTiles con zstd | MapLibre Android legge solo gzip. |
| Un solo SQLite per Paese | Nessun guadagno e romperebbe l'aggiornamento per file. |
| Ordine dei file nel tar | Nessun effetto: il grafo si apre solo a scompattamento finito. |
| FTS5 | Misurata sulla Germania: uguale a FTS4 senza copia del testo, ma serve uno SQLite incluso nell'app. |

## 3. Ordine consigliato

1. **Subito, solo CI e script, nessun cambio di formato.** Sono S2 come esclusione dal tar, i civici senza doppioni nel formato di oggi, e non pubblicare criticità e limiti. Il numero mostrato per la Germania passa da 10,2 a 8,0 GB e il disco scende di 2,2 GB. Le app già installate funzionano.
2. **Un'app che controlla il formato** dei file del grafo e degli SQLite (versione nell'intestazione e campo `format` in `meta`) e rifiuta quelli sconosciuti con un messaggio. Va pubblicata e installata sui tablet prima del passo 3.
3. **Un solo salto di formato** con S1, S3, S4, S5, S6, S7, S8, S9 e S10-S14, con una ricostruzione di tutti i Paesi (grafo circa 3,5 h, dati mensili). La Germania arriva a circa 5,7 GB scaricati e 7,9 GB sul tablet.
4. **Prestazioni, dopo una misura sul tablet:** P1 e poi P2, misurando i tempi su Komjatice e Canneto → Innsbruck con il registro `NavMasterRoute`.
5. **Le scelte del capitolo 2.3**, se l'utente le vuole. Il «solo camion» rende di più ed è naturale con un tablet per autista.

Per ogni passo del motore la verifica è `confronto-ors.yml` (parità con ORS), più `valichi.yml` e `valichi-paesi.yml` sui grafi veri. Attenzione: senza CH semplici il controllo «CH contro Dijkstra» di `CrossBorderRealReferenceTest` confronta Dijkstra con Dijkstra e non controlla più nulla; va adattato. Per gli SQLite: stesse risposte su un elenco fisso di ricerche, prima e dopo. Per la mappa: confronto di screenshot sul tablet e un test in CI che le chiavi lette dallo stile siano tutte fra quelle tenute da `slim_tiles.py`.

## 4. Altre cose trovate

- **Civici diversi nascosti.** Fra i doppioni tedeschi, 190 000 sono case distinte a più di 200 m l'una dall'altra, unite sotto la stessa via, per esempio la «Hauptstraße» di più frazioni dello stesso comune. Oggi l'app ne mostra solo una: è un difetto della ricerca già presente, non introdotto da queste misure.
- **Overture manca nei luoghi.** Tutti i luoghi tedeschi e italiani sono OSM: la build è fallita su uno schema vecchio. Con lo script corretto la prossima build aggiungerà Overture senza filtro di categoria, e `luoghi.sqlite` della Germania potrebbe crescere di 250-500 MB. Va deciso prima quali categorie tenere.
- **I valori ADR non sono ancora nei grafi pubblicati.** I prossimi grafi avranno 6 int di flag per arco invece di 5: +62 MB in Germania.
- **Lo spazio chiesto all'installazione è troppo.** La formula di `RegionManager` chiede 25 GB liberi per la Germania contro un picco reale di 17 GB. Un pacchetto con un file compresso per ogni file del grafo farebbe scendere il picco di altri 4 GB.
- **Gli aggiornamenti mensili riscaricano tutto.** Una differenza per tile della mappa potrebbe far risparmiare 1-1,8 GB al mese in Germania. Prima va tolta l'ora dall'intestazione gzip di ogni tile in `slim_tiles.py`, altrimenti ogni tile cambia a ogni build.

## 5. Cosa non è misurato

- L'effetto di tutte le misure insieme su un grafo intero. Il passo più utile prima di implementare: costruire la Slovenia con le misure sicure del grafo e rifare `ls -la` più gzip.
- xz su landmark e archi, e sul tar intero.
- I tempi sul tablet di P1 e P2: sono le uniche misure che toccano le ricerche.
- Le misure sicure della mappa su Slovenia e Italia, dove i boschi pesano di più.
- La quota di tile invariate fra due build mensili.

## Metodo e file

- Pacchetti letti in streaming da GitHub (`curl | gunzip | python`), mai salvati sul Mac.
- Script e risultati nella cartella della sessione: `nm_analyze.py`, `nm_classes.py`, `pmtiles_analyze.py`, `sqlite_experiments.sh` e i JSON di misura.
- Il riepilogo dei fatti è anche in `dimensione-pacchetti-fatti-misurati.md` nella cartella delle note condivise.
- Registri CI usati: `grafo-nm.yml`, run 42479260799 (Germania, 8 novembre) e 41922638563 (tutti i Paesi, 4 novembre).
