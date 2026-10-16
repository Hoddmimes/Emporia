#!/usr/bin/env bash
#
# Starts and stops the Emporia server from its fat jar (Server-<version>-all.jar, made by
# ./gradlew :Server:shadowJar or :Server:build).
#
#   emporia.sh start [test]   start in the background (test: -mode test, all markets opened)
#   emporia.sh stop           stop it (SIGTERM: the server stores its queued trades first)
#   emporia.sh restart [test]
#   emporia.sh status
#   emporia.sh run [test]     run in the foreground, logging to the terminal; Ctrl-C stops it
#   emporia.sh log            follow the server log
#
# The server runs in EMPORIA_HOME (default: this script's directory), so the relative paths of the
# configuration (web root, certificate and key) and the log file server.log are relative to it.
# Settings, from the environment or EMPORIA_HOME/emporia.env (sourced if it exists):
#   EMPORIA_HOME    working directory                     (default: this script's directory)
#   EMPORIA_JAR     the fat jar                           (default: the newest *-all.jar in EMPORIA_HOME,
#                                                          else in EMPORIA_HOME/build/libs)
#   EMPORIA_CONFIG  configuration file                    (default: emporia.json)
#   EMPORIA_MODE    normal or test                        (default: normal; the test argument overrides it)
#   JAVA            java 21 or later                      (default: $JAVA_HOME/bin/java, else java)
#   JAVA_OPTS       JVM options                           (default: -Xms256m -Xmx1g)
#   STOP_TIMEOUT    seconds to wait for a stop before kill -9 (default: 30)
#
# The log configuration is EMPORIA_HOME/log4j2.xml if it exists, else the one in the jar (both log to
# the console and server.log). In the background the console, with only warnings and errors
# (-Demporia.console.level=WARN), goes to emporia.out, which also catches errors at startup.

EMPORIA_HOME=${EMPORIA_HOME:-$(cd "$(dirname "$(readlink -f "$0")")" && pwd)}
cd "$EMPORIA_HOME" || { echo "Cannot enter $EMPORIA_HOME" >&2; exit 1; }
[ -f emporia.env ] && . ./emporia.env

EMPORIA_CONFIG=${EMPORIA_CONFIG:-emporia.json}
EMPORIA_MODE=${EMPORIA_MODE:-normal}
JAVA_OPTS=${JAVA_OPTS:--Xms256m -Xmx1g}
STOP_TIMEOUT=${STOP_TIMEOUT:-30}
if [ -z "$JAVA" ]; then
    if [ -n "$JAVA_HOME" ]; then JAVA=$JAVA_HOME/bin/java; else JAVA=java; fi
fi
PID_FILE=$EMPORIA_HOME/emporia.pid
OUT_FILE=$EMPORIA_HOME/emporia.out
LOG_FILE=$EMPORIA_HOME/server.log

die() { echo "$*" >&2; exit 1; }

