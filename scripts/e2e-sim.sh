#!/bin/sh
# The session against a real radio (P1's exit criterion).
#
# `meshpigeon-sim` is the firmware's own simulator: the same command processor
# the board runs, behind a TCP bridge. A script is piped into `mp` and the
# output is checked — which is the same code path a person drives, because a
# piped session *is* the session.
#
# Usage: SIM_PORT=8801 sh scripts/e2e-sim.sh
set -eu

root="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
port="${SIM_PORT:-8801}"
mp="$root/cli-app/build/bin/linuxX64/mpDebugExecutable/mp.kexe"
log="${E2E_LOG:-/tmp/mp-e2e.log}"

[ -x "$mp" ] || { echo "no mp binary at $mp — build it first" >&2; exit 1; }
[ -n "${MP_LD_LIBRARY_PATH:-}" ] && export LD_LIBRARY_PATH="$MP_LD_LIBRARY_PATH"

script=$(cat <<EOF
/link add sim tcp://127.0.0.1:$port
/radio connect sim
/radio info
/radio status
/radio tuning
/radio settings
/json on
/radio info
/json off
/radio disconnect
/quit
EOF
)

printf '%s\n' "$script" | "$mp" >"$log" 2>&1
status=$?

fail() {
    echo "e2e: $1" >&2
    echo "--- what mp printed ---" >&2
    cat "$log" >&2
    exit 1
}

# The session must end cleanly, and every one of these must have happened.
[ "$status" -eq 0 ] || fail "the session exited $status"
grep -q "connected to 127.0.0.1:$port" "$log" || fail "no connection to the simulator"
grep -q "board" "$log" || fail "no device information"
grep -q '"schema":1' "$log" || fail "no JSON envelope"
grep -q '"board"' "$log" || fail "no machine-readable device information"
grep -q "disconnected" "$log" || fail "the radio was never disconnected"

echo "e2e: mp talked to meshpigeon-sim on $port"
