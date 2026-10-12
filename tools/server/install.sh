#!/bin/bash
# NavMaster server: installation on a fresh Ubuntu 24.04 machine (Oracle Cloud "Always Free",
# Ampere A1 ARM, 2 OCPU / 12 GB, 150 GB disk; also any other Ubuntu 24.04 with >= 8 GB RAM and
# >= 100 GB disk).
#
#   curl -fsSL https://raw.githubusercontent.com/francescobuzle-a11y/navmaster/main/tools/server/install.sh | sudo bash
#
# Run it again to update the program (the graphs are updated every week by a timer, or with
# "sudo systemctl start navmaster-update"). Settings: DOMAIN=... (default <ip>.sslip.io),
# COUNTRIES=italy,austria (default all), HEAP=4g.
set -euo pipefail

NM=/opt/navmaster
REPO=https://github.com/francescobuzle-a11y/navmaster.git
# the complete jar of GraphHopper 11.0 (the same of the graph builds and of the tests)
GH_JAR_URL=https://github.com/graphhopper/graphhopper/releases/download/11.0/graphhopper-web-11.0.jar
GH_JAR_SHA1=4cba3d3bbdc8572f7bc6ecd6ffebf8ff6d394ce4
COUNTRIES=${COUNTRIES:-all}
HEAP=${HEAP:-4g}

[ "$(id -u)" = 0 ] || { echo "Lancialo con sudo"; exit 1; }
say() { echo; echo "==> $*"; }

say "Pacchetti (Java 21, Python, git, Caddy)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q openjdk-21-jdk-headless python3 git curl ca-certificates
if ! apt-get install -y -q caddy; then
  # older Ubuntu: Caddy's own repository
  apt-get install -y -q debian-keyring debian-archive-keyring apt-transport-https gnupg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q && apt-get install -y -q caddy
fi

say "Utente e cartelle"
id navmaster >/dev/null 2>&1 || useradd --system --home-dir $NM --shell /usr/sbin/nologin navmaster
mkdir -p $NM/lib $NM/data
# GraphHopper maps many files in memory
echo "vm.max_map_count=262144" > /etc/sysctl.d/90-navmaster.conf
sysctl -q -p /etc/sysctl.d/90-navmaster.conf

say "Programma (dal repository)"
if [ -d $NM/src/.git ]; then git -C $NM/src fetch -q --depth 1 origin main && git -C $NM/src reset -q --hard origin/main
else rm -rf $NM/src && git clone -q --depth 1 $REPO $NM/src; fi
if [ ! -s $NM/lib/graphhopper-web-11.0.jar ]; then
  curl -fsSL -o $NM/lib/graphhopper-web-11.0.jar.part $GH_JAR_URL
  have=$(sha1sum $NM/lib/graphhopper-web-11.0.jar.part | cut -c1-40)
  [ "$GH_JAR_SHA1" = "$have" ] || { echo "graphhopper-web-11.0.jar: sha1 errato"; exit 1; }
  mv $NM/lib/graphhopper-web-11.0.jar.part $NM/lib/graphhopper-web-11.0.jar
fi
rm -rf $NM/classes.new && mkdir $NM/classes.new
javac -nowarn -encoding UTF-8 -d $NM/classes.new -cp $NM/lib/graphhopper-web-11.0.jar \
  $NM/src/app/src/main/java/app/navmaster/truck/routing/gh/*.java $NM/src/tools/server/NmServer.java
rm -rf $NM/classes && mv $NM/classes.new $NM/classes
rm -rf $NM/models && cp -r $NM/src/app/src/main/assets/gh $NM/models
cp $NM/src/tools/server/update_graphs.py $NM/src/tools/server/add_key.sh $NM/
chmod +x $NM/add_key.sh
touch $NM/keys.txt && chmod 640 $NM/keys.txt
chown -R navmaster:navmaster $NM

say "Servizio"
cat > /etc/systemd/system/navmaster-server.service <<EOF
[Unit]
Description=NavMaster routing server (GraphHopper)
After=network-online.target
Wants=network-online.target

[Service]
User=navmaster
WorkingDirectory=$NM
Environment=NM_DATA=$NM/data NM_MODELS=$NM/models NM_KEYS=$NM/keys.txt NM_BIND=127.0.0.1 NM_PORT=8989 NM_THREADS=2 NM_PRELOAD=all
# the heap taken whole at start (-Xms = -Xmx, pre-touched): the rest of the memory is for the
# graphs (memory-mapped), and Oracle never sees the machine as "idle" (memory < 20%)
ExecStart=/usr/bin/java -Xms$HEAP -Xmx$HEAP -XX:+AlwaysPreTouch -XX:+UseG1GC -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp $NM/lib/graphhopper-web-11.0.jar:$NM/classes app.navmaster.server.NmServer
Restart=always
RestartSec=5
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
cat > /etc/systemd/system/navmaster-update.service <<EOF
[Unit]
Description=NavMaster: update the graphs from the releases

[Service]
Type=oneshot
# (\$\$ = a "$" for bash: systemd expands "$" itself)
ExecStart=/bin/bash -c 'out=\$\$(runuser -u navmaster -- python3 -I $NM/update_graphs.py --data $NM/data --countries $COUNTRIES | tee /dev/stderr | tail -1); [ "\$\$out" = CHANGED ] && systemctl restart navmaster-server || true'
EOF
cat > /etc/systemd/system/navmaster-update.timer <<EOF
[Unit]
Description=NavMaster: graphs updated every week

[Timer]
OnCalendar=Mon *-*-* 03:30:00
RandomizedDelaySec=1800
Persistent=true

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable -q navmaster-server navmaster-update.timer

say "Grafi dei Paesi ($COUNTRIES): la prima volta ~12 GB, può richiedere 30-60 minuti"
runuser -u navmaster -- python3 -I $NM/update_graphs.py --data $NM/data --countries $COUNTRIES | tail -3
systemctl restart navmaster-server
systemctl start navmaster-update.timer

say "HTTPS (Caddy) e firewall"
if [ -z "${DOMAIN:-}" ]; then
  IP=$(curl -fsS -4 https://api.ipify.org || curl -fsS -4 https://ifconfig.me)
  DOMAIN="${IP//./-}.sslip.io"
fi
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
	reverse_proxy 127.0.0.1:8989
}
EOF
# Oracle's Ubuntu images let in only SSH: ports 80 (certificate) and 443 opened
if ! iptables -C INPUT -p tcp -m multiport --dports 80,443 -m conntrack --ctstate NEW -j ACCEPT 2>/dev/null; then
  iptables -I INPUT -p tcp -m multiport --dports 80,443 -m conntrack --ctstate NEW -j ACCEPT
  command -v netfilter-persistent >/dev/null && netfilter-persistent save -q || true
fi
systemctl enable -q caddy
systemctl restart caddy

sleep 8
say "Fatto"
echo "Indirizzo del server:  https://$DOMAIN"
echo "Controllo:             curl https://$DOMAIN/v1/health"
echo "Chiave per un tablet:  sudo $NM/add_key.sh camion1"
echo "Registro:              journalctl -u navmaster-server -f"
curl -fsS http://127.0.0.1:8989/v1/health || echo "(il server sta ancora aprendo i grafi: riprova tra un minuto)"
