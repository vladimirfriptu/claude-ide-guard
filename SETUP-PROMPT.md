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
4. `mkdir -p ~/.claude/hooks` and copy `hooks/ide-guard-pre.sh` and
   `hooks/ide-guard-post.sh` there, then `chmod +x` both.
5. Register the hooks in `~/.claude/settings.json`:
   - If the file does not exist, copy `hooks/settings.snippet.json` to it.
   - If it exists, FIRST back it up to `~/.claude/settings.json.bak`, then
     deep-merge the snippet with jq:
     `jq -s '.[0] * .[1]' ~/.claude/settings.json hooks/settings.snippet.json > tmp && mv tmp ~/.claude/settings.json`
   - IMPORTANT: jq's `*` replaces arrays. If I already have `PreToolUse` or
     `PostToolUse` hooks, do NOT clobber them — instead append the two entries
     from `hooks/settings.snippet.json` to my existing arrays. Verify with
     `jq '.hooks | keys' ~/.claude/settings.json` that nothing I had was lost.

VERIFY
6. Print a short checklist of what you did, then tell me the remaining
   GUI-only steps that you cannot do:
   - In WebStorm: Settings -> Plugins -> gear -> Install Plugin from Disk ->
     pick the zip from step 3, then restart WebStorm and open a project.
   - Start a fresh Claude Code session so the new hooks load.
   - Test: ask Claude to edit a file in that project and watch WebStorm light
     up the file (tab/Project-view icon + "Claude Edits" tool window).

Do not attempt to install the plugin into WebStorm yourself or launch its GUI —
those steps are mine. Keep everything fail-open: if anything is uncertain,
report it instead of guessing.
```
