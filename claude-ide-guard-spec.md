# claude-ide-guard — Project Spec / Kickoff Prompt

> This document is both the design spec and the kickoff prompt for a fresh
> Claude Code session. The target repository is **empty / new** — there are
> no existing conventions to follow. Build the project described below from
> scratch. Use `brainstorming`/`writing-plans` only if you need to refine an
> open detail; the high-level design here is already approved.

---

## 1. Background & Problem

The author runs Claude Code with the JetBrains (WebStorm) IDE integration.
Often a Claude Code agent edits files **in parallel** while the author is
manually refactoring in the IDE. This causes two problems:

1. **Invisible agent activity** — the author cannot see which file Claude is
   touching right now, so they may start editing the same file by hand.
2. **Lost-edit conflicts** — if a file has **unsaved (dirty)** changes in the
   IDE and Claude writes it on disk, WebStorm raises a "file changed
   externally" conflict and edits can be lost.

Claude Code's IDE integration does **not** expose the active/open/dirty file
state to hooks or to the model. The only way to obtain that state is from
**inside the IDE** — hence a custom WebStorm plugin is required.

## 2. Goal

A WebStorm plugin + a set of Claude Code hooks that:

- **Primary value — visibility (channel B):** whenever Claude is about to
  change a file, the file lights up in WebStorm (tab color, Project view
  badge, and a dedicated tool window list), so the author always sees what
  the agent is working on.
- **Secondary — soft guard (channel A):** before Claude edits a file that has
  **unsaved changes** in the IDE, the hook asks the author for confirmation.

The agent's normal work must **never be blocked** by this tool: if the plugin
is off or unreachable, hooks fail open (allow).

## 3. Approved Architecture Decisions

These were decided during brainstorming. Do not relitigate them.

- **Scope:** both channels (B = highlight, A = dirty-only soft guard) in MVP.
- **Repo:** standalone new repository (not part of any product repo).
- **Transport:** localhost HTTP. Plugin holds state **in memory** — there are
  **no state files on disk** (this avoids file write-write conflicts that the
  author explicitly wanted to avoid).
- **Gate strictness:** the hook returns `ask` **only** when the target file is
  **dirty** (unsaved) in the IDE. Open/active-but-saved files do NOT trigger
  `ask` — they only get the channel-B highlight.
- **Highlight UI:** three persistent indicators while Claude touches a file —
  editor **tab color**, **Project view** badge/icon, and a **tool window**
  list. No toast/balloon notifications.
- **Multi-window:** a **single application-level** HTTP server (one fixed
  port) that knows about all open projects and routes by absolute file path.
  The hook always hits one well-known port; no per-project port discovery.

## 4. Components

### 4.1 WebStorm plugin (Kotlin, Gradle IntelliJ Plugin)

- **Application-level service** that starts an HTTP server bound to
  `127.0.0.1:<port>` (default `7337`, configurable in Settings).
- Tracks, across **all** open projects:
  - the set of files Claude currently has "in flight" (channel B), keyed by
    absolute path, with `{ sessionId, startedAt, lastSeen }`;
  - per-file **dirty** state, via `FileDocumentManager.isDocumentUnsaved`;
  - the currently active editor / selection, via `FileEditorManagerListener`
    (needed later; for MVP only dirty drives the gate).
- **In-memory state map** `absPath → ClaudeEditState`, thread-safe.
- **UI indicators** (all must clear when the file is no longer in flight):
  - **Tab color:** implement `EditorTabColorProvider` to tint tabs of
    in-flight files.
  - **Project view:** a node decorator / icon badge on in-flight files.
  - **Tool window:** a "Claude is editing" panel listing in-flight files with
    status (e.g. `editing`, `done`) and the originating session id.
  - All Swing/UI mutations go through `invokeLater` on the EDT.

### 4.2 Claude Code hooks (shell, installed into `~/.claude/settings.json`)

- **`PreToolUse`** — matcher `Edit|Write|MultiEdit|NotebookEdit`:
  1. Read the hook stdin JSON; extract `tool_input.file_path` and
     `session_id`.
  2. `POST /editing { path, action: "start", sessionId }` → plugin lights the
     indicators.
  3. `GET /check?path=<file>` → if response says the file is **dirty**, emit
     PreToolUse JSON with `permissionDecision: "ask"` (+ reason); otherwise
     `allow`.
- **`PostToolUse`** — same matcher: `POST /editing { path, action: "end",
  sessionId }` → plugin clears the indicators for that file.

The hooks are a small, dependency-light shell (or a tiny script) using `curl`
+ `jq`.

## 5. HTTP Contract

All endpoints bound to loopback only.

- `GET /check?path=<absolute-path>`
  - `200 → { "decision": "ask" | "allow", "reason": "<human text>" }`
  - `decision: "ask"` is returned **only** when the file is dirty in some open
    project. Otherwise `allow`.
- `POST /editing` body `{ "path": "<abs>", "action": "start" | "end",
  "sessionId": "<id>" }`
  - `200 → { "ok": true }`. `start` adds/refreshes the in-flight entry and
    lights indicators; `end` clears it.
