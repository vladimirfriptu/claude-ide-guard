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

    /** Adds or refreshes the active (editing) entry for [path]. */
    fun start(path: String, sessionId: String?, now: Long) {
        val existing = map[path]
        map[path] = if (existing != null) {
            existing.copy(lastSeen = now, endedAt = null, sessionId = sessionId ?: existing.sessionId)
        } else {
            ClaudeEditState(path, sessionId, startedAt = now, lastSeen = now, endedAt = null)
        }
        notifyListeners()
    }

    /**
     * Marks editing of [path] as finished. The entry is NOT removed — it moves
     * to the "recently edited" state (so the highlight clears but the tool
     * window keeps it for a while). Returns true if a change happened.
     */
    fun end(path: String, now: Long): Boolean {
        val existing = map[path] ?: return false
        if (!existing.isEditing) return false
        map[path] = existing.copy(endedAt = now)
        notifyListeners()
        return true
    }

    /** Whether [path] is actively being edited right now (drives highlights/lock). */
    fun isInFlight(path: String): Boolean = map[path]?.isEditing == true

    /** All tracked entries (editing + recent), oldest first. Safe snapshot. */
    fun snapshot(): List<ClaudeEditState> = map.values.sortedBy { it.startedAt }

    /**
     * Two-stage expiry:
     * - An editing entry not refreshed within [activeTtlMillis] is moved to the
     *   recent state (guards stuck highlights when `PostToolUse` never fires).
     * - A recent entry older than [recentTtlMillis] since it ended is removed.
     *
     * Returns the affected paths.
     */
    fun sweep(now: Long, activeTtlMillis: Long, recentTtlMillis: Long): List<String> {
        val changed = ArrayList<String>()
        for (entry in map.values) {
            if (entry.isEditing) {
                if (now - entry.lastSeen > activeTtlMillis) {
                    map[entry.path] = entry.copy(endedAt = now)
                    changed.add(entry.path)
                }
            } else {
                val endedAt = entry.endedAt ?: continue
                if (now - endedAt > recentTtlMillis) {
                    map.remove(entry.path)
                    changed.add(entry.path)
                }
            }
        }
        if (changed.isNotEmpty()) notifyListeners()
        return changed
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            runCatching { listener() }
        }
    }
}
