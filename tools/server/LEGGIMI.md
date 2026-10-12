# Server NavMaster (prima bozza)

Il calcolo del percorso online di NavMaster su un server nostro, con **lo stesso motore del tablet**:
GraphHopper 11 con le regole di openrouteservice (OrsWeighting), i valichi tra Paesi (GhCross) e la
guida (GhGuide). Il server usa i grafi dei 48 Paesi già pubblicati nelle release `dati-<paese>`, gli
stessi che scaricano i tablet. Non c'è niente da ricostruire.

Il tablet manda punti, misure del mezzo e scelte del viaggio. Riceve la stessa risposta che
calcolerebbe da solo: la risposta OSRM per Ferrostar (manovre, voce, corsie, limiti) e le strade del
percorso per l'analisi (pedaggi, limiti, criticità). Quindi l'app online e offline si comporta allo
stesso modo, senza il limite di 2.000 percorsi al giorno di openrouteservice.

## File

| File | Cosa fa |
|---|---|
| `NmServer.java` | il server (Java, solo JDK + jar di GraphHopper) |
| `update_graphs.py` | scarica e aggiorna i grafi dalle release: solo i file cambiati (sha256 del manifest) |
| `install.sh` | installazione completa su Ubuntu 24.04: Java, servizio, aggiornamento settimanale, HTTPS (Caddy), firewall |
| `add_key.sh` | crea o toglie la chiave di un tablet |

## Server gratuito: Oracle Cloud «Always Free»

È l'unico gratuito per sempre con abbastanza memoria e disco. Secondo la documentazione Oracle di
ottobre 2026 comprende:

- ARM Ampere A1: **2 OCPU e 12 GB di RAM** (prima erano 4 e 24);
- **200 GB** di disco in tutto;
- **10 TB al mese** di traffico in uscita.

Gli altri servizi gratuiti (Google e2-micro, AWS per 12 mesi) hanno 1 GB di RAM, troppo poco.

Spazio necessario: i grafi di tutta Europa sono circa 12 GB compressi (Germania 1,5, Francia 1,5,
Italia 0,9…). Scompattati, insieme ai pacchetti tenuti per gli aggiornamenti, fanno circa 40-50 GB.
Un disco da 150 GB basta.

### Cosa fai tu (una volta sola)

1. **Account**: vai su oracle.com/cloud/free e crea l'account. Chiedono una carta solo per la
   verifica; non c'è addebito finché usi solo risorse «Always Free». La **home region** non si può
   cambiare dopo: scegli *Italy Northwest (Milan)* o *Germany Central (Frankfurt)*.
2. **Macchina**: Compute › Instances › Create instance.
   - Image: **Canonical Ubuntu 24.04** (la versione aarch64 compare scegliendo la shape ARM).
   - Shape: **Ampere › VM.Standard.A1.Flex**, **2 OCPU, 12 GB**. Non mettere di più: oltre il
     gratuito si paga.
   - Networking: rete nuova con **IP pubblico** assegnato.
   - SSH keys: «Generate a key pair» e scarica la chiave privata.
   - Boot volume: «Specify a custom boot volume size» **150 GB** (il gratuito arriva a 200 GB in tutto).
   - Se compare «Out of capacity» per A1, riprova più tardi o in un altro Availability Domain.
3. **Porte**: Networking › Virtual cloud networks › la tua rete › Security Lists › Default ›
   Add Ingress Rules: sorgente `0.0.0.0/0`, TCP, porta **80**; poi lo stesso con la porta **443**.
