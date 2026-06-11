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

    @Volatile
    var activeFilePath: String? = null
        private set

    fun setActiveFile(path: String?) { activeFilePath = path }

    fun addListener(listener: () -> Unit) { listeners.add(listener) }

    /** Atomic test-and-set. Returns whether the lock was granted. */
    fun acquire(path: String, sessionId: String, mode: LockMode, now: Long): AcquireResult {
        val result: AcquireResult
        synchronized(mutex) {
            val lock = locks.getOrPut(path) { FileLock(now) }
            result = when (mode) {
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
            if (result.granted) recent.remove(path)
        }
        if (result.granted) notifyListeners()
        return result
    }

    /** Releases this session's hold on [path]. Returns true if something changed. */
    fun release(path: String, sessionId: String, now: Long): Boolean {
        var changed = false
        synchronized(mutex) {
            val lock = locks[path] ?: return false
            if (lock.writer?.sessionId == sessionId) {
                lock.writer = null
                recent[path] = FileView(path, LockMode.WRITE, listOf(sessionId), lock.startedAt, now)
                changed = true
            }
            if (lock.readers.remove(sessionId) != null) changed = true
            if (lock.writer == null && lock.readers.isEmpty()) locks.remove(path)
        }
        if (changed) notifyListeners()
        return changed
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
     *   (a freed writer becomes a recent entry).
     * - Recent entries older than [recentTtlMillis] are removed.
     */
    fun sweep(now: Long, leaseMillis: Long, recentTtlMillis: Long): List<String> {
        val changed = ArrayList<String>()
        synchronized(mutex) {
            for ((path, lock) in locks) {
                val staleReaders = lock.readers.filterValues { now - it.lastSeen > leaseMillis }.keys
                staleReaders.forEach { lock.readers.remove(it); changed.add(path) }
                val w = lock.writer
                if (w != null && now - w.lastSeen > leaseMillis) {
                    lock.writer = null
                    recent[path] = FileView(path, LockMode.WRITE, listOf(w.sessionId), lock.startedAt, now)
                    changed.add(path)
                }
                if (lock.writer == null && lock.readers.isEmpty()) locks.remove(path)
            }
            val expiredRecent = recent.values.filter { now - (it.endedAt ?: 0) > recentTtlMillis }.map { it.path }
            expiredRecent.forEach { recent.remove(it); changed.add(it) }
        }
        if (changed.isNotEmpty()) notifyListeners()
        return changed.distinct()
    }

    private fun notifyListeners() {
        for (listener in listeners) runCatching { listener() }
    }
}
