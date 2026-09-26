#!/bin/bash
# Runs NavMaster on the emulator with the offline test package (Rimini / San Marino) and takes
# screenshots: welcome, map, vehicle editor, route choice with its difficulties, guidance (simulated)
# for the articulated lorry and the camper, portrait layout, settings and the countries.
APKDIR="$1"; DATA="$2"; OUT="$3"
PKG=app.navmaster.truck
mkdir -p "$OUT"
INFO="$OUT/preview_info.txt"
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$INFO"; }
log() { adb logcat -d -s NavMaster:* NavMasterRoute:* NavMasterLimits:* NavMasterVM:* NavMasterData:* NavMasterCrit:* \
  NavMasterAnalysis:* NavMasterJV:* NavMasterLive:* DEBUG:* libc:* Mbgl:* mbgl:* NavMasterCatalog:* NavMasterLocation:* AndroidRuntime:E FerrostarCore:* > "$OUT/logcat.txt" 2>&1; }
start() { adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --es nm_from "44.0587,12.5663" "$@"; }

adb wait-for-device
for i in $(seq 1 30); do adb shell pm path android 2>/dev/null | grep -q package: && break; sleep 3; done
adb shell settings put system screen_off_timeout 1800000
adb root; sleep 3; adb wait-for-device
adb shell "setprop persist.sys.locale it-IT; stop; sleep 2; start"; sleep 25; adb wait-for-device
for i in $(seq 1 30); do adb shell pm path android 2>/dev/null | grep -q package: && break; sleep 3; done
adb shell getprop persist.sys.locale >> "$INFO"

