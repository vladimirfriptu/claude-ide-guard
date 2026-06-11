package dev.friptu.ideguard

import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe registry of files Claude currently has in flight, keyed by
 * absolute path. Pure of any UI/platform concern so it can be unit-tested
 * directly; UI components observe it through [addListener].
 *
 * All mutating calls are idempotent and safe to call from any thread.
 */
@Service(Service.Level.APP)
class GuardState {

    private val map = ConcurrentHashMap<String, ClaudeEditState>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Absolute path of the file the user most recently selected in any editor.
     * Tracked for future use (e.g. richer queries); the MVP dirty gate ignores it.
     */
    @Volatile
    var activeFilePath: String? = null
        private set

    fun setActiveFile(path: String?) {
        activeFilePath = path
    }

    /** Registers a listener fired (off any thread) whenever the set changes. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** Adds or refreshes the in-flight entry for [path]. */
    fun start(path: String, sessionId: String?, now: Long) {
        val existing = map[path]
        map[path] = if (existing != null) {
            existing.copy(lastSeen = now, sessionId = sessionId ?: existing.sessionId)
        } else {
            ClaudeEditState(path, sessionId, startedAt = now, lastSeen = now)
        }
        notifyListeners()
    }

    /** Clears the in-flight entry for [path]. Returns true if something was removed. */
    fun end(path: String): Boolean {
        val removed = map.remove(path) != null
        if (removed) notifyListeners()
        return removed
    }

    fun isInFlight(path: String): Boolean = map.containsKey(path)

    /** Current entries, oldest first. Safe snapshot — never the live map. */
    fun snapshot(): List<ClaudeEditState> = map.values.sortedBy { it.startedAt }

    /**
     * Removes entries whose [ClaudeEditState.lastSeen] is older than [ttlMillis].
     * Returns the removed paths. Guards against stuck indicators when a
     * `PostToolUse` hook never fires (e.g. the session was killed mid-edit).
     */
    fun sweep(now: Long, ttlMillis: Long): List<String> {
        val expired = map.values.filter { now - it.lastSeen > ttlMillis }.map { it.path }
        if (expired.isNotEmpty()) {
            expired.forEach { map.remove(it) }
            notifyListeners()
        }
        return expired
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            runCatching { listener() }
        }
    }
}
