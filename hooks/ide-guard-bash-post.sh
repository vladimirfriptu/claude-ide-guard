#!/bin/sh
# claude-ide-guard PostToolUse for Bash. Re-sends the command + cwd so the
# arbiter re-parses and releases this session's lock set. Fire-and-forget.
set -u

PORT="${CLAUDE_IDE_GUARD_PORT:-7337}"
HOST="127.0.0.1"

input=$(cat)
command -v jq >/dev/null 2>&1 || exit 0
command -v curl >/dev/null 2>&1 || exit 0

cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // empty' 2>/dev/null)
cwd=$(printf '%s' "$input" | jq -r '.cwd // empty' 2>/dev/null)
session=$(printf '%s' "$input" | jq -r '.session_id // empty' 2>/dev/null)
[ -n "$cmd" ] || exit 0
[ -n "$session" ] || session="anonymous"

body=$(jq -nc --arg c "$cmd" --arg w "$cwd" --arg s "$session" '{command:$c, cwd:$w, sessionId:$s}')
curl -s -m 0.3 -X POST "http://$HOST:$PORT/release-bash" \
    -H 'Content-Type: application/json' -d "$body" >/dev/null 2>&1 || true
exit 0
