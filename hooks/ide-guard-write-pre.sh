#!/bin/sh
# claude-ide-guard PreToolUse for Edit|Write|MultiEdit|NotebookEdit.
# Acquires an exclusive WRITE lock from the plugin, waiting (polling) until
# granted or the wait timeout, then allows. On any error -> fail open (allow).
set -u

PORT="${CLAUDE_IDE_GUARD_PORT:-7337}"
HOST="127.0.0.1"
WAIT_SECONDS="${CLAUDE_IDE_GUARD_WAIT_SECONDS:-10}"
POLL_MS="${CLAUDE_IDE_GUARD_POLL_MS:-200}"
REQ_TIMEOUT="0.3"

allow() {
    printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","permissionDecisionReason":"%s"}}\n' "claude-ide-guard: ${1:-allow}"
    exit 0
}

input=$(cat)
command -v jq >/dev/null 2>&1 || allow "jq not installed"
command -v curl >/dev/null 2>&1 || allow "curl not installed"

path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
session=$(printf '%s' "$input" | jq -r '.session_id // empty' 2>/dev/null)
[ -n "$path" ] || allow "no file_path"
[ -n "$session" ] || session="anonymous"

body=$(jq -nc --arg p "$path" --arg s "$session" '{path:$p, sessionId:$s, mode:"write"}')

deadline=$(( $(date +%s) + WAIT_SECONDS ))
last=""
while :; do
    resp=$(curl -s -m "$REQ_TIMEOUT" -X POST "http://$HOST:$PORT/acquire" \
        -H 'Content-Type: application/json' -d "$body" 2>/dev/null) || allow "plugin unreachable"
    [ -n "$resp" ] || allow "empty response"
    last="$resp"
    granted=$(printf '%s' "$resp" | jq -r '.granted // false' 2>/dev/null)
    [ "$granted" = "true" ] && break
    [ "$(date +%s)" -ge "$deadline" ] && allow "wait timeout, proceeding"
    sleep "$(awk "BEGIN{print $POLL_MS/1000}")"
done

dirty=$(printf '%s' "$last" | jq -r '.dirty // false' 2>/dev/null)
if [ "$dirty" = "true" ]; then
    jq -nc '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"ask",permissionDecisionReason:"File has unsaved changes in WebStorm — confirm before overwriting."}}'
    exit 0
fi
allow "lock acquired"
