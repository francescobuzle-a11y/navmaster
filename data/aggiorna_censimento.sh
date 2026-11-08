#!/bin/sh
# Refreshes the copy of Italy's speed camera census (velox.mit.gov.it) that the data build reads
# when the ministry's site does not answer GitHub's servers (data/census_it.py, MIRROR).
# Run it from Italy (the site answers Italian connections), with gh logged in; then run «Dati
# offline» for italy to bring the new list to the tablets.
set -e
tmp=$(mktemp -d)
curl -fsS -m 120 -H "X-Requested-With: XMLHttpRequest" -A "NavMaster data build" \
  "https://velox.mit.gov.it/dispositivi/data?draw=1&start=0&length=-1" -o "$tmp/dispositivi.json"
n=$(python3 -c "import json,sys; print(len(json.load(open(sys.argv[1]))['data']))" "$tmp/dispositivi.json")
[ "$n" -gt 1000 ] || { echo "elenco troppo corto ($n): non caricato"; exit 1; }
repo=francescobuzle-a11y/navmaster
gh release view censimento-autovelox --repo $repo >/dev/null 2>&1 || gh release create censimento-autovelox --repo $repo --latest=false \
  --title "Censimento autovelox (copia)" \
  --notes "Copia dell'elenco nazionale dei dispositivi di rilevamento della velocità del Ministero delle Infrastrutture e dei Trasporti (velox.mit.gov.it/dispositivi), per la costruzione dei dati: il sito non risponde ai server di GitHub."
gh release upload censimento-autovelox "$tmp/dispositivi.json" --repo $repo --clobber
echo "censimento: $n dispositivi caricati ($(date +%Y-%m-%d))"
rm -rf "$tmp"
