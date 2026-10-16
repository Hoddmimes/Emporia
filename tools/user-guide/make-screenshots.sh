#!/bin/bash
# Makes the screenshots of the user guide (Server/WebContent/user-guide.html) into
# Server/WebContent/guide and of the admin guide (Server/WebContent/admin/admin-guide.html) into
# Server/WebContent/admin/guide; the argument user or admin makes only one of them. It builds the
# server (installDist, no WAR, so the build number stays), starts it in test mode on port 18090 with an emptied database emporia_guide, fills it with demo
# data (seed.mjs), takes the screenshots with headless Chrome (screenshots.mjs, admin-screenshots.mjs)
# and stops the server.
# Needs MongoDB on localhost (the legacy mongo shell), node 22+ and google-chrome.
set -e
WHAT=${1:-all}
case $WHAT in user|admin|all) ;; *) echo "usage: $0 [user|admin]" >&2; exit 1;; esac
cd "$(dirname "$0")"
ROOT=../..
URL=http://127.0.0.1:18090
LOG=$(mktemp -t emporia-guide-server.XXXXXX.log)

(cd $ROOT && ./gradlew -q :Server:installDist)
mongo --quiet emporia_guide --eval 'db.dropDatabase()' >/dev/null

$ROOT/Server/build/install/Server/bin/Server -config guide-server.json -mode test > "$LOG" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; wait $SERVER 2>/dev/null' EXIT
for i in $(seq 60); do curl -s -o /dev/null $URL/api/version && break; sleep 0.5; done

node seed.mjs $URL
[ $WHAT = admin ] || node screenshots.mjs $URL/ ${GUIDE_OUT:-$ROOT/Server/WebContent/guide}
[ $WHAT = user ]  || node admin-screenshots.mjs $URL/ ${ADMIN_GUIDE_OUT:-$ROOT/Server/WebContent/admin/guide}
echo "Server log: $LOG"
