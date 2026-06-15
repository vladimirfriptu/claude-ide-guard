# claude-ide-guard

A WebStorm/JetBrains plugin + Claude Code hooks that coordinate parallel Claude
Code agents around file access and make in-flight activity **visible** in your
IDE.

Three channels:

- **Readers-writer lock (primary).** Before touching a file, a hook acquires a
  shared READ lock (for `Read`) or an exclusive WRITE lock (for
  `Edit|Write|MultiEdit|NotebookEdit`). A second agent that needs conflicting
  access waits (polling) until the first releases, or proceeds after a
  configurable timeout — **fail open, never blocking Claude permanently**.
- **Visibility.** While a lock is held, the file lights up: editor **tab icon**
  (pencil = write, eye = read), **Project view** badge, and a **"Claude Edits"
  tool window** list.
- **Dirty guard.** When a write lock is acquired on a file that has **unsaved
  changes** in the IDE, the hook pauses and asks you to confirm before
  overwriting.

## How it works

```
Agent A  ──PreToolUse hook──▶  POST /acquire {path, sessionId, mode:"write"}
                                      ┌─ granted:true  → tool runs
                                      └─ granted:false → poll until granted or timeout → allow
Agent A  ──PostToolUse hook─▶  POST /release {path, sessionId}  →  lock freed

Agent B  ──PreToolUse hook──▶  POST /acquire {path, sessionId, mode:"read"}
                                      └─ multiple readers allowed simultaneously
```

**Readers-writer semantics:**
- Multiple agents may hold READ on the same path simultaneously.
- A WRITE holder blocks all other readers and writers on that path.
- The same session never blocks itself (safe re-entrance, self-upgrade).

**Cross-agent coordination** works because a single plugin instance owns the
server port. Every agent that connects to that port sees the same lock table
— the arbiter is the IDE process that opened the project first.

**Icons:** pencil (✎) = active write, eye (👁) = active read.

The agent is **never permanently blocked**. If the plugin is off, unreachable,
or the wait timeout expires, the hooks fail open (allow) immediately.

## Repository layout

```
plugin/   Gradle IntelliJ Platform plugin (Kotlin)
hooks/    PreToolUse / PostToolUse shell hooks + settings.json snippet
```

## Prerequisites

- **JDK 21** to build. Installed via Homebrew (keg-only, not on PATH):
  ```sh
  brew install openjdk@21
  ```
  All build commands need `JAVA_HOME` pointed at it. The `plugin/dev.sh` wrapper
  does this for you:
  ```sh
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  ```
- **WebStorm 2026.1+** (build 261+). The build targets your locally installed
  `~/Applications/WebStorm.app`.
- `curl` and `jq` for the hooks (preinstalled on macOS / via `brew install jq`).

## Build & run

All commands run from `plugin/`. Use `./dev.sh <task>` (sets `JAVA_HOME`) or
`./gradlew <task>` if your `JAVA_HOME` is already exported.

```sh
cd plugin
./dev.sh test          # run unit tests
./dev.sh buildPlugin   # produce build/distributions/claude-ide-guard-<ver>.zip
./dev.sh runIde        # launch a sandbox WebStorm with the plugin loaded
```

Quick server check (with a sandbox or a real IDE running and a project open):

```sh
curl -s 127.0.0.1:7337/health           # → {"ok":true,"locks":0,"recent":0,"listeners":1,"worktreeCache":0}
```

## Install into your real WebStorm

1. Build the zip: `./dev.sh buildPlugin`.
2. WebStorm → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick
   `plugin/build/distributions/claude-ide-guard-<ver>.zip`.
3. Restart WebStorm. Open a project (the server starts on first project open).

> **Tip:** prefer a one-shot setup? Paste the prompt in
> [SETUP-PROMPT.md](SETUP-PROMPT.md) into a Claude Code session opened in this
> repo and it will build the plugin and install the hooks for you.

## HTTP Contract

The plugin exposes a loopback HTTP server on `127.0.0.1:7337` (configurable).

### `POST /acquire`

Request body:
```json
{ "path": "/abs/path/to/file", "sessionId": "agent-uuid", "mode": "write" }
```
`mode` is `"read"` or `"write"`.

