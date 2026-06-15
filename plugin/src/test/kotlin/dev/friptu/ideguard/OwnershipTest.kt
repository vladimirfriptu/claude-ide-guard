package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnershipTest {

    /** A project whose direct containment is a plain base-path prefix check. */
    private fun project(id: String, base: String, working: List<String> = emptyList()) =
        ProjectView(id, base, working) { path -> Ownership.isUnder(path, base) }

    @Test
    fun mainOwnsWorktreeFileWhenWorktreeNotOpen() {
        val main = project("main", "/repo/main", working = listOf("/repo/feat"))
        val projects = listOf(main)
        val path = "/repo/feat/src/a.kt"
        assertEquals("main", Ownership.ownerOf(path, projects))
        assertEquals(OwnershipBucket.WORKTREE, Ownership.bucketFor(path, main, projects))
    }

    @Test
    fun separatelyOpenedWorktreeOwnsItsFilesNoDuplication() {
        val main = project("main", "/repo/main", working = listOf("/repo/feat"))
        val feat = project("feat", "/repo/feat", working = listOf("/repo/main"))
        val projects = listOf(main, feat)
        val path = "/repo/feat/src/a.kt"
        assertEquals("feat", Ownership.ownerOf(path, projects))
        assertEquals(OwnershipBucket.OWN, Ownership.bucketFor(path, feat, projects))
        assertEquals(OwnershipBucket.NONE, Ownership.bucketFor(path, main, projects))
    }

    @Test
    fun longestBasePathWinsAmongDirectContainers() {
        val outer = project("outer", "/a")
        val inner = project("inner", "/a/b")
        val projects = listOf(outer, inner)
        assertEquals("inner", Ownership.ownerOf("/a/b/x.kt", projects))
    }

    @Test
    fun foreignPathHasNoOwner() {
        val main = project("main", "/repo/main", working = listOf("/repo/feat"))
        assertNull(Ownership.ownerOf("/other/x.kt", listOf(main)))
        assertEquals(OwnershipBucket.NONE, Ownership.bucketFor("/other/x.kt", main, listOf(main)))
    }

    @Test
    fun worktreeParentLabelShowsNameAndRelativeDir() {
        assertEquals("feat/src", Ownership.worktreeParentLabel("/repo/feat/src/a.kt", "/repo/feat"))
        assertEquals("feat", Ownership.worktreeParentLabel("/repo/feat/a.kt", "/repo/feat"))
    }

    @Test
    fun matchingWorkingRootPicksLongest() {
        val p = project("p", "/repo/main", working = listOf("/repo/feat", "/repo/feat/nested"))
        assertEquals("/repo/feat/nested", Ownership.matchingWorkingRoot("/repo/feat/nested/x.kt", p))
    }
}
