#!/usr/bin/env bash
# A region's OpenStreetMap extract (Geofabrik region id, e.g. czech-republic) into OUT, from the first
# source that gives a valid file:
#   1. Geofabrik (download.geofabrik.de), the usual one;
#   2. geo2day.com (formerly osmtoday), daily extracts cut like Geofabrik's for 42 of the 48 countries; the
#      regions it has no file for are cut from a bigger one with Geofabrik's outline (data/outlines.geojson);
#   3. download.openstreetmap.fr (a few western countries only);
#   4. Geofabrik's whole Europe on the GWDG mirror (ftp5.gwdg.de, 35 GB), cut with the outline.
# On 2026-11-20 Geofabrik answered every file with a redirect loop (/europe/x/x/x/...) and openstreetmap.fr
# has no Belarus, Latvia, Kosovo or Macedonia: the builds of those countries stopped.
# Usage: osm_extract.sh <region> <out.pbf>   (needs curl, osmium, python3)
set -uo pipefail

REGION=$1
OUT=$2
HERE=$(cd "$(dirname "$0")" && pwd)
OUTLINES=$HERE/outlines.geojson
GEOFABRIK=${GEOFABRIK:-https://download.geofabrik.de/europe}
G2D=https://geo2day.com/europe
OSMFR=https://download.openstreetmap.fr/extracts/europe
GWDG=https://ftp5.gwdg.de/pub/misc/openstreetmap/download.geofabrik.de/europe-latest.osm.pbf
OUT_DIR=$(cd "$(dirname "$OUT")" && pwd) || { echo "::error::folder of $OUT missing"; exit 1; }
WORK=$OUT_DIR/osm-src-$$
mkdir -p "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT

# --max-redirs: a redirect loop fails in a second instead of 50 redirects per try; --speed-*: a transfer
# stalled for two minutes is dropped (and retried) instead of hanging until the job's timeout
CURL=(curl -fsSL --max-redirs 5 --connect-timeout 30 --speed-limit 100000 --speed-time 120)

md5_of_url() {  # md5-url: the published checksum, empty when there is none
  [ -n "$1" ] || return 0
  curl -fsSL --max-redirs 5 --max-time 60 "$1" 2>/dev/null | awk '{print $1; exit}'
}

# the md5 is read before and after the download: a source rebuilding its file meanwhile (geo2day in the
# morning, GWDG near midnight) publishes a new checksum, and the file matches one of the two
fetch() {  # url file md5-url [tries]
  local before
  before=$(md5_of_url "$3")
  "${CURL[@]}" --retry "${4:-3}" --retry-delay 30 --retry-all-errors --retry-connrefused -o "$2" "$1" || return 1
  valid "$2" "$3" "$before"
}

# the file is whole when the source publishes an md5 and it matches (the one read before the download or
# the one after); without one (a file made here: merged, cut), when osmium reads its header
valid() {  # file [md5-url [md5-before]]
  [ -s "$1" ] || return 1
  osmium fileinfo "$1" >/dev/null 2>&1 || { echo "  $(basename "$1"): not a readable PBF"; return 1; }
  local after before=${3:-} got
  after=$(md5_of_url "${2:-}")
  [[ "$after" =~ ^[0-9a-f]{32}$ ]] || [[ "$before" =~ ^[0-9a-f]{32}$ ]] || return 0
  got=$( (md5sum "$1" 2>/dev/null || md5 -r "$1") | awk '{print $1}')
  [ "$got" = "$after" ] || [ "$got" = "$before" ] || { echo "  $(basename "$1"): md5 $got, published $after (before: $before)"; return 1; }
}

outline() {  # file: the region's outline as one GeoJSON Feature, for osmium extract -p
  python3 - "$OUTLINES" "$REGION" "$1" <<'EOF'
import json, sys
src, region, out = sys.argv[1:]
for f in json.load(open(src, encoding="utf-8"))["features"]:
    if f["properties"]["id"] == region:
        json.dump({"type": "Feature", "properties": {}, "geometry": f["geometry"]}, open(out, "w"))
        sys.exit(0)
sys.exit(f"no outline for {region} in {src}")
EOF
}

cut_out() {  # source.pbf out.pbf: the region's part of a bigger file
  outline "$WORK/outline.geojson" || return 1
  osmium extract -p "$WORK/outline.geojson" -s complete_ways "$1" -o "$2" --overwrite
}

done_with() {  # file source
  mv -f "$1" "$OUT"
  echo "OSM extract of $REGION from $2: $(( $(stat -c%s "$OUT" 2>/dev/null || stat -f%z "$OUT") / 1000000 )) MB"
  osmium fileinfo -g header.option.osmosis_replication_timestamp "$OUT" 2>/dev/null | sed 's/^/  data up to /'
  exit 0
}

# 1. Geofabrik
echo "Geofabrik: $REGION"
if fetch "$GEOFABRIK/$REGION-latest.osm.pbf" "$WORK/g.pbf" "$GEOFABRIK/$REGION-latest.osm.pbf.md5" 4; then
  done_with "$WORK/g.pbf" Geofabrik
fi

# 2. geo2day: its file names, and what to cut from where it has none
#    (its "ireland" is the Republic only, its "united_kingdom" has Northern Ireland but no Isle of Man)
G2D_CUT=0
case "$REGION" in
  czech-republic) G2D_FILES="czech_republic" ;;
  guernsey-jersey) G2D_FILES="guernesey jersey" ;;
  great-britain) G2D_FILES="united_kingdom"; G2D_CUT=1 ;;
  ireland-and-northern-ireland) G2D_FILES="ireland united_kingdom"; G2D_CUT=1 ;;
  faroe-islands|isle-of-man|kosovo) G2D_FILES="" ;;
  *) G2D_FILES="$REGION" ;;
