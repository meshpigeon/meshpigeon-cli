#!/bin/sh
# The localization gate (D31).
#
# Two rules, both mechanical:
#
#   1. No user-facing string literal in logic. Every visible text is a message id
#      resolved through `cli-i18n`, so adding a language is not a refactor.
#   2. Every id the code asks for exists in the catalog, and every catalog key is
#      used — a typo shows up here rather than as a missing message at runtime.
#
# What the rule deliberately does not cover: comments, exception messages (which
# never reach a person — the command layer turns them into a catalog message),
# and the entry points' `--help`, which is the binary describing itself.
set -eu

root="$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)"
catalog="$root/cli-i18n/src/commonMain/kotlin/dev/meshpigeon/cli/i18n/EnglishCatalog.kt"
sources="$root/cli-core/src $root/cli-render/src $root/cli-journal/src $root/cli-app/src $root/cli-i18n/src"
# The catalog is where the strings live; the tests deliberately invent ids.
production="$root/cli-core/src $root/cli-render/src $root/cli-journal/src $root/cli-app/src"

status=0

# ── 1. string literals in logic ────────────────────────────────────────────────
offenders=$(
    grep -rnE '"[A-Za-z][^"]* [A-Za-z][^"]*"' $production --include='*.kt' 2>/dev/null |
        grep -vE 'src/(common|jvm|native)Test/' |
        # comments and KDoc
        grep -vE '^[^:]+:[0-9]+: *(\*|//|/\*)' |
        # the catalog's own API
        grep -vE '(messages|messagesOf)\.(t|tp)\("' |
        grep -vE 'template\("' |
        # exception messages: the command layer localises them
        grep -vE '(IllegalState|IllegalArgument|UnsupportedOperation)Exception\(' |
        # the binary describing its own options
        grep -vE 'Main\.kt' || true
)

if [ -n "$offenders" ]; then
    echo "string literals outside the catalog (put them in EnglishCatalog):" >&2
    printf '%s\n' "$offenders" >&2
    status=1
fi

# ── 2. every id asked for exists ─────────────────────────────────────────────
missing=$(
    for id in $(grep -rhoE '\.(t|tp|template)\("[a-z0-9_.-]+"' $production --include='*.kt' 2>/dev/null |
        sed -E 's/.*"([a-z0-9_.-]+)"/\1/' | sort -u); do
        # `plural("chat.message", 1)` asks for chat.message.one and chat.message.many
        case "$id" in
            *.one | *.many) prefix=${id%.*} ;;
            *) prefix=$id ;;
        esac
        grep -q "\"$prefix\.\(one\|many\)\" to" "$catalog" 2>/dev/null && continue
        grep -q "\"$id\" to" "$catalog" || echo "$id"
    done
)

if [ -n "$missing" ]; then
    echo "message ids with no catalog entry:" >&2
    printf '%s\n' "$missing" >&2
    status=1
fi

# ── 3. every catalog key is reachable ─────────────────────────────────────────
# `reserved` names vocabulary that belongs to a later phase and is kept so a
# translator sees the whole language rather than half of it.
reserved='chat\.'

unused=$(
    for id in $(grep -oE '^        "[a-z0-9_.-]+" to ' "$catalog" | sed -E 's/.*"([a-z0-9_.-]+)".*/\1/' | sort -u); do
        echo "$id" | grep -qE "^($reserved)" && continue
        grep -rqE "(\"|\.)$id(\"|\.| )" $sources --include='*.kt' 2>/dev/null || echo "$id"
    done
)

if [ -n "$unused" ]; then
    echo "catalog entries nothing asks for:" >&2
    printf '%s\n' "$unused" >&2
    status=1
fi

[ "$status" -eq 0 ] && echo "i18n: ok"
exit "$status"