package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GuardStateTest {

    @Test
    fun startAddsEntryAndEndRemovesIt() {
        val s = GuardState()
        s.start("/a.ts", "sess1", now = 1000)
        assertTrue(s.isInFlight("/a.ts"))
        assertEquals(1, s.snapshot().size)

        assertTrue(s.end("/a.ts"))
        assertFalse(s.isInFlight("/a.ts"))
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun startIsIdempotentAndRefreshesLastSeen() {
        val s = GuardState()
        s.start("/a.ts", "sess1", now = 1000)
        s.start("/a.ts", "sess1", now = 5000)
        val entries = s.snapshot()
        assertEquals(1, entries.size)
        assertEquals(1000, entries[0].startedAt)   // preserved
        assertEquals(5000, entries[0].lastSeen)     // refreshed
    }

    @Test
    fun endIsIdempotent() {
        val s = GuardState()
        s.start("/a.ts", null, now = 1000)
        assertTrue(s.end("/a.ts"))
        assertFalse(s.end("/a.ts"))  // second end is a safe no-op
    }

    @Test
    fun sweepRemovesOnlyStaleEntries() {
        val s = GuardState()
        s.start("/fresh.ts", null, now = 40_000)
        s.start("/stale.ts", null, now = 1_000)

        // now = 50_000, ttl = 30_000 → stale (age 49_000) expires, fresh (age 10_000) stays
        val removed = s.sweep(now = 50_000, ttlMillis = 30_000)

        assertEquals(listOf("/stale.ts"), removed)
        assertTrue(s.isInFlight("/fresh.ts"))
        assertFalse(s.isInFlight("/stale.ts"))
    }

    @Test
    fun sweepWithNothingStaleReturnsEmpty() {
        val s = GuardState()
        s.start("/a.ts", null, now = 10_000)
        assertTrue(s.sweep(now = 20_000, ttlMillis = 30_000).isEmpty())
        assertTrue(s.isInFlight("/a.ts"))
    }

    @Test
    fun listenerFiresOnStartEndAndSweep() {
        val s = GuardState()
        val calls = AtomicInteger(0)
        s.addListener { calls.incrementAndGet() }

        s.start("/a.ts", null, now = 1000)   // +1
        s.end("/a.ts")                        // +1
        s.end("/a.ts")                        // no-op, no fire
        s.start("/b.ts", null, now = 1000)   // +1
        s.sweep(now = 100_000, ttlMillis = 10) // +1

        assertEquals(4, calls.get())
    }
}
