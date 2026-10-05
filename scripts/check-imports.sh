#!/bin/sh
# The import gate (D18).
#
# `meshpigeon-cli` may use `meshpigeon-core`'s public API and nothing else. No
# `java.`, no SQLDelight, no generated protobuf type, no core `internal` package,
# and no chip or vendor identifier — an `sx1262` in the CLI would mean hardware
# knowledge had leaked out from under the firmware's own abstraction.
#
# `meshpigeon-core-testing` is the one exception, and only in test sources.
#
# Usage: scripts/check-imports.sh [--print]
set -eu

root="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
sources="$root/cli-core/src cli-render/src cli-journal/src cli-app/src cli-i18n/src"

# Each pattern is something a client has no business knowing.
forbidden='java\.|javax\.crypto|org\.bouncycastle|app\.cash\.sqldelight|com\.squareup\.wire|dev\.meshpigeon\.proto|dev\.meshpigeon\.core\..*\.internal|sx1262|lr1110|cc1101|esp32|nrf52|heltec|t1000e'

status=0
report=$(grep -rnE "$forbidden" $sources --include='*.kt' 2>/dev/null || true)

if [ -n "$report" ]; then
    # The core's own testing module is allowed in test sources, and nothing else
    # from core's internals is.
    real=$(printf '%s\n' "$report" | grep -v 'src/.*Test/' || true)
    if [ -n "$real" ] && [ "$real" != "$report" ]; then
        printf '%s\n' "$report" >&2
    elif [ -n "$real" ]; then
        printf '%s\n' "$real" >&2
        status=1
    fi
fi

if [ "${1:-}" = "--print" ]; then
    printf '%s\n' "$report"
    exit 0
fi

[ "$status" -eq 0 ] && echo "imports: ok"
exit "$status"