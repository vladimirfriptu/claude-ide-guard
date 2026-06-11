#!/bin/sh
# claude-ide-guard — PostToolUse hook for Edit|Write|MultiEdit|NotebookEdit.
#
# Tells the plugin Claude finished touching the file so it clears the IDE
# indicators. Fire-and-forget; PostToolUse needs no decision output.
#
# FAIL OPEN: any error is swallowed and we exit 0.
set -u

PORT="${CLAUDE_IDE_GUARD_PORT:-7337}"
HOST="127.0.0.1"
TIMEOUT="0.3"

input=$(cat)

command -v jq >/dev/null 2>&1 || exit 0
command -v curl >/dev/null 2>&1 || exit 0

path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
session=$(printf '%s' "$input" | jq -r '.session_id // empty' 2>/dev/null)
[ -n "$path" ] || exit 0

end_body=$(jq -nc --arg p "$path" --arg s "$session" '{path:$p, action:"end", sessionId:$s}')
curl -s -m "$TIMEOUT" -X POST "http://$HOST:$PORT/editing" \
    -H 'Content-Type: application/json' -d "$end_body" >/dev/null 2>&1 || true

exit 0