APK=$(ls "$APKDIR"/*.apk | head -1)
adb install -r -g "$APK" >> "$INFO" 2>&1
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
adb shell settings put secure location_mode 3
adb shell settings put secure location_providers_allowed +gps
adb shell settings put secure location_providers_allowed +network

# first start (no maps: welcome), then the offline package is copied in
adb shell am start -n $PKG/.MainActivity; sleep 14
shot 01_benvenuto
adb shell am force-stop $PKG
D=/sdcard/Android/data/$PKG/files/regions/test
adb shell mkdir -p $D
for f in "$DATA"/*; do
  n=$(basename "$f")
  case "$n" in *.part*) continue;; esac
  adb push "$f" $D/ >> "$INFO" 2>&1
done
adb shell ls -la $D >> "$INFO" 2>&1

start; sleep 16; shot 02_mappa_offline
start --es nm_sheet vehicle; sleep 10; shot 03_mezzo
start --es nm_sheet vehicle --ei nm_tab 6; sleep 10; shot 03b_mezzo_sterzata
start --es nm_sheet search; sleep 8; adb shell input text "via"; sleep 1; adb shell input keyevent 62; adb shell input text "flaminia"; sleep 5; shot 04_ricerca
# the same with the tablet upright: the results must stay above the keyboard
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 6
start --es nm_sheet search; sleep 8; adb shell input text "via"; sleep 1; adb shell input keyevent 62; adb shell input text "roma"; sleep 5; shot 04c_ricerca_verticale
# portrait: home, vehicle, route choice (nothing cut or overlapping)
start; sleep 14; shot 02b_mappa_verticale
start --es nm_sheet vehicle; sleep 9; shot 03c_mezzo_verticale
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --ez nm_plan true
sleep 32; shot 05m_scelta_verticale
adb shell settings put system user_rotation 0; sleep 5
# guided search (country > town > street > number), offline
start --es nm_sheet search_guided; sleep 9; shot 04b_ricerca_guidata

# route choice: variants, tolls advice, difficulties
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --ez nm_plan true
sleep 30; shot 05_scelta_percorso
# long press on the map with a route on screen: go / pass here / avoid this zone
adb shell input swipe 1900 700 1900 700 1600; sleep 3; shot 05i_punto_sulla_mappa
# detail of the first difficulty: swept path, satellite, street photos, choices
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --ez nm_plan true --ei nm_crit 0
sleep 40; shot 05b_criticita
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --ez nm_plan true --ei nm_crit 0 --ei nm_tab 1
sleep 40; shot 05b2_criticita_fonti
# tolls on the A14 of the test map. From next to the Rimini Nord booth to Riccione: the motorway
# (booths Rimini Nord and Riccione) or the free roads; the driver accepts 5 minutes more to save it
TOLL="--es nm_from 44.08767,12.46958 --es nm_dest 43.99007,12.64362 --es nm_label Riccione --es nm_profile camion --es nm_load 12 --es nm_tolls ask"
start $TOLL --ei nm_tollmax 5 --ez nm_plan true
sleep 36; shot 05c_pedaggi
# the driver accepts up to 30 minutes more to save the toll: the free road is suggested (and said)
start $TOLL --ei nm_tollmax 30 --ez nm_plan true
sleep 36; shot 05e_pedaggi_consiglio
# onto the motorway (destination on the A14 towards Cattolica): the entry booth right after the start
TOLLIN="--es nm_from 44.08767,12.46958 --es nm_dest 43.96360,12.69205 --es nm_label Cattolica --es nm_profile camion --es nm_load 12 --es nm_tolls allow"
start $TOLLIN --ez nm_plan true
sleep 36; shot 05k_ingresso_piano
start $TOLLIN --ei nm_variant 0 --ez nm_sim true
sleep 14; shot 05d_guida_pedaggio
sleep 12; shot 05h_guida_pedaggio_2
# off the motorway at Riccione (start on the A14, 2 km before the exit): the exit booth, where to pay
TOLLOUT="--es nm_from 43.99500,12.61810 --es nm_dest 43.99007,12.64362 --es nm_label Riccione --es nm_profile camion --es nm_load 12 --es nm_tolls allow"
start $TOLLOUT --ei nm_variant 0 --ez nm_sim true
sleep 16; shot 05f_guida_casello
sleep 16; shot 05g_guida_casello_2
sleep 3; shot 05j_guida_casello_3
# the same exit at night: night palette of the junction view, the gantry drawn behind the signs
start $TOLLOUT --ei nm_variant 0 --ez nm_sim true --es nm_night night
sleep 16; shot 05n_svincolo_notte
sleep 14; shot 05o_svincolo_notte_2
sleep 5; shot 05p_svincolo_notte_3
start --es nm_night auto; sleep 6

# live: a report of "another driver" 3 km ahead, sent and read back through ntfy (test topics, not
# the drivers' ones), the "still there?" question once passed; the report tiles; a detour to
# Riccione with the way back onto the motorway route computed at the start
LIVE="--es nm_live_prefix navmaster-ci-$RANDOM-"
start $TOLLIN --ei nm_variant 0 --ez nm_sim true $LIVE --es nm_report_ahead POLICE:3000 --ez nm_livetest true --es nm_tomtom_key ci-no-key
sleep 24; shot 12_segnalazione_polizia
sleep 24; shot 12c_ancora_li
start $TOLLIN --ei nm_variant 0 --ez nm_sim true $LIVE --es nm_sheet report
sleep 18; shot 12b_segnala
# personal test server (format of the waze-api server used by the JMoore335 script): here a
# fixed sample file served on the runner, reached by the emulator at 10.0.2.2
mkdir -p /tmp/pf/waze
cat > /tmp/pf/waze/traffic-notifications <<'JSON'
{"alerts":[{"country":"IT","numOfThumbsUp":3,"type":"POLICE","subType":"POLICE_VISIBLE","placeNearBy":"A14","latitude":"44.07414","longitude":"12.49037"},
{"country":"IT","numOfThumbsUp":0,"type":"HAZARD","subType":"HAZARD_ON_ROAD_CAR_STOPPED","latitude":"44.06","longitude":"12.55"}],
"jams":[{"severity":3,"type":"NONE","street":"A14","startLatitude":"44.05","startLongitude":"12.56","endLatitude":"44.04","endLongitude":"12.57","delayInSec":240}]}
JSON
(cd /tmp/pf && nohup python3 -m http.server 8088 >/dev/null 2>&1 &)
sleep 2
start $TOLLIN --ei nm_variant 0 --ez nm_sim true --es nm_personal_feed http://10.0.2.2:8088
sleep 22; shot 16_server_personale
start --es nm_personal_feed none; sleep 5
# the same reports asked directly by the tablet (Live Map format), again from a sample file
mkdir -p /tmp/pf/live-map/api
cat > /tmp/pf/live-map/api/georss <<'JSON'
{"alerts":[{"type":"POLICE","subtype":"POLICE_HIDING","location":{"x":12.49037,"y":44.07414},"nThumbsUp":2,"street":"A14","uuid":"t1"},
{"type":"HAZARD","subtype":"HAZARD_ON_SHOULDER_CAR_STOPPED","location":{"x":12.55,"y":44.06},"street":"SS16","uuid":"t2"}],
"jams":[{"level":3,"street":"A14","line":[{"x":12.56,"y":44.05},{"x":12.565,"y":44.045},{"x":12.57,"y":44.04}],"delay":240,"uuid":"t3"}]}
JSON
start $TOLLIN --ei nm_variant 0 --ez nm_sim true --es nm_waze_direct http://10.0.2.2:8088/live-map/api/georss
sleep 22; shot 16b_waze_dal_tablet
start --es nm_personal_feed none; sleep 5
start $TOLLIN --ei nm_variant 0 --ez nm_sim true --es nm_detour 43.99007,12.64362 --es nm_sheet stops
sleep 42; shot 13_deviazione_tappe
# all the places (along the route, nearest first) while driving
start $TOLLIN --ei nm_variant 0 --ez nm_sim true --es nm_sheet pois
sleep 24; shot 14_punti_interesse

# guidance, articulated lorry (places panel: 3 places, closes after 12 s)
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --ei nm_tollmax 5 --ei nm_poicount 3 --ei nm_poisec 12 --ez nm_sim true
sleep 28; shot 06_guida_camion
sleep 3; shot 06b_svolta_dal_vivo
sleep 15; shot 07_guida_camion_2
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 8; shot 08_guida_verticale; sleep 10; shot 08b_guida_verticale_2
adb shell settings put system user_rotation 0; sleep 4
# the same trip seen in 2D, from above
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --es nm_view 2d --ez nm_sim true
sleep 28; shot 07b_guida_2d
# a point long-pressed while driving: go on from there, or go there and back onto the route
adb shell input swipe 500 650 500 650 1600; sleep 3; shot 07c_punto_in_guida

# same trip for the camper: the route may differ where the lorry is not allowed
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camper --es nm_load 0.4 --ei nm_poisec 0 --es nm_view 3d --ez nm_sim true
sleep 28; shot 09_guida_camper

# settings: the list of pages, then the places page (groups, categories, finer choices) and the map page
start --es nm_sheet settings; sleep 8; shot 10_impostazioni
start --es nm_sheet settings_poi; sleep 8; shot 10b_impostazioni_poi
adb shell input swipe 1800 1500 1800 300 600; sleep 3; shot 10c_impostazioni_poi_2
start --es nm_sheet settings_map; sleep 8; shot 10d_impostazioni_mappa
start --es nm_sheet settings_live; sleep 8; shot 10e_impostazioni_traffico
adb shell input swipe 1800 1500 1800 300 600; sleep 3; shot 10f_impostazioni_traffico_2
start --es nm_sheet regions; sleep 12; shot 11_paesi
# traffic colours on the map with a (fake) TomTom key: the app must not stop; then back to normal
start --es nm_tomtom_key ci-no-key --ez nm_traffic_map true; sleep 16; shot 15_traffico_mappa
start --es nm_tomtom_key none --ez nm_traffic_map false; sleep 6
log
grep -E "live|detour|direction of travel|report|personal feed|waze direct|lane signs" "$OUT/logcat.txt" | head -60 >> "$INFO"
grep -E "probe |route computed|scan:|criticalities|Valhalla ready|edges in|variant |advice|toll check|booth|FATAL|Exception" "$OUT/logcat.txt" | head -80 >> "$INFO"
echo done >> "$INFO"
