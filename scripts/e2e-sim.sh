#!/bin/sh
# The session against a real radio (P1's exit criterion).
#
# `meshpigeon-sim` is the firmware's own simulator: the same command processor
# the board runs, behind a TCP bridge. A script is piped into `mp` and the
# output is checked — which is the same code path a person drives, because a
# piped session *is* the session.
#
# Two simulators, on purpose. A quiet one answers the handshake and the
# settings; a second one with `--traffic-ms` keeps receiving, so the packet
# store has something in it and `/radio history` is a real read rather than an
# empty list.
#
# Usage: SIM_PORT=8801 SIM_TRAFFIC_PORT=8802 sh scripts/e2e-sim.sh
set -eu

root="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
port="${SIM_PORT:-8801}"
traffic_port="${SIM_TRAFFIC_PORT:-8802}"
mp="$root/cli-app/build/bin/linuxX64/mpDebugExecutable/mp.kexe"
log="${E2E_LOG:-/tmp/mp-e2e.log}"
traffic_log="${E2E_TRAFFIC_LOG:-/tmp/mp-e2e-traffic.log}"

[ -x "$mp" ] || { echo "no mp binary at $mp — build it first" >&2; exit 1; }
[ -n "${MP_LD_LIBRARY_PATH:-}" ] && export LD_LIBRARY_PATH="$MP_LD_LIBRARY_PATH"

fail() {
    echo "e2e: $1" >&2
    for f in "$log" "$traffic_log"; do
        [ -f "$f" ] && { echo "--- $(basename "$f") ---" >&2; cat "$f" >&2; }
    done
    exit 1
}

# 1. The quiet radio: identity, status, tuning, and the JSON envelope.
printf '%s\n' "/link add sim tcp://127.0.0.1:$port" \
    '/radio connect sim' \
    '/radio info' \
    '/radio status' \
    '/radio settings' \
    '/radio retune 869525000 250000 8 5 20' \
    '/radio tuning' \
    '/json on' \
    '/radio info' \
    '/json off' \
    '/radio disconnect' \
    '/quit' | "$mp" >"$log" 2>&1
status=$?
[ "$status" -eq 0 ] || fail "the session exited $status"
grep -q "connected to 127.0.0.1:$port" "$log" || fail "no connection to the simulator"
grep -q "board" "$log" || fail "no device information"
# Either answer means the radio is on those settings; the tuning read afterwards
# is what proves it, so the check does not depend on how long the sim has run.
grep -qE "the radio (now runs|already ran)" "$log" || fail "the retune was not answered"
grep -q "869525000 Hz" "$log" || fail "the radio does not report the requested tuning"
grep -q '"schema":1' "$log" || fail "no JSON envelope"
grep -q '"board"' "$log" || fail "no machine-readable device information"
grep -q "disconnected" "$log" || fail "the radio was never disconnected"

# 2. The busy radio: packets the firmware really stored, read back and cleared.
printf '%s\n' "/link add traffic tcp://127.0.0.1:$traffic_port" \
    '/radio connect traffic' \
    '/radio history 5' \
    '/radio info' \
    '/radio purge' \
    '/radio history' \
    '/quit' | "$mp" >"$traffic_log" 2>&1
status=$?
[ "$status" -eq 0 ] || fail "the store session exited $status"
grep -qE '^[0-9]+ packets?, [0-9]+ delivered' "$traffic_log" || fail "the store was not read"
grep -q "received" "$traffic_log" || fail "no packet came back from the store"
grep -q "packets held" "$traffic_log" || fail "no store statistics"
grep -q "the packet store was cleared" "$traffic_log" || fail "the store was not purged"
grep -q "the radio is holding no packets" "$traffic_log" || fail "the store is not empty after a purge"

echo "e2e: mp talked to meshpigeon-sim on $port (handshake, tuning, JSON)"
echo "e2e: mp read and cleared the packet store of meshpigeon-sim on $traffic_port"