package dev.friptu.ideguard

/**
 * One "in-flight" file that a Claude session is currently editing.
 *
 * @param path absolute file path (the map key)
 * @param sessionId originating Claude session id, if the hook supplied one
 * @param startedAt epoch millis of the first `start` for this path
 * @param lastSeen epoch millis of the most recent `start` refresh; drives TTL
 */
data class ClaudeEditState(
    val path: String,
    val sessionId: String?,
    val startedAt: Long,
    val lastSeen: Long,
)