find_jar() {
    if [ -n "$EMPORIA_JAR" ]; then
        [ -f "$EMPORIA_JAR" ] || die "EMPORIA_JAR not found: $EMPORIA_JAR"
        return
    fi
    EMPORIA_JAR=$(ls -t ./*-all.jar 2>/dev/null | head -1)
    [ -z "$EMPORIA_JAR" ] && EMPORIA_JAR=$(ls -t build/libs/*-all.jar 2>/dev/null | head -1)
    [ -n "$EMPORIA_JAR" ] || die "No fat jar (*-all.jar) in $EMPORIA_HOME or $EMPORIA_HOME/build/libs; build it with ./gradlew :Server:shadowJar or set EMPORIA_JAR"
}

check_java() {
    command -v "$JAVA" >/dev/null 2>&1 || die "Java not found ($JAVA); set JAVA or JAVA_HOME"
    local tVersion
    tVersion=$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    [ -n "$tVersion" ] && [ "$tVersion" -ge 21 ] || die "Java 21 or later is needed ($JAVA is version ${tVersion:-unknown})"
}

# The pid of the running server, from the pid file, if that process is still this server's java
running_pid() {
    [ -f "$PID_FILE" ] || return 1
    local tPid
    tPid=$(cat "$PID_FILE")
    [ -n "$tPid" ] && kill -0 "$tPid" 2>/dev/null || return 1
    tr '\0' ' ' < "/proc/$tPid/cmdline" 2>/dev/null | grep -q -- "-all.jar" || return 1
    echo "$tPid"
}

# The java command line, in the array CMD
build_cmd() {
    find_jar
    check_java
    [ -f "$EMPORIA_CONFIG" ] || die "Configuration not found: $EMPORIA_HOME/$EMPORIA_CONFIG"
    case $EMPORIA_MODE in normal|test) ;; *) die "EMPORIA_MODE must be normal or test, not $EMPORIA_MODE" ;; esac
    CMD=("$JAVA")
    read -r -a tOpts <<< "$JAVA_OPTS"
    CMD+=("${tOpts[@]}")
    [ -f log4j2.xml ] && CMD+=("-Dlog4j2.configurationFile=$EMPORIA_HOME/log4j2.xml")
    CMD+=(-jar "$EMPORIA_JAR" -config "$EMPORIA_CONFIG" -mode "$EMPORIA_MODE")
}

do_start() {
    local tPid
    if tPid=$(running_pid); then echo "Emporia is already running (pid $tPid)"; return 0; fi
    rm -f "$PID_FILE"
    build_cmd
    CMD=("${CMD[0]}" -Demporia.console.level=WARN "${CMD[@]:1}")
    echo "Starting Emporia ($EMPORIA_MODE mode): $EMPORIA_JAR"
    local tLogStart=1   # server.log is appended to: look only at this start's lines
    [ -f "$LOG_FILE" ] && tLogStart=$(( $(wc -l < "$LOG_FILE") + 1 ))
    nohup "${CMD[@]}" > "$OUT_FILE" 2>&1 < /dev/null &
    tPid=$!
    echo "$tPid" > "$PID_FILE"
    # Wait for the web server to listen, or the process to die
    for _ in $(seq 1 30); do
        if ! kill -0 "$tPid" 2>/dev/null; then
            rm -f "$PID_FILE"
            echo "Emporia failed to start; the end of $OUT_FILE:" >&2
            tail -20 "$OUT_FILE" >&2
            echo "(and $LOG_FILE)" >&2
            return 1
        fi
        if tail -n +"$tLogStart" "$LOG_FILE" 2>/dev/null | grep -q "listening on"; then
            tail -n +"$tLogStart" "$LOG_FILE" | grep "Emporia V\|listening on" | sed 's/^.* - /  /'
            echo "Emporia started (pid $tPid), log $LOG_FILE"
            return 0
        fi
        sleep 1
    done
    echo "Emporia is running (pid $tPid) but not listening yet; see $LOG_FILE"
}

do_stop() {
    local tPid
    if ! tPid=$(running_pid); then echo "Emporia is not running"; rm -f "$PID_FILE"; return 0; fi
    echo -n "Stopping Emporia (pid $tPid) "
    kill "$tPid"
    for _ in $(seq 1 "$STOP_TIMEOUT"); do
        if ! kill -0 "$tPid" 2>/dev/null; then
            rm -f "$PID_FILE"
            echo " stopped"
            return 0
        fi
        echo -n "."
        sleep 1
    done
    echo " no stop after $STOP_TIMEOUT s, killing it"
    kill -9 "$tPid" 2>/dev/null
    rm -f "$PID_FILE"
}

do_status() {
    local tPid
    if tPid=$(running_pid); then
        echo "Emporia is running (pid $tPid, since $(ps -o lstart= -p "$tPid"))"
        grep "Emporia V" "$LOG_FILE" 2>/dev/null | tail -1 | sed 's/^.* - /  /'
        return 0
    fi
    echo "Emporia is not running"
    return 3
}

[ "$2" = "test" ] && EMPORIA_MODE=test
case $1 in
    start)   do_start ;;
    stop)    do_stop ;;
    restart) do_stop && do_start ;;
    status)  do_status ;;
    run)
        if tPid=$(running_pid); then die "Emporia is already running in the background (pid $tPid)"; fi
        build_cmd
        exec "${CMD[@]}" ;;
    log)     exec tail -F "$LOG_FILE" ;;
    *)       echo "Usage: $0 start [test] | stop | restart [test] | status | run [test] | log" >&2; exit 2 ;;
esac
