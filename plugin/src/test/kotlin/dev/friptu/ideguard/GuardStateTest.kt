package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GuardStateTest {

    @Test
    fun readsShareWriteIsExclusive() {
        val s = GuardState()
        assertTrue(s.acquire("/a", "s1", LockMode.READ, now = 10).granted)
        // second reader allowed
        assertTrue(s.acquire("/a", "s2", LockMode.READ, now = 11).granted)
        // writer blocked by readers
        val w = s.acquire("/a", "s3", LockMode.WRITE, now = 12)
        assertFalse(w.granted)
        assertEquals(LockMode.READ, w.heldMode)
    }

    @Test
    fun writerBlocksOtherReadersAndWriters() {
        val s = GuardState()
        assertTrue(s.acquire("/a", "s1", LockMode.WRITE, now = 10).granted)
        assertFalse(s.acquire("/a", "s2", LockMode.READ, now = 11).granted)
        val w = s.acquire("/a", "s2", LockMode.WRITE, now = 12)
        assertFalse(w.granted)
        assertEquals("s1", w.heldBy)
        assertEquals(LockMode.WRITE, w.heldMode)
    }

    @Test
    fun sameSessionNeverBlocksItself() {
        val s = GuardState()
        assertTrue(s.acquire("/a", "s1", LockMode.READ, now = 10).granted)
        // same session upgrades to write even though it holds a read
        assertTrue(s.acquire("/a", "s1", LockMode.WRITE, now = 11).granted)
        // re-acquire write by same session refreshes, still granted
        assertTrue(s.acquire("/a", "s1", LockMode.WRITE, now = 12).granted)
    }

    @Test
    fun releaseFreesTheLock() {
        val s = GuardState()
        s.acquire("/a", "s1", LockMode.WRITE, now = 10)
        s.release("/a", "s1", now = 20)
        assertTrue(s.acquire("/a", "s2", LockMode.WRITE, now = 21).granted)
    }

    @Test
    fun writeAndReadBothLingerAsRecentAfterRelease() {
        val s = GuardState()
        s.acquire("/w", "s1", LockMode.WRITE, now = 10)
        s.release("/w", "s1", now = 20)
        s.acquire("/r", "s1", LockMode.READ, now = 10)
        s.release("/r", "s1", now = 20)

        val views = s.snapshot()
        val w = views.first { it.path == "/w" }
        assertFalse(w.isActive)
        assertEquals(LockMode.WRITE, w.mode)
        val r = views.first { it.path == "/r" } // reads now linger so they don't flash
        assertFalse(r.isActive)
        assertEquals(LockMode.READ, r.mode)
    }

    @Test
    fun readAfterWriteKeepsEditedHistory() {
        val s = GuardState()
        s.acquire("/f", "s1", LockMode.WRITE, now = 10)
        s.release("/f", "s1", now = 20)            // recorded as a recent WRITE
        s.acquire("/f", "s1", LockMode.READ, now = 30)
        s.release("/f", "s1", now = 40)            // a later read must NOT erase the edit
        val v = s.snapshot().first { it.path == "/f" }
        assertFalse(v.isActive)
        assertEquals(LockMode.WRITE, v.mode)       // still shown as edited
    }

    @Test
    fun readRecentExpiresBeforeWriteRecent() {
        val s = GuardState()
        s.acquire("/w", "s1", LockMode.WRITE, now = 1_000); s.release("/w", "s1", now = 2_000)
        s.acquire("/r", "s1", LockMode.READ, now = 1_000); s.release("/r", "s1", now = 2_000)
        // 90s on: read recent (60s TTL) is gone, write recent (15min TTL) survives
        s.sweep(now = 92_000, leaseMillis = 300_000, recentTtlMillis = 900_000, readRecentTtlMillis = 60_000)
        assertNull(s.snapshot().firstOrNull { it.path == "/r" })
        assertEquals(LockMode.WRITE, s.snapshot().first { it.path == "/w" }.mode)
    }

    @Test
    fun isInFlightAndModeReflectHolders() {
        val s = GuardState()
        assertFalse(s.isInFlight("/a"))
        s.acquire("/a", "s1", LockMode.READ, now = 10)
        assertTrue(s.isInFlight("/a"))
        assertEquals(LockMode.READ, s.modeOf("/a"))
        s.acquire("/a", "s1", LockMode.WRITE, now = 11)
        assertEquals(LockMode.WRITE, s.modeOf("/a"))
        s.release("/a", "s1", now = 12)
        assertFalse(s.isInFlight("/a"))
    }

    @Test
    fun sweepFreesStaleHoldersThenExpiresRecent() {
        val s = GuardState()
        s.acquire("/a", "s1", LockMode.WRITE, now = 1_000)
        // lease 30s; at 40s the holder is stale -> released (and recorded recent)
        s.sweep(now = 40_000, leaseMillis = 30_000, recentTtlMillis = 1_000_000)
        assertFalse(s.isInFlight("/a"))
        assertTrue(s.snapshot().any { it.path == "/a" && !it.isActive })
        // recent TTL 10s; far in the future -> removed
        s.sweep(now = 2_000_000, leaseMillis = 30_000, recentTtlMillis = 10_000)
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun listenerFiresOnChange() {
        val s = GuardState()
        val calls = AtomicInteger(0)
        s.addListener { calls.incrementAndGet() }
        s.acquire("/a", "s1", LockMode.READ, now = 10)   // +1
        s.acquire("/a", "s1", LockMode.READ, now = 11)   // refresh -> still fires (+1)
        s.release("/a", "s1", now = 12)                  // +1
        assertTrue(calls.get() >= 3)
    }

    @Test
    fun unsubscribedListenerStopsFiring() {
        val s = GuardState()
        val calls = AtomicInteger(0)
        val subscription = s.addListener { calls.incrementAndGet() }
        s.acquire("/a", "s1", LockMode.READ, now = 10)   // fires
        val afterFirst = calls.get()
        assertTrue(afterFirst >= 1)
        subscription.unsubscribe()
        s.release("/a", "s1", now = 12)                  // must NOT fire after unsubscribe
        assertEquals(afterFirst, calls.get())
    }

    @Test
    fun listenerCountReturnsToBaselineAfterUnsubscribe() {
        val s = GuardState()
        val baseline = s.diagnostics().listeners
        val subs = (1..50).map { s.addListener { } }
        assertEquals(baseline + 50, s.diagnostics().listeners)
        subs.forEach { it.unsubscribe() }
        assertEquals(baseline, s.diagnostics().listeners)
    }

    @Test
    fun diagnosticsReportTableSizes() {
        val s = GuardState()
        s.acquire("/a", "s1", LockMode.WRITE, now = 10)   // 1 active lock
        s.release("/a", "s1", now = 20)                   // becomes 1 recent, lock cleared
        val d = s.diagnostics()
        assertEquals(0, d.locks)
        assertEquals(1, d.recent)
    }

    @Test
    fun acquireSetGrantsAllOrNothing() {
        val s = GuardState()
        // s2 holds a write on /b -> the whole set must be denied
        s.acquire("/b", "s2", LockMode.WRITE, now = 5)
        val set = listOf(FileAccess("/a", LockMode.READ), FileAccess("/b", LockMode.WRITE))
        val res = s.acquireSet(set, "s1", now = 10)
        assertFalse(res.granted)
        assertEquals("s2", res.heldBy)
        // /a must NOT have been left locked by the failed attempt (rollback)
        assertFalse(s.isInFlight("/a"))
    }

    @Test
    fun acquireSetSucceedsWhenFree() {
        val s = GuardState()
        val set = listOf(FileAccess("/a", LockMode.READ), FileAccess("/b", LockMode.WRITE))
        val res = s.acquireSet(set, "s1", now = 10)
        assertTrue(res.granted)
        assertEquals(LockMode.READ, s.modeOf("/a"))
        assertEquals(LockMode.WRITE, s.modeOf("/b"))
    }

    @Test
    fun releaseSetFreesEntireSet() {
        val s = GuardState()
        val set = listOf(FileAccess("/a", LockMode.READ), FileAccess("/b", LockMode.WRITE))
        s.acquireSet(set, "s1", now = 10)
        s.releaseSet(set.map { it.path }, "s1", now = 20)
        assertFalse(s.isInFlight("/a"))
        assertFalse(s.isInFlight("/b"))
    }

    @Test
    fun acquireSetEmptyIsGranted() {
        val s = GuardState()
        assertTrue(s.acquireSet(emptyList(), "s1", now = 10).granted)
    }

    @Test
    fun deniedAcquireSetLeavesNoRecentHistory() {
        val s = GuardState()
        // /b is write-held by another session, so the set is denied AFTER /a's write is taken
        s.acquire("/b", "s2", LockMode.WRITE, now = 5)
        val set = listOf(FileAccess("/a", LockMode.WRITE), FileAccess("/b", LockMode.WRITE))
        val res = s.acquireSet(set, "s1", now = 10)
        assertFalse(res.granted)
        // rollback must NOT leave a phantom "recently written" entry for /a
        assertTrue(s.snapshot().none { it.path == "/a" })
    }
}
