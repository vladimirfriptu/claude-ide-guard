package dev.friptu.ideguard

/**
 * Pure request router for the loopback API. No HTTP/platform types, fully
 * unit-testable. Fails safe: errors map to allow/granted-false without throwing.
 */
class GuardRouter(
    private val state: GuardState,
    private val dirtyChecker: DirtyChecker,
    private val clock: () -> Long,
) {
    data class Result(val status: Int, val body: String)

    fun health(): Result = Result(200, """{"ok":true}""")

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
}
