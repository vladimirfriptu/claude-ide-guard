package dev.friptu.ideguard

/**
 * Pure request router: maps a parsed request to a [Result]. No HTTP, no
 * platform types — fully unit-testable with a fake [DirtyChecker] and a fixed
 * clock. [GuardServer] adapts `HttpExchange` to/from this.
 *
 * Fails safe: the only decision that can block Claude is `ask`, returned
 * exclusively when the target file is genuinely dirty. Any error path yields
 * `allow`.
 */
class GuardRouter(
    private val state: GuardState,
    private val dirtyChecker: DirtyChecker,
    private val clock: () -> Long,
) {
    data class Result(val status: Int, val body: String)

    fun health(): Result = Result(200, """{"ok":true}""")

    /** Handles `POST /editing` with a JSON body `{path, action, sessionId}`. */
    fun editing(body: String): Result {
        val obj = MiniJson.parseFlatObject(body)
        val path = obj["path"]
        val action = obj["action"]
        val sessionId = obj["sessionId"]
        if (path.isNullOrBlank() || action.isNullOrBlank()) {
            return Result(400, MiniJson.obj("ok" to "false", "error" to "path and action are required"))
        }
        when (action) {
            "start" -> state.start(path, sessionId, clock())
            "end" -> state.end(path)
            else -> return Result(400, MiniJson.obj("ok" to "false", "error" to "unknown action: $action"))
        }
        return Result(200, """{"ok":true}""")
    }

    /** Handles `GET /check?path=<abs>`. Returns `ask` only for dirty files. */
    fun check(path: String?): Result {
        if (path.isNullOrBlank()) {
            return Result(200, MiniJson.obj("decision" to "allow", "reason" to "missing path"))
        }
        val dirty = runCatching { dirtyChecker.isDirty(path) }.getOrDefault(false)
        return if (dirty) {
            Result(200, MiniJson.obj("decision" to "ask", "reason" to DIRTY_REASON))
        } else {
            Result(200, MiniJson.obj("decision" to "allow", "reason" to "no unsaved changes in IDE"))
        }
    }

    companion object {
        const val DIRTY_REASON =
            "File has unsaved changes in WebStorm — confirm before overwriting."
    }
}
