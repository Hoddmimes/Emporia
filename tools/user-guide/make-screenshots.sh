#!/bin/bash
# Makes the screenshots of the user guide (Server/WebContent/user-guide.html) into
# Server/WebContent/guide: builds the server (installDist, no WAR, so the build number stays),
# starts it in test mode on port 18090 with an emptied database emporia_guide, fills it with demo
# data (seed.mjs), takes the screenshots with headless Chrome (screenshots.mjs) and stops the server.
# Needs MongoDB on localhost (the legacy mongo shell), node 22+ and google-chrome.
set -e
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
node screenshots.mjs $URL/ $ROOT/Server/WebContent/guide
echo "Server log: $LOG"
