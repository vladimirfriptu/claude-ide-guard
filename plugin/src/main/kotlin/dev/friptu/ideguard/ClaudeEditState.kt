package dev.friptu.ideguard

/**
 * One file tracked by the plugin.
 *
 * While [endedAt] is null the file is actively being edited ("in flight") and
 * drives the icon highlights, the editor lock and the tool window's top list.
 * Once the edit ends (or the active TTL expires) [endedAt] is set and the entry
 * lingers in the tool window's "Recently edited" section until the recent TTL.
 *
 * @param path absolute file path (the map key)
 * @param sessionId originating Claude session id, if the hook supplied one
 * @param startedAt epoch millis of the first `start` for this path
 * @param lastSeen epoch millis of the most recent `start` refresh; drives the active TTL
 * @param endedAt epoch millis when editing ended; null while editing
 */
data class ClaudeEditState(
    val path: String,
    val sessionId: String?,
    val startedAt: Long,
    val lastSeen: Long,
    val endedAt: Long? = null,
) {
    val isEditing: Boolean get() = endedAt == null
}
