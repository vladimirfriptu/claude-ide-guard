package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardRouterTest {

    private fun router(
        dirty: Set<String> = emptySet(),
        now: Long = 1000,
        bashEnabled: Boolean = true,
        roots: List<String> = listOf("/proj"),
    ): Pair<GuardRouter, GuardState> {
        val state = GuardState()
        val checker = DirtyChecker { it in dirty }
        return GuardRouter(state, checker, { bashEnabled }, { roots }) { now } to state
    }

    @Test
    fun healthOk() {
        val (r, _) = router()
        assertEquals(200, r.health().status)
        assertTrue(r.health().body.contains("\"ok\":true"))
    }

    @Test
    fun acquireWriteGrantedAndRegistersHolder() {
        val (r, state) = router()
        val res = r.acquire("""{"path":"/a","sessionId":"s1","mode":"write"}""")
        assertEquals(200, res.status)
        assertTrue(res.body.contains("\"granted\":true"))
        assertTrue(state.isInFlight("/a"))
        assertEquals(LockMode.WRITE, state.modeOf("/a"))
    }

    @Test
    fun acquireWriteDeniedWhenHeldByOther() {
        val (r, state) = router()
        r.acquire("""{"path":"/a","sessionId":"s1","mode":"write"}""")
        val res = r.acquire("""{"path":"/a","sessionId":"s2","mode":"write"}""")
        assertTrue(res.body.contains("\"granted\":false"))
        assertTrue(res.body.contains("\"heldBy\":\"s1\""))
    }

    @Test
    fun acquireReportsDirtyForWrite() {
        val (r, _) = router(dirty = setOf("/a"))
        val res = r.acquire("""{"path":"/a","sessionId":"s1","mode":"write"}""")
        assertTrue(res.body.contains("\"dirty\":true"))
    }

    @Test
    fun releaseFreesLock() {
        val (r, state) = router()
        r.acquire("""{"path":"/a","sessionId":"s1","mode":"write"}""")
        val res = r.release("""{"path":"/a","sessionId":"s1"}""")
        assertEquals(200, res.status)
        assertTrue(res.body.contains("\"ok\":true"))
    }

    @Test
    fun acquireRejectsBadInput() {
        val (r, _) = router()
        assertEquals(400, r.acquire("""{"path":"/a"}""").status)         // no mode/session
        assertEquals(400, r.acquire("""{"sessionId":"s1","mode":"read"}""").status) // no path
    }

    @Test
    fun acquireBashLocksParsedSet() {
        val (r, state) = router()
        val res = r.acquireBash("""{"command":"cat a.ts > b.ts","cwd":"/proj","sessionId":"s1"}""")
        assertEquals(200, res.status)
        assertTrue(res.body.contains("\"granted\":true"))
        assertEquals(LockMode.READ, state.modeOf("/proj/a.ts"))
        assertEquals(LockMode.WRITE, state.modeOf("/proj/b.ts"))
    }

    @Test
    fun acquireBashFiltersOutsideProjectRoot() {
        val (r, state) = router(roots = listOf("/proj"))
        r.acquireBash("""{"command":"cat /etc/hosts","cwd":"/proj","sessionId":"s1"}""")
        assertFalse(state.isInFlight("/etc/hosts"))
    }

    @Test
    fun acquireBashDisabledLocksNothing() {
        val (r, state) = router(bashEnabled = false)
        val res = r.acquireBash("""{"command":"cat a.ts","cwd":"/proj","sessionId":"s1"}""")
        assertTrue(res.body.contains("\"granted\":true"))
        assertFalse(state.isInFlight("/proj/a.ts"))
    }

    @Test
    fun acquireBashConflictDeniesWholeSet() {
        val (r, state) = router()
        state.acquire("/proj/b.ts", "s2", LockMode.WRITE, now = 5)
        val res = r.acquireBash("""{"command":"cat a.ts > b.ts","cwd":"/proj","sessionId":"s1"}""")
        assertTrue(res.body.contains("\"granted\":false"))
        assertTrue(res.body.contains("\"heldBy\":\"s2\""))
        assertFalse(state.isInFlight("/proj/a.ts")) // rolled back
    }

    @Test
    fun acquireBashReportsDirtyForWriteTarget() {
        val (r, _) = router(dirty = setOf("/proj/b.ts"))
        val res = r.acquireBash("""{"command":"echo x > b.ts","cwd":"/proj","sessionId":"s1"}""")
        assertTrue(res.body.contains("\"granted\":true"))
        assertTrue(res.body.contains("\"dirty\":true"))
    }

    @Test
    fun releaseBashFreesSet() {
        val (r, state) = router()
        r.acquireBash("""{"command":"cat a.ts > b.ts","cwd":"/proj","sessionId":"s1"}""")
        val res = r.releaseBash("""{"command":"cat a.ts > b.ts","cwd":"/proj","sessionId":"s1"}""")
        assertEquals(200, res.status)
        assertTrue(res.body.contains("\"ok\":true"))
        assertFalse(state.isInFlight("/proj/a.ts"))
        assertFalse(state.isInFlight("/proj/b.ts"))
    }

    @Test
    fun acquireBashMissingFieldsRejected() {
        val (r, _) = router()
        assertEquals(400, r.acquireBash("""{"command":"cat a.ts"}""").status) // no session
    }
}