- `GET /health` → `200` (debugging / readiness probe).

Notes:
- No authentication in MVP (loopback only). A shared-token header is a
  future hardening item — design the server so it can be added without
  breaking the contract.
- Request/response bodies are JSON; keep them small and stable.

## 6. Hook ↔ Claude Code Output Contract (reference)

`PreToolUse` hook stdin (provided by Claude Code):
```json
{
  "session_id": "abc123",
  "cwd": "/project/path",
  "tool_name": "Edit",
  "tool_input": { "file_path": "/abs/path/file.ts" },
  "hook_event_name": "PreToolUse",
  "permission_mode": "default"
}
```

`PreToolUse` hook stdout (exit 0) to ask for confirmation:
```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "ask",
    "permissionDecisionReason": "File has unsaved changes in WebStorm — confirm before overwriting."
  }
}
```
To allow, return `permissionDecision: "allow"` (or simply exit 0 with no
decision, per the chosen convention — but be explicit and return `allow`).

Example `settings.json` wiring (final paths set at install time):
```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Edit|Write|MultiEdit|NotebookEdit",
        "hooks": [ { "type": "command", "command": "~/.claude/hooks/ide-guard-pre.sh" } ] }
    ],
    "PostToolUse": [
      { "matcher": "Edit|Write|MultiEdit|NotebookEdit",
        "hooks": [ { "type": "command", "command": "~/.claude/hooks/ide-guard-post.sh" } ] }
    ]
  }
}
```

## 7. Reliability Rules (must-haves)

- **Fail open / graceful degradation.** Hooks call `curl` with a short
  timeout (~300 ms). On timeout, connection refused, non-2xx, or malformed
  body → return `allow` and exit 0. The plugin being off must never block or
  slow down Claude's work.
- **Stale protection.** If `PostToolUse` never fires (session killed
  mid-edit), in-flight entries must expire automatically: track `lastSeen`
  and run a TTL sweep (e.g. expire after N seconds without refresh) so
  indicators clear themselves.
- **Concurrency.** The state map and UI updates must be thread-safe; the HTTP
  server runs off the EDT, UI changes are dispatched onto the EDT.
- **Idempotency.** Repeated `start`/`end` for the same path must be safe.

## 8. Out of Scope (MVP) — YAGNI

- MCP server for on-demand conversational queries ("what file am I looking
  at?"). Phase 2, same port — leave room for it but do not build it now.
- Gating on active/open (saved) files. Only dirty files gate.
- Token authentication.
- Cross-editor support (VS Code, etc.). WebStorm/JetBrains only.

## 9. Tech Stack

- **Plugin:** Kotlin, Gradle with the `org.jetbrains.intellij` (IntelliJ
  Platform) plugin. Target a current IntelliJ Platform / WebStorm version.
  Lightweight embedded HTTP server (the platform ships a built-in server, or
  use a minimal HTTP library — pick the simplest that binds loopback).
- **Hooks:** POSIX shell + `curl` + `jq`. Keep them tiny and installable by
  copying into `~/.claude/hooks/` plus a `settings.json` snippet.
- **Repo layout:** `plugin/` (Gradle project), `hooks/` (shell scripts +
  sample `settings.json`), `README.md` (install + usage).

## 10. Implementation Checklist

1. Scaffold the Gradle IntelliJ plugin project; verify it loads in a sandbox
   WebStorm.
2. Application-level service + loopback HTTP server with `/health`.
3. In-memory `ClaudeEditState` map + thread-safe accessors.
4. `POST /editing` (start/end) updating the map; TTL sweep for stale entries.
5. Dirty detection (`FileDocumentManager`) + `GET /check` returning ask/allow.
6. `FileEditorManagerListener` wiring (active/selection tracking — store for
   future even if MVP gate ignores it).
7. UI: `EditorTabColorProvider` tab tint for in-flight files.
8. UI: Project view node decorator / icon badge.
9. UI: "Claude is editing" tool window with the live list + status.
10. Settings page: configurable port (default 7337).
11. Hooks: `ide-guard-pre.sh` (POST start + GET check → ask/allow) and
    `ide-guard-post.sh` (POST end), both fail-open on any error.
12. Sample `settings.json` snippet + `README.md` (install, port config,
    troubleshooting).

## 11. Acceptance Criteria

- With the plugin running, when Claude edits a file: its tab changes color,
  its Project view node is badged, and it appears in the tool window — and all
  three clear within a couple of seconds after the edit completes.
- Editing a file that has **unsaved** changes in WebStorm triggers a Claude
  permission prompt ("ask"); editing a saved file does not.
- With the plugin **stopped**, Claude edits proceed normally with no added
  latency and no errors (fail-open verified).
- Killing a Claude session mid-edit leaves no permanently-stuck indicators
  (TTL sweep verified).
- Multiple WebStorm windows open: an edit in project A highlights the file in
  project A's window, not project B's.