esac
if [ -n "$G2D_FILES" ]; then
  echo "geo2day: $G2D_FILES"
  # (its <name>.md5 files stopped in August 2026: the daily checksum is <name>.pbf.md5)
  ok=1; parts=(); days=()
  for f in $G2D_FILES; do
    if fetch "$G2D/$f.pbf" "$WORK/$f.pbf" "$G2D/$f.pbf.md5" 3; then
      parts+=("$WORK/$f.pbf")
      days+=("$(curl -fsSI --max-time 30 "$G2D/$f.pbf" | tr -d '\r' | awk -F': ' 'tolower($1)=="last-modified" {print $2}' | cut -c1-16)")
    else
      ok=0; break
    fi
  done
  # two files of different days hold different versions of the objects near their common border, and
  # osmium merge keeps both: then not from here
  if [ $ok = 1 ] && [ ${#days[@]} -gt 1 ] && [ "$(printf '%s\n' "${days[@]}" | sort -u | wc -l)" != 1 ]; then
    echo "  pieces of different days (${days[*]}): not merged"; ok=0
  fi
  if [ $ok = 1 ]; then
    if [ ${#parts[@]} = 1 ]; then
      src=${parts[0]}
    else
      src=$WORK/merged.pbf
      osmium merge "${parts[@]}" -o "$src" --overwrite || ok=0
      rm -f "${parts[@]}"
    fi
    if [ $ok = 1 ] && [ $G2D_CUT = 1 ]; then
      cut_out "$src" "$WORK/cut.pbf" && rm -f "$src" && src=$WORK/cut.pbf || ok=0
    fi
    [ $ok = 1 ] && valid "$src" && done_with "$src" geo2day.com
  fi
  rm -f "$WORK"/*.pbf
fi

# 3. openstreetmap.fr
FR=$(echo "$REGION" | tr - _)
echo "openstreetmap.fr: $FR"
if fetch "$OSMFR/$FR-latest.osm.pbf" "$WORK/fr.pbf" "$OSMFR/$FR.osm.pbf.md5" 2; then
  done_with "$WORK/fr.pbf" openstreetmap.fr
fi
rm -f "$WORK/fr.pbf"

# 4. Europe from the GWDG mirror, cut with the outline: on the disk with the most room when 40 GB are
#    free there (ways crossing the border kept whole), else read once from the network (ways cut at it)
echo "GWDG mirror: Europe, cut to $REGION"
outline "$WORK/outline.geojson" || exit 1
best=$WORK; room=$(df -Pk "$WORK" | awk 'NR==2 {print $4}')
if [ -d /mnt ] && [ -w /mnt ]; then
  m=$(df -Pk /mnt | awk 'NR==2 {print $4}')
  [ "$m" -gt "$room" ] && { best=/mnt/osm-europe-$$; mkdir -p "$best"; room=$m; }
fi
if [ "$room" -gt $((40 * 1024 * 1024)) ]; then
  if fetch "$GWDG" "$best/europe.osm.pbf" "$GWDG.md5" 3; then
    osmium extract -p "$WORK/outline.geojson" -s complete_ways "$best/europe.osm.pbf" -o "$WORK/eu.pbf" --overwrite
    rc=$?
    rm -f "$best/europe.osm.pbf"; [ "$best" != "$WORK" ] && rm -rf "$best"
    [ $rc = 0 ] && valid "$WORK/eu.pbf" && done_with "$WORK/eu.pbf" "GWDG (Europe, cut)"
  fi
  rm -f "$best/europe.osm.pbf"; [ "$best" != "$WORK" ] && rm -rf "$best"
  echo "  Europe on the disk failed: read from the network instead"
else
  echo "  $((room / 1024 / 1024)) GB free: Europe read from the network"
fi
for try in 1 2; do
  if "${CURL[@]}" "$GWDG" | osmium extract -p "$WORK/outline.geojson" -s simple -F pbf - -o "$WORK/eu.pbf" --overwrite \
      && valid "$WORK/eu.pbf"; then
    done_with "$WORK/eu.pbf" "GWDG (Europe read from the network, cut)"
  fi
  echo "  read from the network failed (try $try)"
done

echo "::error::no OpenStreetMap extract of $REGION from any source"
exit 1
