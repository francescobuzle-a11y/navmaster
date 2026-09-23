#!/bin/bash
# Runs NavMaster on the emulator with the offline test package (Rimini / San Marino) and
# takes screenshots: map, route preview with limits, guidance (simulated), portrait.
APKDIR="$1"; DATA="$2"; OUT="$3"
PKG=app.navmaster.truck
mkdir -p "$OUT"
INFO="$OUT/preview_info.txt"
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$INFO"; }
log() { adb logcat -d -s NavMaster:* NavMasterRoute:* NavMasterLimits:* NavMasterVM:* NavMasterData:* AndroidRuntime:E FerrostarCore:* AndroidLocationProvider:* > "$OUT/logcat.txt" 2>&1; }

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
# location services on (a fresh emulator image may have them off) and a steady GPS fix at Rimini
adb shell settings put secure location_mode 3
adb shell settings put secure location_providers_allowed +gps
adb shell settings put secure location_providers_allowed +network
adb shell cmd location set-location-enabled true 2>/dev/null
( for i in $(seq 1 400); do adb emu geo fix 12.5663 44.0587 >/dev/null 2>&1; sleep 2; done ) &
GEO=$!
adb shell settings get secure location_providers_allowed >> "$INFO"

# first start creates the app folders, then the offline package is copied in
adb shell am start -n $PKG/.MainActivity; sleep 12
shot 01_benvenuto
adb shell am force-stop $PKG
D=/sdcard/Android/data/$PKG/files/regions/test
adb shell mkdir -p $D
for f in mappa.pmtiles percorsi.tar limiti.sqlite manifest.json; do
  if [ -f "$DATA/$f" ]; then adb push "$DATA/$f" $D/ >> "$INFO" 2>&1; else echo "manca $f" >> "$INFO"; fi
done
adb shell ls -la $D >> "$INFO" 2>&1

adb emu geo fix 12.5663 44.0587
adb shell am start -n $PKG/.MainActivity; sleep 15
adb emu geo fix 12.5664 44.0588; sleep 5
shot 02_mappa_offline

# truck route Rimini -> San Marino, offline, started in simulation
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --es nm_dest "43.9360,12.4460" --es nm_label "San Marino" --es nm_profile camion --es nm_load 12 --ez nm_sim true
sleep 25; shot 03_guida_camion
sleep 15; shot 04_guida_camion_2
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 8; shot 05_guida_verticale
adb shell settings put system user_rotation 0; sleep 4

# same trip for the camper: the route may differ where the truck is not allowed
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --es nm_dest "43.9360,12.4460" --es nm_label "San Marino" --es nm_profile camper --es nm_load 0.4 --ez nm_sim true
sleep 25; shot 06_guida_camper
log
grep -E "route computed|scan:|Valhalla ready|request=" "$OUT/logcat.txt" | head -20 >> "$INFO"
kill $GEO 2>/dev/null
echo done >> "$INFO"
