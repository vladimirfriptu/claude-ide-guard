package dev.friptu.ideguard

import com.intellij.openapi.components.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe readers-writer lock table keyed by absolute path, plus a short
 * "recently edited" history for writes. Pure of UI/platform concerns so it is
 * unit-testable; observers subscribe via [addListener]. `now` is passed in so
 * tests are deterministic.
 */
@Service(Service.Level.APP)
class GuardState {

    private class FileLock(var startedAt: Long) {
        val readers = LinkedHashMap<String, Holder>()
        var writer: Holder? = null
    }

    private val locks = ConcurrentHashMap<String, FileLock>()
    private val recent = ConcurrentHashMap<String, FileView>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val mutex = Any()

    /** Handle to remove a previously registered listener. */
    fun interface Subscription {
        fun unsubscribe()
    }

    /** Live sizes of the internal tables — cheap self-diagnostics for leak detection. */
    data class Diagnostics(val locks: Int, val recent: Int, val listeners: Int)

    fun diagnostics(): Diagnostics =
        synchronized(mutex) { Diagnostics(locks.size, recent.size, listeners.size) }

    @Volatile
    var activeFilePath: String? = null
        private set

    fun setActiveFile(path: String?) { activeFilePath = path }

    /**
     * Registers [listener]. The returned [Subscription] removes it again — callers
     * tied to a shorter lifecycle than this app-level service (e.g. a tool window)
     * MUST unsubscribe on dispose, or they leak their captured UI on every reopen.
     */
    fun addListener(listener: () -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    /** Atomic test-and-set. Returns whether the lock was granted. */
    fun acquire(path: String, sessionId: String, mode: LockMode, now: Long): AcquireResult {
        val result: AcquireResult
        synchronized(mutex) { result = acquireLocked(path, sessionId, mode, now) }
        if (result.granted) notifyListeners()
        return result
    }

    /** Releases this session's hold on [path]. Returns true if something changed. */
    fun release(path: String, sessionId: String, now: Long): Boolean {
        val changed: Boolean
        synchronized(mutex) { changed = releaseLocked(path, sessionId, now) }
        if (changed) notifyListeners()
        return changed
    }

    /**
     * All-or-nothing acquire of a set of paths for one session. If any access in
     * the set conflicts, every lock taken during this call is rolled back and the
     * result reports the first conflicting holder. An empty set is granted.
     */
    fun acquireSet(accesses: List<FileAccess>, sessionId: String, now: Long): AcquireResult {
        var result = AcquireResult(true)
        synchronized(mutex) {
            val taken = ArrayList<String>()
            for (a in accesses) {
                val r = acquireLocked(a.path, sessionId, a.mode, now)
                if (!r.granted) {
                    taken.forEach { releaseLocked(it, sessionId, now, recordHistory = false) }
                    result = r
                    break
                }
                taken.add(a.path)
            }
        }
        if (result.granted) notifyListeners()
        return result
    }

    /** Releases this session's hold on every path in [paths]. */
    fun releaseSet(paths: List<String>, sessionId: String, now: Long): Boolean {
        var changed = false
        synchronized(mutex) {
            for (p in paths) if (releaseLocked(p, sessionId, now)) changed = true
        }
        if (changed) notifyListeners()
        return changed
    }

    /** Caller MUST hold [mutex]. Does not notify. */
    private fun acquireLocked(path: String, sessionId: String, mode: LockMode, now: Long): AcquireResult {
        val lock = locks.getOrPut(path) { FileLock(now) }
        val result = when (mode) {
            LockMode.READ -> {
                val w = lock.writer
                if (w != null && w.sessionId != sessionId) {
                    AcquireResult(false, w.sessionId, LockMode.WRITE)
                } else {
                    val prev = lock.readers[sessionId]
                    lock.readers[sessionId] = Holder(sessionId, prev?.acquiredAt ?: now, now)
                    AcquireResult(true)
                }
            }
            LockMode.WRITE -> {
                val w = lock.writer
                val otherReaders = lock.readers.keys.any { it != sessionId }
                val blockedByWriter = w != null && w.sessionId != sessionId
                when {
                    blockedByWriter -> AcquireResult(false, w!!.sessionId, LockMode.WRITE)
                    otherReaders -> AcquireResult(false, lock.readers.keys.first { it != sessionId }, LockMode.READ)
                    else -> {
                        lock.readers.remove(sessionId) // upgrade: drop our own read
                        lock.writer = Holder(sessionId, w?.acquiredAt ?: now, now)
                        AcquireResult(true)
                    }
                }
            }
        }
        // Do NOT clear recent history here: a re-acquire (e.g. reading a file we
        // just wrote) must not erase the earlier edit. While active the path is
        // deduped out of recents by [snapshot]; a later release refreshes it.
        return result
    }

    /** Caller MUST hold [mutex]. Does not notify. [recordHistory] false skips the
     *  "recent write" entry — used by rollback, where nothing was actually written. */
    private fun releaseLocked(path: String, sessionId: String, now: Long, recordHistory: Boolean = true): Boolean {
        var changed = false
        val lock = locks[path] ?: return false
        if (lock.writer?.sessionId == sessionId) {
            lock.writer = null
            if (recordHistory) recent[path] = FileView(path, LockMode.WRITE, listOf(sessionId), lock.startedAt, now)
            changed = true
        }
        if (lock.readers.remove(sessionId) != null) {
            changed = true
            // Reads linger too, so they don't flash on/off — but never downgrade
            // an existing edit record (a writer still holding it, or a recent WRITE).
            if (recordHistory && lock.writer == null) lingerRead(path, listOf(sessionId), lock.startedAt, now)
        }
        if (lock.writer == null && lock.readers.isEmpty()) locks.remove(path)
        return changed
    }

    /** Records a recent READ for [path] unless an edit (recent WRITE) is already there. */
    private fun lingerRead(path: String, sessionIds: List<String>, startedAt: Long, now: Long) {
        val existing = recent[path]
        if (existing == null || existing.mode == LockMode.READ) {
            recent[path] = FileView(path, LockMode.READ, sessionIds, startedAt, now)
        }
    }

    fun isInFlight(path: String): Boolean = synchronized(mutex) {
        val lock = locks[path] ?: return@synchronized false
        lock.writer != null || lock.readers.isNotEmpty()
    }

    fun modeOf(path: String): LockMode? = synchronized(mutex) {
        val lock = locks[path] ?: return@synchronized null
        when {
            lock.writer != null -> LockMode.WRITE
            lock.readers.isNotEmpty() -> LockMode.READ
            else -> null
        }
    }

    /** Active views (from locks) + recent views (writes), oldest first. */
    fun snapshot(): List<FileView> = synchronized(mutex) {
        val active = locks.entries.mapNotNull { (path, lock) ->
            val mode = when {
                lock.writer != null -> LockMode.WRITE
                lock.readers.isNotEmpty() -> LockMode.READ
                else -> return@mapNotNull null
            }
            val sessions = buildList {
                lock.writer?.let { add(it.sessionId) }
                addAll(lock.readers.keys)
            }.distinct()
            FileView(path, mode, sessions, lock.startedAt, endedAt = null)
        }
        val activePaths = active.mapTo(HashSet()) { it.path }
        val recents = recent.values.filter { it.path !in activePaths }
        (active + recents).sortedBy { it.startedAt }
    }

    /**
     * - Holders with [Holder.lastSeen] older than [leaseMillis] are released
     *   (a freed writer/reader becomes a recent entry).
     * - Recent WRITE entries older than [recentTtlMillis] are removed; recent READ
     *   entries use the shorter [readRecentTtlMillis] so reads don't linger as long
     *   as edits.
     */
    fun sweep(
        now: Long,
        leaseMillis: Long,
        recentTtlMillis: Long,
        readRecentTtlMillis: Long = recentTtlMillis,
    ): List<String> {
        val changed = ArrayList<String>()
        synchronized(mutex) {
            for ((path, lock) in locks) {
                val staleReaders = lock.readers.filterValues { now - it.lastSeen > leaseMillis }.keys.toList()
                staleReaders.forEach { lock.readers.remove(it); changed.add(path) }
                val w = lock.writer
                if (w != null && now - w.lastSeen > leaseMillis) {
                    lock.writer = null
                    recent[path] = FileView(path, LockMode.WRITE, listOf(w.sessionId), lock.startedAt, now)
                    changed.add(path)
                }
                // Force-released reads linger too (write-wins, same as explicit release).
                if (staleReaders.isNotEmpty() && lock.writer == null) {
                    lingerRead(path, staleReaders, lock.startedAt, now)
                }
                if (lock.writer == null && lock.readers.isEmpty()) locks.remove(path)
            }
            val expiredRecent = recent.values.filter {
                val ttl = if (it.mode == LockMode.WRITE) recentTtlMillis else readRecentTtlMillis
                now - (it.endedAt ?: 0) > ttl
            }.map { it.path }
            expiredRecent.forEach { recent.remove(it); changed.add(it) }
        }
        if (changed.isNotEmpty()) notifyListeners()
        return changed.distinct()
    }

    private fun notifyListeners() {
        for (listener in listeners) runCatching { listener() }
    }
}
