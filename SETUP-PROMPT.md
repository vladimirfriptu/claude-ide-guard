# One-shot setup prompt

Copy everything in the block below and paste it into a **Claude Code** session
started **inside this repository**. Claude will build the plugin and wire up the
hooks for you, then tell you the few GUI-only steps that remain.

```text
You are setting up the "claude-ide-guard" project in the current repository.
Do the following, stopping and reporting if any step fails. Never delete or
overwrite my files without a backup.

PREREQUISITES
1. Check for a JDK 21. Run `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java -version`.
   If missing, run `brew install openjdk@21` (no sudo; it is keg-only).
2. Confirm `jq` and `curl` exist (`command -v jq curl`). If `jq` is missing,
   run `brew install jq`.

BUILD THE PLUGIN
3. From `plugin/`, build the installable zip:
   `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew buildPlugin`
   (or just `./dev.sh buildPlugin`). Report the path of the produced
   `build/distributions/claude-ide-guard-*.zip`.

INSTALL THE HOOKS (into ~/.claude)
4. Create the hooks directory and copy all three hook scripts:
   ```
   mkdir -p ~/.claude/hooks
   cp hooks/ide-guard-write-pre.sh hooks/ide-guard-read-pre.sh hooks/ide-guard-post.sh ~/.claude/hooks/
   chmod +x ~/.claude/hooks/ide-guard-write-pre.sh \
            ~/.claude/hooks/ide-guard-read-pre.sh \
            ~/.claude/hooks/ide-guard-post.sh
   ```
5. Register the hooks in `~/.claude/settings.json`:
   - If the file does not exist, copy `hooks/settings.snippet.json` to it:
     `cp hooks/settings.snippet.json ~/.claude/settings.json`
   - If it exists, FIRST back it up:
     `cp ~/.claude/settings.json ~/.claude/settings.json.bak`
     Then check whether it already has PreToolUse or PostToolUse entries:
     `jq '.hooks | keys' ~/.claude/settings.json`
   - If there are NO existing PreToolUse/PostToolUse entries, merge safely:
     `jq -s '.[0] * .[1]' ~/.claude/settings.json hooks/settings.snippet.json > /tmp/settings_merged.json && mv /tmp/settings_merged.json ~/.claude/settings.json`
   - If PreToolUse or PostToolUse entries ALREADY EXIST, do NOT clobber them.
     Instead, append each of the three new hook entries from
     `hooks/settings.snippet.json` to the existing arrays by hand using jq:
     - Append write-pre to PreToolUse:
       `jq '.hooks.PreToolUse += [{"matcher":"Edit|Write|MultiEdit|NotebookEdit","hooks":[{"type":"command","command":"~/.claude/hooks/ide-guard-write-pre.sh"}]}]' ~/.claude/settings.json > /tmp/s.json && mv /tmp/s.json ~/.claude/settings.json`
     - Append read-pre to PreToolUse:
       `jq '.hooks.PreToolUse += [{"matcher":"Read","hooks":[{"type":"command","command":"~/.claude/hooks/ide-guard-read-pre.sh"}]}]' ~/.claude/settings.json > /tmp/s.json && mv /tmp/s.json ~/.claude/settings.json`
     - Append post to PostToolUse:
       `jq '.hooks.PostToolUse += [{"matcher":"Edit|Write|MultiEdit|NotebookEdit|Read","hooks":[{"type":"command","command":"~/.claude/hooks/ide-guard-post.sh"}]}]' ~/.claude/settings.json > /tmp/s.json && mv /tmp/s.json ~/.claude/settings.json`
   - Verify nothing was lost: `jq '.hooks | keys' ~/.claude/settings.json`

VERIFY
6. Print a short checklist of what you did, then tell me the remaining
   GUI-only steps that you cannot do:
   - In WebStorm: Settings -> Plugins -> gear -> Install Plugin from Disk ->
     pick the zip from step 3, then restart WebStorm and open a project.
   - Start a fresh Claude Code session so the new hooks load.
   - Test: ask Claude to read or edit a file in that project and watch WebStorm
     light up the file (eye for read, pencil for write, in tab/Project-view
     icon + "Claude Edits" tool window).

Do not attempt to install the plugin into WebStorm yourself or launch its GUI —
those steps are mine. Keep everything fail-open: if anything is uncertain,
report it instead of guessing.
```
