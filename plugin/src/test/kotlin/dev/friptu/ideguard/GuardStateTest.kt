package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GuardStateTest {

    @Test
    fun startAddsEditingEntry() {
        val s = GuardState()
        s.start("/a.ts", "sess1", now = 1000)
        assertTrue(s.isInFlight("/a.ts"))
        assertEquals(1, s.snapshot().size)
        assertTrue(s.snapshot()[0].isEditing)
    }

    @Test
    fun endMovesEntryToRecentNotRemoved() {
        val s = GuardState()
        s.start("/a.ts", "sess1", now = 1000)

        assertTrue(s.end("/a.ts", now = 2000))
        // No longer "in flight" (highlight clears) but still tracked as recent.
        assertFalse(s.isInFlight("/a.ts"))
        assertEquals(1, s.snapshot().size)
        val entry = s.snapshot()[0]
        assertFalse(entry.isEditing)
        assertEquals(2000L, entry.endedAt)
    }

    @Test
    fun startReactivatesARecentEntry() {
        val s = GuardState()
        s.start("/a.ts", "sess1", now = 1000)
        s.end("/a.ts", now = 2000)
        s.start("/a.ts", "sess1", now = 3000)
        assertTrue(s.isInFlight("/a.ts"))
        assertTrue(s.snapshot()[0].isEditing)
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
        assertTrue(s.end("/a.ts", now = 2000))
        assertFalse(s.end("/a.ts", now = 3000))  // already recent — safe no-op
    }

    @Test
    fun sweepMovesStaleEditingToRecent() {
        val s = GuardState()
        s.start("/fresh.ts", null, now = 40_000)
        s.start("/stale.ts", null, now = 1_000)

        // now = 50_000, active TTL = 30_000 → stale (age 49_000) moves to recent,
        // fresh (age 10_000) keeps editing.
        val changed = s.sweep(now = 50_000, activeTtlMillis = 30_000, recentTtlMillis = 900_000)

        assertEquals(listOf("/stale.ts"), changed)
        assertTrue(s.isInFlight("/fresh.ts"))
        assertFalse(s.isInFlight("/stale.ts"))
        // Still tracked, now as recent.
        assertEquals(2, s.snapshot().size)
        assertEquals(50_000L, s.snapshot().first { it.path == "/stale.ts" }.endedAt)
    }

    @Test
    fun sweepRemovesExpiredRecentEntries() {
        val s = GuardState()
        s.start("/a.ts", null, now = 1_000)
        s.end("/a.ts", now = 2_000)

        // Recent TTL = 10_000; now = 20_000 → age since end 18_000 > TTL → removed.
        val changed = s.sweep(now = 20_000, activeTtlMillis = 60_000, recentTtlMillis = 10_000)

        assertEquals(listOf("/a.ts"), changed)
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun sweepKeepsFreshRecentEntries() {
        val s = GuardState()
        s.start("/a.ts", null, now = 1_000)
        s.end("/a.ts", now = 2_000)

        val changed = s.sweep(now = 5_000, activeTtlMillis = 60_000, recentTtlMillis = 10_000)
        assertTrue(changed.isEmpty())
        assertEquals(1, s.snapshot().size)
    }

    @Test
    fun listenerFiresOnStartEndAndSweep() {
        val s = GuardState()
        val calls = AtomicInteger(0)
        s.addListener { calls.incrementAndGet() }

        s.start("/a.ts", null, now = 1000)                                   // +1
        s.end("/a.ts", now = 2000)                                           // +1
        s.end("/a.ts", now = 3000)                                           // no-op, no fire
        s.start("/b.ts", null, now = 1000)                                   // +1
        s.sweep(now = 100_000, activeTtlMillis = 10, recentTtlMillis = 10)   // +1

        assertEquals(4, calls.get())
    }
}
