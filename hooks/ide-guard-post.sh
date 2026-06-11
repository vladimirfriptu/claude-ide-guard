#!/bin/sh
# claude-ide-guard PostToolUse for Edit|Write|MultiEdit|NotebookEdit|Read.
# Releases this session's lock on the file. Fire-and-forget, fail open.
set -u

PORT="${CLAUDE_IDE_GUARD_PORT:-7337}"
HOST="127.0.0.1"

input=$(cat)
command -v jq >/dev/null 2>&1 || exit 0
command -v curl >/dev/null 2>&1 || exit 0

path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
session=$(printf '%s' "$input" | jq -r '.session_id // empty' 2>/dev/null)
[ -n "$path" ] || exit 0
[ -n "$session" ] || session="anonymous"

body=$(jq -nc --arg p "$path" --arg s "$session" '{path:$p, sessionId:$s}')
curl -s -m 0.3 -X POST "http://$HOST:$PORT/release" \
    -H 'Content-Type: application/json' -d "$body" >/dev/null 2>&1 || true
exit 0
