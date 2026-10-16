#!/bin/bash
# Emporia @VERSION@ installer for Linux: this script followed by a tar.gz of the server (the fat jar, the
# web pages, log4j2.xml, README.txt and uninstall.sh), built by ./gradlew :Server:installer.
#
#   sudo bash emporia-@VERSION@.run [options]
#
# Installs the server in one directory (asked for, default /opt/emporia) and runs it as the systemd
# service emporia, as the system user emporia. Java 21 or later must be installed. A new installation
# asks for the HTTPS port, the HTTP port (none by default) and the certificate and private key (PEM),
# which are copied to the installation directory as cert.pem and key.pem; the database is SQLite
# (emporia.db in the same directory). An upgrade (the directory has an emporia.json) replaces the program files and keeps the
# configuration, the database, the PEM files and the log; give the same directory to upgrade.
#
# Options:
#   --target DIR        installation directory (asked for; default /opt/emporia)
#   --https-port N      HTTPS port of a new installation (default 8443)
#   --http-port N|none  HTTP port of a new installation (default none)
#   --cert FILE         certificate (PEM) to install as cert.pem
#   --key FILE          private key (PEM) to install as key.pem
#   --java FILE         the java to run (default $JAVA_HOME/bin/java, else java on the PATH)
#   --yes               no questions: the defaults and the options given
#   --extract DIR       only unpack the files into DIR (no root needed)
#   --help
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

VERSION='@VERSION@'
ARCHIVE_SHA256='@SHA256@'
SERVICE=emporia
SERVICE_USER=emporia
UNIT_FILE=/etc/systemd/system/$SERVICE.service

TARGET=
HTTPS_PORT=
HTTP_PORT=
CERT=
KEY=
JAVA=
ASSUME_YES=false
EXTRACT_DIR=
FILES_DIR=

die()  { echo "Error: $*" >&2; exit 1; }
info() { echo "$*"; }

usage() {
    sed -n '2,/^if \[ -z/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
    exit 0
}

# ask VAR "question" default: reads an answer into VAR (the default on Enter, or with --yes)
ask() {
    local tAnswer
    if $ASSUME_YES || [ ! -t 0 ]; then
        printf -v "$1" '%s' "$3"
        return
    fi
    read -r -p "$2 [$3]: " tAnswer
    printf -v "$1" '%s' "${tAnswer:-$3}"
}

valid_port() {
    [[ $1 =~ ^[0-9]+$ ]] && [ "$1" -ge 1 ] && [ "$1" -le 65535 ]
}

parse_args() {
    while [ $# -gt 0 ]; do
        case $1 in
            --target)     TARGET=${2:?--target needs a directory}; shift 2 ;;
            --https-port) HTTPS_PORT=${2:?--https-port needs a port}; shift 2 ;;
            --http-port)  HTTP_PORT=${2:?--http-port needs a port or none}; shift 2 ;;
            --cert)       CERT=${2:?--cert needs a file}; shift 2 ;;
            --key)        KEY=${2:?--key needs a file}; shift 2 ;;
            --java)       JAVA=${2:?--java needs a file}; shift 2 ;;
            --yes|-y)     ASSUME_YES=true; shift ;;
            --extract)    EXTRACT_DIR=${2:?--extract needs a directory}; shift 2 ;;
            --help|-h)    usage ;;
            *)            die "Unknown option $1 (see --help)" ;;
        esac
    done
}

# Unpacks the archive after the __ARCHIVE_BELOW__ line into $1, after checking its checksum
unpack() {
    local tLine tTmp
    tLine=$(grep -an '^__ARCHIVE_BELOW__$' "$0" | head -1 | cut -d: -f1)
    [ -n "$tLine" ] || die "The installer is damaged (no archive)"
    tTmp=$(mktemp)
    tail -n +"$((tLine + 1))" "$0" > "$tTmp"
    if ! echo "$ARCHIVE_SHA256  $tTmp" | sha256sum -c --status; then
        rm -f "$tTmp"
        die "The installer is damaged (checksum mismatch); download it again"
    fi
    mkdir -p "$1"
    tar -xzf "$tTmp" -C "$1"
    rm -f "$tTmp"
}

