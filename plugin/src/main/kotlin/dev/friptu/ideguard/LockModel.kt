package dev.friptu.ideguard

/** Whether an agent wants shared (read) or exclusive (write) access. */
enum class LockMode { READ, WRITE }

/** One agent holding (or having held) a lock on a path. */
data class Holder(val sessionId: String, val acquiredAt: Long, val lastSeen: Long)

/** Result of an [GuardState.acquire] attempt. */
data class AcquireResult(
    val granted: Boolean,
    val heldBy: String? = null,
    val heldMode: LockMode? = null,
)

/** One file touched by a Bash command, with the access mode it implies. */
data class FileAccess(val path: String, val mode: LockMode)

/**
 * Derived view of one path for the UI. [mode] is WRITE when a writer holds or
 * recently held it, else READ. [endedAt] null means active (in flight).
 */
data class FileView(
    val path: String,
    val mode: LockMode,
    val sessionIds: List<String>,
    val startedAt: Long,
    val endedAt: Long?,
) {
    val isActive: Boolean get() = endedAt == null
}
