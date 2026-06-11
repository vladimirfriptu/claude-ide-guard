#!/bin/sh
# claude-ide-guard — PreToolUse hook for Edit|Write|MultiEdit|NotebookEdit.
#
# Two jobs:
#   1. Channel B (visibility): tell the plugin Claude is about to touch a file
#      so it lights up the IDE indicators. Fire-and-forget.
#   2. Channel A (soft guard): ask the plugin whether the file is dirty in the
#      IDE; if so, return permissionDecision "ask" so Claude pauses for the user.
#
# FAIL OPEN: any error (no jq, plugin down, timeout, bad response, missing path)
# results in "allow" and exit 0. This tool must NEVER block or slow Claude.
set -u

PORT="${CLAUDE_IDE_GUARD_PORT:-7337}"
HOST="127.0.0.1"
TIMEOUT="0.3"

# Emit an "allow" decision and exit. Reason text is always a script literal
# (never untrusted data), so plain printf is safe here.
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

# Channel B — light the indicators. Ignore all errors.
start_body=$(jq -nc --arg p "$path" --arg s "$session" '{path:$p, action:"start", sessionId:$s}')
curl -s -m "$TIMEOUT" -X POST "http://$HOST:$PORT/editing" \
    -H 'Content-Type: application/json' -d "$start_body" >/dev/null 2>&1 || true

# Channel A — dirty gate.
enc=$(jq -rn --arg p "$path" '$p|@uri')
resp=$(curl -s -m "$TIMEOUT" "http://$HOST:$PORT/check?path=$enc" 2>/dev/null) || allow "plugin unreachable"
[ -n "$resp" ] || allow "empty response"

decision=$(printf '%s' "$resp" | jq -r '.decision // "allow"' 2>/dev/null) || allow "bad response"
if [ "$decision" = "ask" ]; then
    reason=$(printf '%s' "$resp" | jq -r '.reason // "File has unsaved changes in the IDE."' 2>/dev/null)
    jq -nc --arg r "$reason" \
        '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"ask",permissionDecisionReason:$r}}'
    exit 0
fi

allow "no unsaved changes"
