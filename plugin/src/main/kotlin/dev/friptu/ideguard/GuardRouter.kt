package dev.friptu.ideguard

/**
 * Pure request router for the loopback API. No HTTP/platform types, fully
 * unit-testable. Fails safe: errors map to allow/granted-false without throwing.
 */
class GuardRouter(
    private val state: GuardState,
    private val dirtyChecker: DirtyChecker,
    private val bashEnabled: () -> Boolean = { true },
    private val projectRoots: () -> List<String> = { emptyList() },
    private val worktreeCacheSize: () -> Int = { 0 },
    private val clock: () -> Long,
) {
    data class Result(val status: Int, val body: String)

    /** Liveness + self-diagnostics: sizes of the internal tables, to spot leaks. */
    fun health(): Result {
        val d = state.diagnostics()
        val body = "{\"ok\":true,\"locks\":${d.locks},\"recent\":${d.recent}," +
            "\"listeners\":${d.listeners},\"worktreeCache\":${worktreeCacheSize()}}"
        return Result(200, body)
    }

    /** `POST /acquire {path, sessionId, mode}`. */
    fun acquire(body: String): Result {
        val obj = MiniJson.parseFlatObject(body)
        val path = obj["path"]
        val sessionId = obj["sessionId"]
        val modeRaw = obj["mode"]
        if (path.isNullOrBlank() || sessionId.isNullOrBlank() || modeRaw.isNullOrBlank()) {
            return Result(400, MiniJson.obj("granted" to "false", "error" to "path, sessionId, mode required"))
        }
        val mode = when (modeRaw.lowercase()) {
            "read" -> LockMode.READ
            "write" -> LockMode.WRITE
            else -> return Result(400, MiniJson.obj("granted" to "false", "error" to "bad mode"))
        }
        val res = state.acquire(path, sessionId, mode, clock())
        val dirty = if (mode == LockMode.WRITE) runCatching { dirtyChecker.isDirty(path) }.getOrDefault(false) else false
        val parts = mutableListOf(
            "\"granted\":${res.granted}",
            "\"dirty\":$dirty",
        )
        res.heldBy?.let { parts.add("\"heldBy\":\"${MiniJson.escape(it)}\"") }
        res.heldMode?.let { parts.add("\"heldMode\":\"${it.name.lowercase()}\"") }
        return Result(200, parts.joinToString(prefix = "{", postfix = "}", separator = ","))
    }

    /** `POST /release {path, sessionId}`. */
    fun release(body: String): Result {
        val obj = MiniJson.parseFlatObject(body)
        val path = obj["path"]
        val sessionId = obj["sessionId"]
        if (path.isNullOrBlank() || sessionId.isNullOrBlank()) {
            return Result(400, MiniJson.obj("ok" to "false", "error" to "path, sessionId required"))
        }
        state.release(path, sessionId, clock())
        return Result(200, """{"ok":true}""")
    }

    /** `POST /acquire-bash {command, cwd, sessionId}`. */
    fun acquireBash(body: String): Result {
        val obj = MiniJson.parseFlatObject(body)
        val command = obj["command"]
        val cwd = obj["cwd"]
        val sessionId = obj["sessionId"]
        if (command.isNullOrBlank() || sessionId.isNullOrBlank()) {
            return Result(400, MiniJson.obj("granted" to "false", "error" to "command, sessionId required"))
        }
        if (!bashEnabled()) return Result(200, """{"granted":true,"dirty":false}""")

        val parsed = BashCommandParser.parse(command, cwd, System.getProperty("user.home"))
        val accesses = parsed.filter { withinRoots(it.path) }
        val res = state.acquireSet(accesses, sessionId, clock())
        val dirty = res.granted && accesses.any { a ->
            a.mode == LockMode.WRITE && runCatching { dirtyChecker.isDirty(a.path) }.getOrDefault(false)
        }
        val parts = mutableListOf("\"granted\":${res.granted}", "\"dirty\":$dirty")
        res.heldBy?.let { parts.add("\"heldBy\":\"${MiniJson.escape(it)}\"") }
        res.heldMode?.let { parts.add("\"heldMode\":\"${it.name.lowercase()}\"") }
        return Result(200, parts.joinToString(prefix = "{", postfix = "}", separator = ","))
    }

    /** `POST /release-bash {command, cwd, sessionId}`. Re-parses to free the same set. */
    fun releaseBash(body: String): Result {
        val obj = MiniJson.parseFlatObject(body)
        val command = obj["command"]
        val cwd = obj["cwd"]
        val sessionId = obj["sessionId"]
        if (command.isNullOrBlank() || sessionId.isNullOrBlank()) {
            return Result(400, MiniJson.obj("ok" to "false", "error" to "command, sessionId required"))
        }
        if (bashEnabled()) {
            val parsed = BashCommandParser.parse(command, cwd, System.getProperty("user.home"))
            val paths = parsed.filter { withinRoots(it.path) }.map { it.path }
            state.releaseSet(paths, sessionId, clock())
        }
        return Result(200, """{"ok":true}""")
    }

    /** True if [path] is inside any open project root. With no roots, nothing matches. */
    private fun withinRoots(path: String): Boolean {
        val roots = projectRoots()
        return roots.any { root ->
            val r = if (root.endsWith("/")) root else "$root/"
            path == root || path.startsWith(r)
        }
    }
}
