package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardRouterTest {

    private fun router(dirty: Set<String> = emptySet(), now: Long = 1000): Pair<GuardRouter, GuardState> {
        val state = GuardState()
        val checker = DirtyChecker { it in dirty }
        return GuardRouter(state, checker) { now } to state
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
}
