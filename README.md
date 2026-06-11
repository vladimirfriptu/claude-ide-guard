# claude-ide-guard

A WebStorm/JetBrains plugin + Claude Code hooks that make a parallel-editing
Claude Code agent **visible** in your IDE and **guard** against overwriting your
unsaved work.

Two channels:

- **Channel B — visibility (primary).** While Claude is touching a file, that
  file lights up in WebStorm: editor **tab color**, **Project view** badge, and
  a **"Claude Edits" tool window** list.
- **Channel A — soft guard (secondary).** Before Claude edits a file that has
  **unsaved (dirty)** changes in the IDE, the hook asks you to confirm. Saved
  files are never gated — they only get the highlight.

The agent is **never blocked** by this tool. If the plugin is off, unreachable,
or errors out, the hooks fail open (allow) with a tiny timeout.

## How it works

```
Claude Code  ──PreToolUse hook──▶  POST /editing {start}   ──▶  plugin lights indicators
                                   GET  /check?path=...     ──▶  dirty? → "ask" : "allow"
Claude Code  ──PostToolUse hook─▶  POST /editing {end}      ──▶  plugin clears indicators
```

The plugin runs a single **application-level** HTTP server on `127.0.0.1:7337`
(configurable). State lives **in memory only** — no files on disk, so it can
never cause a write-write conflict. Stale entries (e.g. a killed session) expire
via a TTL sweep.

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
curl -s 127.0.0.1:7337/health           # → {"ok":true}
```

## Install into your real WebStorm

1. Build the zip: `./dev.sh buildPlugin`.
2. WebStorm → **Settings → Plugins → ⚙ → Install Plugin from Disk…** → pick
   `plugin/build/distributions/claude-ide-guard-<ver>.zip`.
3. Restart WebStorm. Open a project (the server starts on first project open).

> **Tip:** prefer a one-shot setup? Paste the prompt in
> [SETUP-PROMPT.md](SETUP-PROMPT.md) into a Claude Code session opened in this
> repo and it will build the plugin and install the hooks for you.

## Connect Claude Code (install the hooks)

Claude Code talks to the plugin through two hooks on the
`Edit|Write|MultiEdit|NotebookEdit` tools. Both fail open, so a broken setup
silently allows edits — it never blocks Claude.

### 1. Copy the hook scripts

```sh
mkdir -p ~/.claude/hooks
cp hooks/ide-guard-pre.sh hooks/ide-guard-post.sh ~/.claude/hooks/
chmod +x ~/.claude/hooks/ide-guard-pre.sh ~/.claude/hooks/ide-guard-post.sh
```

### 2. Register them in `~/.claude/settings.json`

If you have **no** `settings.json` yet, just copy the sample:

```sh
cp hooks/settings.snippet.json ~/.claude/settings.json
```

If you **already have** a `settings.json`, merge the hooks in with `jq` (backup
first):

```sh
cp ~/.claude/settings.json ~/.claude/settings.json.bak
jq -s '.[0] * .[1]' ~/.claude/settings.json hooks/settings.snippet.json \
  > ~/.claude/settings.json.tmp && mv ~/.claude/settings.json.tmp ~/.claude/settings.json
```

> Note: `*` deep-merges objects but **replaces** arrays. If you already have
> `PreToolUse`/`PostToolUse` hooks, the merge overwrites them — in that case add
> the two entries from `hooks/settings.snippet.json` to your existing arrays by
> hand instead.

### 3. Reload Claude Code

Start a new Claude Code session (or run `/hooks` to confirm they're picked up).

### 4. Verify end to end

1. Open the project in WebStorm (plugin running, a project open).
2. In a Claude Code session **inside that project**, ask Claude to edit a file.
3. Watch WebStorm: the file's tab/Project-view icon becomes the Claude mark and
   it appears in the **Claude Edits** tool window while the edit runs, then
   clears.
4. Make an unsaved change to a file, then ask Claude to edit that same file —
   Claude should pause and ask for confirmation (the dirty guard).

### Non-default port

If you changed the plugin's port, set the same value for the hooks, e.g. in
`~/.claude/settings.json` `env`, or export it where Claude Code runs:

```sh
export CLAUDE_IDE_GUARD_PORT=7338
```

## Settings

**Settings → Tools → Claude IDE Guard**

- **HTTP server port** (default `7337`). Changes apply immediately (the server
  rebinds). Loopback only.
- **Lock editor while Claude is editing** (default off). When on, an in-flight
  file's editor is made read-only so you can't type into a file Claude is
  writing. Only your in-IDE typing is blocked — Claude's on-disk writes are
  unaffected. Auto-unlocks when the edit finishes or the TTL expires.

## Troubleshooting

- **`curl: connection refused` on `/health`** — the plugin isn't running or no
  project is open yet. Open a project; check the IDE log (`Help → Show Log`) for
  `claude-ide-guard`.
- **Port already in use** — change the port in Settings (and in the hook env).
  The plugin logs a warning and disables itself rather than crashing.
- **Indicators stuck on a file** — they self-clear via the TTL sweep
  (~60 s without a refresh). Editing the file again also re-syncs.
- **Hooks seem to do nothing** — verify `jq`/`curl` are installed and the hook
  paths in `settings.json` are correct. Hooks fail open by design, so a
  misconfiguration silently allows edits (never blocks Claude).

## Out of scope (MVP)

MCP server for conversational queries; gating on saved/active files; token auth;
non-JetBrains editors. See the project spec for the phase-2 notes.
