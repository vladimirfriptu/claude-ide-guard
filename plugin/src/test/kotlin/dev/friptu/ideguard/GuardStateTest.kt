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
    fun writeReleaseLingersAsRecentReadReleaseDoesNot() {
        val s = GuardState()
        s.acquire("/w", "s1", LockMode.WRITE, now = 10)
        s.release("/w", "s1", now = 20)
        s.acquire("/r", "s1", LockMode.READ, now = 10)
        s.release("/r", "s1", now = 20)

        val views = s.snapshot()
        val w = views.first { it.path == "/w" }
        assertFalse(w.isActive)
        assertEquals(LockMode.WRITE, w.mode)
        assertNull(views.firstOrNull { it.path == "/r" }) // read leaves nothing behind
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