check_java() {
    if [ -z "$JAVA" ]; then
        if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA=$JAVA_HOME/bin/java
        else JAVA=$(command -v java || true)
        fi
    fi
    [ -n "$JAVA" ] && [ -x "$JAVA" ] || die "Java not found; install Java 21 or later, or give it with --java"
    case $JAVA in /*) ;; *) JAVA=$(cd "$(dirname "$JAVA")" && pwd)/$(basename "$JAVA") ;; esac
    local tVersion
    tVersion=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    [ -n "$tVersion" ] && [ "$tVersion" -ge 21 ] || die "Java 21 or later is needed ($JAVA is version ${tVersion:-unknown})"
    info "Java $tVersion: $JAVA"
}

# The ports and PEM files of a new installation, asked for unless given as options
configure() {
    while :; do
        [ -n "$HTTPS_PORT" ] || ask HTTPS_PORT "HTTPS port" 8443
        valid_port "$HTTPS_PORT" && break
        $ASSUME_YES && die "Invalid HTTPS port $HTTPS_PORT"
        echo "Not a port number (1-65535): $HTTPS_PORT"; HTTPS_PORT=
    done
    while :; do
        [ -n "$HTTP_PORT" ] || ask HTTP_PORT "HTTP port (none: HTTPS only)" none
        if [ "$HTTP_PORT" = none ] || { valid_port "$HTTP_PORT" && [ "$HTTP_PORT" != "$HTTPS_PORT" ]; }; then break; fi
        $ASSUME_YES && die "Invalid HTTP port $HTTP_PORT"
        echo "Not a port number (1-65535) other than the HTTPS port: $HTTP_PORT"; HTTP_PORT=
    done
    if [ -f "$TARGET/cert.pem" ] && [ -f "$TARGET/key.pem" ] && [ -z "$CERT$KEY" ]; then
        info "Using the certificate and key already in $TARGET"
        return
    fi
    [ -n "$CERT" ] || ask CERT "Certificate file (PEM; none: add $TARGET/cert.pem later)" none
    [ -n "$KEY" ]  || ask KEY  "Private key file (PEM; none: add $TARGET/key.pem later)" none
    [ "$CERT" = none ] && CERT=
    [ "$KEY" = none ] && KEY=
    if [ -n "$CERT" ]; then [ -f "$CERT" ] || die "Certificate file not found: $CERT"; fi
    if [ -n "$KEY" ];  then [ -f "$KEY" ]  || die "Private key file not found: $KEY"; fi
}

write_config() {
    local tHttp=""
    [ "$HTTP_PORT" != none ] && tHttp="
    \"http_port\": \"$HTTP_PORT\","
    cat > "$TARGET/emporia.json" <<EOF
{
  "sessionInactivityTime": "05:00",
  "database": {
    "type": "sqlite",
    "file": "./emporia.db"
  },
  "http_interface": {
    "root": "./WebContent",
    "interface": "0.0.0.0",
    "https_port": "$HTTPS_PORT",$tHttp
    "ssl": {
      "cert": "./cert.pem",
      "key": "./key.pem"
    }
  }
}
EOF
    chown root:"$SERVICE_USER" "$TARGET/emporia.json"
    chmod 640 "$TARGET/emporia.json"
}

write_unit() {
    cat > "$UNIT_FILE" <<EOF
# Emporia marketplace server, installed by emporia-$VERSION.run; JAVA_OPTS in $TARGET/emporia.env
[Unit]
Description=Emporia marketplace server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$TARGET
Environment=JAVA_OPTS=
EnvironmentFile=-$TARGET/emporia.env
ExecStart=$JAVA \$JAVA_OPTS -Demporia.console.level=WARN -Dlog4j2.configurationFile=$TARGET/log4j2.xml -jar $TARGET/emporia.jar -config $TARGET/emporia.json
# The JVM exits with 143 on SIGTERM, after the shutdown hook has stored the queued trades
SuccessExitStatus=143
TimeoutStopSec=30
Restart=on-failure
RestartSec=5
# Allows ports below 1024 (e.g. 443) without running as root
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=true
UMask=0027

[Install]
WantedBy=multi-user.target
EOF
}

install_files() {
    local tFiles=$1
    # Program files: owned by root, replaced on every installation
    install -m 644 -o root -g root "$tFiles/emporia.jar" "$TARGET/.emporia.jar.tmp"
    mv -f "$TARGET/.emporia.jar.tmp" "$TARGET/emporia.jar"
    rm -rf "$TARGET/.WebContent.new"
    cp -r "$tFiles/WebContent" "$TARGET/.WebContent.new"
    chown -R root:root "$TARGET/.WebContent.new"
    chmod -R u=rwX,go=rX "$TARGET/.WebContent.new"
    rm -rf "$TARGET/WebContent"
    mv "$TARGET/.WebContent.new" "$TARGET/WebContent"
    install -m 755 -o root -g root "$tFiles/uninstall.sh" "$TARGET/uninstall.sh"
    install -m 644 -o root -g root "$tFiles/README.txt" "$TARGET/README.txt"
    # Settings: kept if present, a changed new version is put next to them
    if [ ! -f "$TARGET/log4j2.xml" ]; then
        install -m 644 -o root -g root "$tFiles/log4j2.xml" "$TARGET/log4j2.xml"
    elif ! cmp -s "$tFiles/log4j2.xml" "$TARGET/log4j2.xml"; then
        install -m 644 -o root -g root "$tFiles/log4j2.xml" "$TARGET/log4j2.xml.new"
        info "Kept your log4j2.xml; the new version is log4j2.xml.new"
    fi
    if [ ! -f "$TARGET/emporia.env" ]; then
        printf '# Java options of the emporia service (systemctl restart emporia after a change)\nJAVA_OPTS="-Xms256m -Xmx1g"\n' > "$TARGET/emporia.env"
        chmod 644 "$TARGET/emporia.env"
    fi
    if [ -n "$CERT" ]; then install -m 644 -o root -g "$SERVICE_USER" "$CERT" "$TARGET/cert.pem"; fi
    if [ -n "$KEY" ];  then install -m 640 -o root -g "$SERVICE_USER" "$KEY"  "$TARGET/key.pem"; fi
    # PEM files put there by hand: readable by the service, the key not by others
    if [ -f "$TARGET/cert.pem" ]; then chgrp "$SERVICE_USER" "$TARGET/cert.pem"; chmod 644 "$TARGET/cert.pem"; fi
    if [ -f "$TARGET/key.pem" ];  then chgrp "$SERVICE_USER" "$TARGET/key.pem";  chmod 640 "$TARGET/key.pem"; fi
}

# Prints the installed README.txt (the service commands, logs, settings, ...)
show_readme() {
    echo
    echo "──────────────────────────── $TARGET/README.txt ────────────────────────────"
    cat "$TARGET/README.txt"
    echo "──────────────────────────────────────────────────────────────────────────────"
}

# Waits until the server listens (its "listening on" lines in server.log after line $1) or the service failed
wait_started() {
    local tLogStart=$1 i
    for i in $(seq 1 30); do
        if tail -n +"$tLogStart" "$TARGET/server.log" 2>/dev/null | grep -q "listening on"; then
            tail -n +"$tLogStart" "$TARGET/server.log" | grep "Emporia V\|listening on" | sed 's/^.* - /  /'
            return 0
        fi
        systemctl is-failed --quiet "$SERVICE" && break
        sleep 1
    done
    echo "The service did not start; see: journalctl -u $SERVICE, $TARGET/server.log" >&2
    return 1
}

main() {
    parse_args "$@"
    if [ -n "$EXTRACT_DIR" ]; then
        unpack "$EXTRACT_DIR"
        info "Unpacked Emporia $VERSION into $EXTRACT_DIR"
        exit 0
    fi
    info "Emporia $VERSION installer"
    [ "$(id -u)" -eq 0 ] || die "Run the installer as root (sudo bash $0)"
    command -v systemctl >/dev/null && [ -d /run/systemd/system ] || die "systemd is needed to run Emporia as a service"
    check_java
    # An installed service suggests its directory (an upgrade); another one is refused below
    local tInstalled="" tDefault=/opt/emporia
    [ -f "$UNIT_FILE" ] && tInstalled=$(sed -n 's/^WorkingDirectory=//p' "$UNIT_FILE")
    [ -n "$tInstalled" ] && tDefault=$tInstalled
    while :; do
        [ -n "$TARGET" ] || ask TARGET "Installation directory" "$tDefault"
        TARGET=${TARGET%/}
        [[ $TARGET = /?* && $TARGET != *[[:space:]]* ]] && break
        $ASSUME_YES && die "The installation directory must be an absolute path without spaces: $TARGET"
        echo "Give an absolute path without spaces: $TARGET"; TARGET=
    done
    # Never take over a directory with other contents (it is chowned to the service user)
    # (a new installation may have only the PEM files put there beforehand)
    if [ -d "$TARGET" ] && [ ! -f "$TARGET/emporia.json" ] && [ -n "$(ls -A "$TARGET" | grep -vx 'cert.pem\|key.pem')" ]; then
        die "$TARGET is not empty and has no Emporia installation; choose a new or empty directory (cert.pem and key.pem may be there)"
    fi

    if [ -n "$tInstalled" ] && [ "$tInstalled" != "$TARGET" ]; then
        die "The $SERVICE service is already installed in $tInstalled; upgrade that one, or remove it first ($tInstalled/uninstall.sh)"
    fi

    local tUpgrade=false
    [ -f "$TARGET/emporia.json" ] && tUpgrade=true
    if $tUpgrade; then
        info "Upgrading the installation in $TARGET (configuration, database and PEM files kept)"
        [ -z "$HTTPS_PORT$HTTP_PORT" ] || info "Note: the ports are not changed on an upgrade; edit $TARGET/emporia.json"
    else
        info "New installation in $TARGET"
        configure
    fi

    FILES_DIR=$(mktemp -d)
    trap 'rm -rf "$FILES_DIR"' EXIT
    unpack "$FILES_DIR"

    if ! id "$SERVICE_USER" >/dev/null 2>&1; then
        useradd --system --home-dir "$TARGET" --no-create-home --shell "$(command -v nologin || echo /bin/false)" "$SERVICE_USER"
        info "Created the system user $SERVICE_USER"
    fi
    if systemctl is-active --quiet "$SERVICE"; then
        info "Stopping the $SERVICE service"
        systemctl stop "$SERVICE"
    fi

    # The directory is the service's working directory: it writes emporia.db and server.log there
    mkdir -p "$TARGET"
    chown "$SERVICE_USER:$SERVICE_USER" "$TARGET"
    chmod 750 "$TARGET"
    install_files "$FILES_DIR"
    $tUpgrade || write_config
    write_unit
    systemctl daemon-reload
    systemctl enable --quiet "$SERVICE"

    if grep -q '"https_port"' "$TARGET/emporia.json" && { [ ! -f "$TARGET/cert.pem" ] || [ ! -f "$TARGET/key.pem" ]; }; then
        show_readme
        echo
        info "Installed, but not started: HTTPS needs $TARGET/cert.pem and $TARGET/key.pem."
        info "Copy them there (key.pem readable by the group $SERVICE_USER) and run: systemctl start $SERVICE"
        info "See $TARGET/README.txt"
        exit 0
    fi

    local tLogStart=1
    [ -f "$TARGET/server.log" ] && tLogStart=$(( $(wc -l < "$TARGET/server.log") + 1 ))
    info "Starting the $SERVICE service"
    systemctl start "$SERVICE"
    wait_started "$tLogStart" || exit 1

    show_readme
    echo
    info "Emporia $VERSION is installed in $TARGET and runs as the service $SERVICE."
    if ! $tUpgrade; then
        info "  Trading:  https://$(hostname -f 2>/dev/null || hostname):$HTTPS_PORT/"
        info "  Admin:    https://$(hostname -f 2>/dev/null || hostname):$HTTPS_PORT/admin  (Admin / changeit: change the password)"
        info "  Open the port in the firewall if needed, e.g. firewall-cmd --permanent --add-port=$HTTPS_PORT/tcp && firewall-cmd --reload"
    fi
    info "  Service:  systemctl status|stop|start|restart $SERVICE"
    info "  Log:      $TARGET/server.log (warnings and errors also in journalctl -u $SERVICE)"
    info "  Settings: $TARGET/emporia.json, Java options in $TARGET/emporia.env"
    info "  Remove:   $TARGET/uninstall.sh [--purge]"
    info "  Help:     $TARGET/README.txt (service, logs, settings, backup, upgrade)"
    info "The markets are closed after each start; open them in the admin interface."
}

main "$@"
exit 0
__ARCHIVE_BELOW__