Response:
```json
{ "granted": true,  "dirty": false }
{ "granted": false, "dirty": false, "heldBy": "other-session-id", "heldMode": "write" }
```

- `dirty` is `true` when `mode=write` and the file has unsaved changes in the
  IDE. The write-pre hook surfaces this as a confirmation prompt.
- `heldBy` / `heldMode` are present only when `granted:false`.

### `POST /release`

Request body:
```json
{ "path": "/abs/path/to/file", "sessionId": "agent-uuid" }
```

Response:
```json
{ "ok": true }
```

### `POST /acquire-bash`

Request body:
```json
{ "command": "cat src/a.ts > src/b.ts", "cwd": "/abs/project", "sessionId": "agent-uuid" }
```

The arbiter parses the command heuristically, keeps only paths inside an open
project root, and acquires the whole implied READ/WRITE set atomically
(all-or-nothing). Response matches `/acquire`: `granted`, `dirty` (true when any
write target has unsaved changes), and `heldBy`/`heldMode` when `granted:false`.
When Bash detection is disabled in settings, it returns `{"granted":true,"dirty":false}`
without locking anything.

### `POST /release-bash`

Request body identical to `/acquire-bash`. Re-parses the command and releases this
session's lock set. Response: `{ "ok": true }`.

### `GET /health`

Liveness plus self-diagnostics — the live sizes of the plugin's internal
tables, so you can spot a leak without a heap dump:

```json
{ "ok": true, "locks": 0, "recent": 0, "listeners": 1, "worktreeCache": 0 }
```

- `locks` — paths currently in flight (held by an agent).
- `recent` — finished writes still lingering in history (TTL-expired by the sweep).
- `listeners` — UI observers of the lock state. Expect roughly one per open
  "Claude Edits" tool window plus two internal singletons; a value that climbs
  over time without windows opening means a listener leak (the sweep also logs a
  warning past a threshold).
- `worktreeCache` — cached git worktree lookups; dropped automatically under IDE
  memory pressure.

## Connect Claude Code (install the hooks)

Claude Code talks to the plugin through three hooks. All fail open, so a broken
setup silently allows the tool — Claude is never permanently blocked.

### 1. Copy the hook scripts

```sh
mkdir -p ~/.claude/hooks
cp hooks/ide-guard-write-pre.sh hooks/ide-guard-read-pre.sh hooks/ide-guard-post.sh \
   hooks/ide-guard-bash-pre.sh hooks/ide-guard-bash-post.sh ~/.claude/hooks/
chmod +x ~/.claude/hooks/ide-guard-write-pre.sh \
         ~/.claude/hooks/ide-guard-read-pre.sh \
         ~/.claude/hooks/ide-guard-post.sh \
         ~/.claude/hooks/ide-guard-bash-pre.sh \
         ~/.claude/hooks/ide-guard-bash-post.sh
```

### 2. Register them in `~/.claude/settings.json`

If you have **no** `settings.json` yet, just copy the sample:

```sh
cp hooks/settings.snippet.json ~/.claude/settings.json
```

If you **already have** a `settings.json` with no existing hooks, merge:

```sh
cp ~/.claude/settings.json ~/.claude/settings.json.bak
jq -s '.[0] * .[1]' ~/.claude/settings.json hooks/settings.snippet.json \
  > ~/.claude/settings.json.tmp && mv ~/.claude/settings.json.tmp ~/.claude/settings.json
```

> **Note:** `jq *` deep-merges objects but **replaces** arrays. If you already
> have `PreToolUse`/`PostToolUse` entries, append the three entries from
> `hooks/settings.snippet.json` to your existing arrays by hand so nothing is
> lost.

The snippet registers:

| Event | Matcher | Hook |
|---|---|---|
| PreToolUse | `Edit\|Write\|MultiEdit\|NotebookEdit` | `ide-guard-write-pre.sh` |
| PreToolUse | `Read` | `ide-guard-read-pre.sh` |
| PreToolUse | `Bash` | `ide-guard-bash-pre.sh` |
| PostToolUse | `Edit\|Write\|MultiEdit\|NotebookEdit\|Read` | `ide-guard-post.sh` |
| PostToolUse | `Bash` | `ide-guard-bash-post.sh` |

