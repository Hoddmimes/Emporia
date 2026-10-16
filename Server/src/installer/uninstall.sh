#!/bin/bash
# Removes the Emporia service installed by emporia-<version>.run, and its program files. The data
# (emporia.json, emporia.env, emporia.db, the PEM files, server.log) and the user emporia are kept,
# unless --purge is given: then the whole installation directory and the user are removed.
#
#   sudo <installation directory>/uninstall.sh [--purge]
set -euo pipefail

SERVICE=emporia
SERVICE_USER=emporia
UNIT_FILE=/etc/systemd/system/$SERVICE.service
TARGET=$(cd "$(dirname "$0")" && pwd)

PURGE=false
case ${1:-} in
    "")      ;;
    --purge) PURGE=true ;;
    *)       echo "Usage: $0 [--purge]" >&2; exit 1 ;;
esac
[ "$(id -u)" -eq 0 ] || { echo "Error: run it as root (sudo $0)" >&2; exit 1; }
[ -f "$TARGET/emporia.jar" ] || { echo "Error: no Emporia installation in $TARGET" >&2; exit 1; }

if [ -f "$UNIT_FILE" ]; then
    systemctl disable --now --quiet "$SERVICE" || true
    rm -f "$UNIT_FILE"
    systemctl daemon-reload
    echo "Removed the $SERVICE service"
fi

if $PURGE; then
    rm -rf "$TARGET"
    if id "$SERVICE_USER" >/dev/null 2>&1; then userdel "$SERVICE_USER" && echo "Removed the user $SERVICE_USER"; fi
    echo "Removed $TARGET with all its data"
else
    rm -rf "$TARGET/emporia.jar" "$TARGET/WebContent" "$TARGET/log4j2.xml" "$TARGET/log4j2.xml.new" "$TARGET/README.txt" "$TARGET/uninstall.sh"
    echo "Removed the program files; the data is kept in $TARGET (remove it with rm -rf $TARGET, and the user with userdel $SERVICE_USER)"
fi
