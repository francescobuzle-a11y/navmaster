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
  NavMasterAnalysis:* NavMasterCatalog:* NavMasterLocation:* AndroidRuntime:E FerrostarCore:* > "$OUT/logcat.txt" 2>&1; }
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
# the same trip for the camper (no weight limits): does it enter the motorway at Rimini Nord? (see the log)
start $TOLLIN --es nm_profile camper --es nm_load 0.4 --ez nm_plan true
sleep 36; shot 05l_ingresso_piano_camper
# which stretch stops the lorry? routes between points of the Rimini Nord entry, lorry then camper (log: probe)
PROBE="44.08767,12.46958;44.08690,12.46887;44.08588,12.46840;44.08381,12.46547;43.96360,12.69205"
start --es nm_profile camion --es nm_load 12 --es nm_probe "$PROBE"; sleep 25
start --es nm_profile camion --es nm_load 12 --es nm_probe "44.08767,12.46958;43.96360,12.69205;44.08588,12.46840;43.96360,12.69205;44.08690,12.46887;44.08588,12.46840"; sleep 25
start --es nm_profile camper --es nm_load 0.4 --es nm_probe "$PROBE"; sleep 20
start $TOLLIN --ei nm_variant 0 --ez nm_sim true
sleep 14; shot 05d_guida_pedaggio
sleep 12; shot 05h_guida_pedaggio_2
# off the motorway at Riccione (start on the A14, 2 km before the exit): the exit booth, where to pay
TOLLOUT="--es nm_from 43.99500,12.61810 --es nm_dest 43.99007,12.64362 --es nm_label Riccione --es nm_profile camion --es nm_load 12 --es nm_tolls allow"
start $TOLLOUT --ei nm_variant 0 --ez nm_sim true
sleep 16; shot 05f_guida_casello
sleep 16; shot 05g_guida_casello_2
sleep 3; shot 05j_guida_casello_3

# guidance, articulated lorry (places panel: 3 places, closes after 12 s)
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --ei nm_tollmax 5 --ei nm_poicount 3 --ei nm_poisec 12 --ez nm_sim true
sleep 28; shot 06_guida_camion
sleep 3; shot 06b_svolta_dal_vivo
sleep 15; shot 07_guida_camion_2
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 8; shot 08_guida_verticale
adb shell settings put system user_rotation 0; sleep 4
# the same trip seen in 2D, from above
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camion --es nm_load 12 --es nm_view 2d --ez nm_sim true
sleep 28; shot 07b_guida_2d

# same trip for the camper: the route may differ where the lorry is not allowed
start --es nm_dest "43.9360,12.4460" --es nm_label "'San Marino'" --es nm_profile camper --es nm_load 0.4 --ei nm_poisec 0 --es nm_view 3d --ez nm_sim true
sleep 28; shot 09_guida_camper

# settings: the list of pages, then the places page (groups, categories, finer choices) and the map page
start --es nm_sheet settings; sleep 8; shot 10_impostazioni
start --es nm_sheet settings_poi; sleep 8; shot 10b_impostazioni_poi
adb shell input swipe 1800 1500 1800 300 600; sleep 3; shot 10c_impostazioni_poi_2
start --es nm_sheet settings_map; sleep 8; shot 10d_impostazioni_mappa
start --es nm_sheet regions; sleep 12; shot 11_paesi
log
grep -E "probe |route computed|scan:|criticalities|Valhalla ready|edges in|variant |advice|toll check|booth|FATAL|Exception" "$OUT/logcat.txt" | head -80 >> "$INFO"
echo done >> "$INFO"