### 3. Reload Claude Code

Start a new Claude Code session (or run `/hooks` to confirm they're picked up).

### 4. Verify end to end

1. Open the project in WebStorm (plugin running, a project open).
2. In a Claude Code session **inside that project**, ask Claude to read or edit
   a file.
3. Watch WebStorm: the file's tab/Project-view icon becomes the eye (read) or
   pencil (write) mark and it appears in the **Claude Edits** tool window while
   the operation runs, then clears.
4. Make an unsaved change to a file, then ask Claude to edit that same file —
   Claude should pause and ask for confirmation (the dirty guard).

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `CLAUDE_IDE_GUARD_PORT` | `7337` | Port the plugin listens on. |
| `CLAUDE_IDE_GUARD_WAIT_SECONDS` | `10` | How long a hook polls before giving up and allowing. |
| `CLAUDE_IDE_GUARD_POLL_MS` | `200` | Interval between polling attempts (milliseconds). |

Set them in your shell profile or in the `env` section of `~/.claude/settings.json`:

```json
{
  "env": {
    "CLAUDE_IDE_GUARD_PORT": "7337",
    "CLAUDE_IDE_GUARD_WAIT_SECONDS": "10",
    "CLAUDE_IDE_GUARD_POLL_MS": "200"
  }
}
```

## Settings

**Settings → Tools → Claude IDE Guard**

- **HTTP server port** (default `7337`). Changes apply immediately (the server
  rebinds). Loopback only.
- **Lock editor while Claude is editing** (default off). When on, any file that
  is actively locked (read or write) is made read-only in the editor so you
  can't type into it. Only your in-IDE typing is blocked — Claude's on-disk
  writes are unaffected. Auto-unlocks when the lock is released or the lease
  expires.
- **Lock lease (sec)** (default `300`). How long a held lock survives without a
  refresh before it is force-released by the sweep. This protects against
  orphaned locks left by a killed or crashed agent session.
- **Detect file access in Bash commands** (default on). When on, Bash commands
  that read or write files are parsed and take the same locks as `Read`/`Edit`.
  Heuristic — uncheck if it ever locks the wrong files.

## Troubleshooting

- **`curl: connection refused` on `/health`** — the plugin isn't running or no
  project is open yet. Open a project; check the IDE log (`Help → Show Log`) for
  `claude-ide-guard`.
- **Port already in use** — change the port in Settings (and in the hook env).
  The plugin logs a warning and disables itself rather than crashing.
- **Indicators stuck on a file** — locks self-clear after the lease expires
  (default 5 min). Editing the file again also re-syncs.
- **Hook seems to wait a long time** — another agent holds a conflicting lock.
  The hook polls until the lock is granted or `CLAUDE_IDE_GUARD_WAIT_SECONDS`
  elapses, then fails open (allows). Increase the timeout if you expect long
  operations; reduce it if you prefer faster fail-open.
- **Hooks seem to do nothing** — verify `jq`/`curl` are installed and the hook
  paths in `settings.json` are correct. Hooks fail open by design, so a
  misconfiguration silently allows edits (never blocks Claude).
- **Multi-process projects** — if you have more than one IDE instance open, each
  owns its lock table independently. Only agents connected to the same port
  (same IDE process) coordinate with each other. Agents talking to different
  IDE instances have no visibility into each other's locks.

## Bash detection

File access via Bash (`cat`, `>`, `>>`, `tee`, `sed -i`, `cp`, `mv`, `touch`,
`grep file`, …) is detected heuristically and participates in the same lock and
visibility as the built-in tools. Parsing happens in the plugin; it deliberately
**skips** anything ambiguous (globs, `$variables`, unknown commands, paths
outside the project, `rm`/`mkdir`) — biased toward missing rather than locking the
wrong file. Toggle it in **Settings → Tools → Claude IDE Guard → Detect file
access in Bash commands** (on by default).

## Out of scope (MVP)

MCP server for conversational queries; token auth; non-JetBrains editors. See
the project spec for the phase-2 notes.
