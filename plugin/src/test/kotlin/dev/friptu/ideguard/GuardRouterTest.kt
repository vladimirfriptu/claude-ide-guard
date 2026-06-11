package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardRouterTest {

    private fun router(
        state: GuardState = GuardState(),
        dirty: Set<String> = emptySet(),
        now: Long = 1000,
    ): Pair<GuardRouter, GuardState> {
        val checker = DirtyChecker { path -> path in dirty }
        return GuardRouter(state, checker) { now } to state
    }

    @Test
    fun healthIsOk() {
        val (r, _) = router()
        assertEquals(200, r.health().status)
        assertTrue(r.health().body.contains("\"ok\":true"))
    }

    @Test
    fun editingStartRegistersInState() {
        val (r, state) = router()
        val res = r.editing("""{"path":"/a.ts","action":"start","sessionId":"s1"}""")
        assertEquals(200, res.status)
        assertTrue(state.isInFlight("/a.ts"))
    }

    @Test
    fun editingEndClearsState() {
        val (r, state) = router()
        r.editing("""{"path":"/a.ts","action":"start","sessionId":"s1"}""")
        r.editing("""{"path":"/a.ts","action":"end","sessionId":"s1"}""")
        assertFalse(state.isInFlight("/a.ts"))
    }

    @Test
    fun editingRejectsMissingFields() {
        val (r, _) = router()
        assertEquals(400, r.editing("""{"action":"start"}""").status)
        assertEquals(400, r.editing("""{"path":"/a.ts"}""").status)
    }

    @Test
    fun editingRejectsUnknownAction() {
        val (r, _) = router()
        assertEquals(400, r.editing("""{"path":"/a.ts","action":"frobnicate"}""").status)
    }

    @Test
    fun checkReturnsAskOnlyForDirtyFile() {
        val (r, _) = router(dirty = setOf("/dirty.ts"))

        val dirtyRes = r.check("/dirty.ts")
        assertEquals(200, dirtyRes.status)
        assertTrue(dirtyRes.body.contains("\"decision\":\"ask\""))

        val cleanRes = r.check("/clean.ts")
        assertEquals(200, cleanRes.status)
        assertTrue(cleanRes.body.contains("\"decision\":\"allow\""))
    }

    @Test
    fun checkAllowsWhenPathMissing() {
        val (r, _) = router()
        val res = r.check(null)
        assertTrue(res.body.contains("\"decision\":\"allow\""))
    }

    @Test
    fun checkFailsOpenWhenCheckerThrows() {
        val throwing = DirtyChecker { error("boom") }
        val r = GuardRouter(GuardState(), throwing) { 1000 }
        val res = r.check("/a.ts")
        assertEquals(200, res.status)
        assertTrue(res.body.contains("\"decision\":\"allow\""))
    }
}