4. **Installazione**: apri la console della macchina (Cloud Shell, oppure
   `ssh -i chiave.key ubuntu@IP`) e lancia:

   ```
   curl -fsSL https://raw.githubusercontent.com/francescobuzle-a11y/navmaster/main/tools/server/install.sh | sudo bash
   ```

   Ci vogliono 30-60 minuti (soprattutto per scaricare i grafi). Alla fine scrive l'indirizzo, del
   tipo `https://140-238-1-2.sslip.io`, con un certificato HTTPS vero (Let's Encrypt, tramite sslip.io).
5. **Chiavi dei tablet**: una per ogni tablet, così se ne perdi uno ne togli solo la chiave:

   ```
   sudo /opt/navmaster/add_key.sh camion1
   ```

   La chiave si vede una volta sola. Il server tiene solo il suo hash.
6. Mandami l'indirizzo del server. Le chiavi tienile tu: andranno nel menu protetto «Chiavi dei
   servizi» dell'app quando aggiungerò la parte del tablet.

Consigliato: in Billing › Budgets crea un budget di 1 € con avviso via email, così te ne accorgi
subito se qualcosa diventa a pagamento.

Nota: Oracle può riprendersi le macchine «Always Free» inattive, cioè quelle che per 7 giorni restano
sotto il 20% di CPU, di rete e (solo A1) di memoria. Il server occupa subito 4 GB di memoria Java
(-Xms = -Xmx, `AlwaysPreTouch`), cioè più del 20% di 12 GB, quindi non risulta inattivo.

### Comandi utili sulla macchina

```
curl https://<indirizzo>/v1/health              # Paesi installati e aperti
journalctl -u navmaster-server -f               # registro del server
sudo systemctl start navmaster-update           # aggiorna subito i grafi (di base ogni lunedì 03:30)
sudo /opt/navmaster/add_key.sh --remove camion1 # toglie una chiave
curl -fsSL .../install.sh | sudo bash           # aggiorna il programma
COUNTRIES=italy,austria,slovakia ... | sudo bash  # solo alcuni Paesi (per una prova veloce)
```

## API

`GET /v1/health`: `{ok, version, started, countries: [...], open: [...]}`

`POST /v1/route`, con l'intestazione `Authorization: Bearer <chiave del tablet>` e `Accept-Encoding: gzip`:

```json
{
  "points": [[45.4846, 10.0566], [48.7363, 19.1462]],
  "kinds": ["break", "break"],
  "headings": [87.0, null],
  "vehicle": {"hgv": true, "vehicleType": "hgv", "heightM": 4, "widthM": 2.55, "lengthM": 16.5,
              "weightT": 40, "axleLoadT": 11.5, "hazmat": false, "tunnelCode": "", "hazmatWater": false,
              "topSpeedKmh": 90, "avoidFerries": false, "avoidUnpaved": true, "preferTruckRoutes": false},
  "trip": {"route": 1, "avoidTolls": false, "shortest": false, "alternatives": 1, "timeoutMs": 0,
           "softIfNone": true, "avoidZones": [[[45.1, 9.1], [45.1, 9.2], [45.2, 9.2]]]},
  "guide": true
}
```

- `route`: 0 più veloce, 1 più autostrada, 2 più corto.
- `kinds`: `via` = punto di passaggio (nessun arrivo).
- `alternatives`: fino a 2, solo per i viaggi dentro un Paese.
- `softIfNone`: se nessuna strada rispetta tutte le misure, dà il percorso che supera meno limiti,
  con `soft: true` e `softWhy` (come il tablet).

Risposta:

```json
{"ok": true, "serverMs": 2400,
 "routes": [{"distanceM": 1075300, "timeMs": 47820000, "computeMs": 1800,
             "countries": ["italy", "austria", "slovakia"], "waypointIndex": [0, 9123],
             "polyline6": "...", "osrm": {...}, "attributes": {...}}]}
```

In caso di errore la risposta è `{"ok": false, "error": "motivo in italiano"}`.

## Prova fatta (computer, 12/10/2026)

Prova con i grafi pubblicati di Italia, Austria e Slovacchia, 700 MB di memoria Java:

- Lograto → Banská Bystrica, camion 40 t, più autostrada: 1075 km, risposta completa con la guida in
  **2,3 s** (3,3 s alla prima richiesta). La risposta pesa 2,1 MB, compressa 0,3 MB.
- Lograto → Bologna con un'alternativa: **0,5 s**.
- 4 richieste insieme (2 calcoli alla volta): 2,5-5,4 s ciascuna.

Sulla macchina Oracle (2 core ARM, grafi di 48 Paesi in 12 GB di RAM) i tempi saranno un po' più
alti, soprattutto per i Paesi non ancora in memoria.

## Da fare dopo

- App: «Server NavMaster» come calcolo online. L'indirizzo e la chiave vanno nel menu «Chiavi dei
  servizi», con «Verifica». OfflineRouteProvider passa prima dal server, poi dal tablet. Il server
  diventa il fornitore online al posto di ORS (`ONLINE_ROUTING`).
- Traffico in tempo reale sul server (code e chiusure come strade evitate o rallentate).
