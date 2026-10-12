#!/bin/bash
# A key for one tablet of the fleet: printed once (to enter in the app), only its sha256 kept.
#   sudo /opt/navmaster/add_key.sh camion1        new key for "camion1"
#   sudo /opt/navmaster/add_key.sh --remove camion1
set -euo pipefail
KEYS=/opt/navmaster/keys.txt
[ "$(id -u)" = 0 ] || { echo "Lancialo con sudo"; exit 1; }
if [ "${1:-}" = "--remove" ]; then
  name=${2:?nome del tablet}
  sed -i "/^$name /d" $KEYS
  systemctl restart navmaster-server
  echo "Chiave di $name tolta."
  exit 0
fi
name=${1:?nome del tablet, es. camion1}
[[ "$name" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "Nome: solo lettere, numeri, - e _"; exit 1; }
grep -q "^$name " $KEYS 2>/dev/null && { echo "$name ha già una chiave (togli prima con --remove)"; exit 1; }
key=nm_$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
echo "$name $(printf '%s' "$key" | sha256sum | cut -d' ' -f1)" >> $KEYS
chown navmaster:navmaster $KEYS
systemctl restart navmaster-server
echo "Chiave per $name (copiala ora, non viene più mostrata):"
echo "  $key"
