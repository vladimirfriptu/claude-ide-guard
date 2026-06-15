package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorktreeResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Lays out: <root>/main (.git dir) with a linked worktree <root>/feat (.git file). */
    private fun layout(): Triple<File, File, File> {
        val root = tmp.root
        val main = File(root, "main").apply { mkdirs() }
        val feat = File(root, "feat").apply { mkdirs() }
        val mainGit = File(main, ".git").apply { mkdirs() }
        val wtAdmin = File(mainGit, "worktrees/feat").apply { mkdirs() }
        // main/.git/worktrees/feat/gitdir -> points at the worktree's .git FILE
        File(wtAdmin, "gitdir").writeText(File(feat, ".git").path + "\n")
        // feat/.git is a FILE pointing back at the admin dir
        File(feat, ".git").writeText("gitdir: ${wtAdmin.path}\n")
        return Triple(main, feat, mainGit)
    }

    @Test
    fun listsWorkingRootsFromMainCheckout() {
        val (main, feat, _) = layout()
        val roots = WorktreeResolver.allWorkingRootsUncached(main.path)
        assertEquals(2, roots.size)
        assertTrue(roots.contains(main.path))
        assertTrue(roots.contains(feat.path))
    }

    @Test
    fun listsWorkingRootsFromLinkedWorktree() {
        val (main, feat, _) = layout()
        val roots = WorktreeResolver.allWorkingRootsUncached(feat.path)
        assertEquals(2, roots.size)
        assertTrue(roots.contains(main.path))
        assertTrue(roots.contains(feat.path))
    }

    @Test
    fun noGitYieldsEmpty() {
        val plain = tmp.newFolder("plain")
        assertEquals(emptyList<String>(), WorktreeResolver.allWorkingRootsUncached(plain.path))
    }

    @Test
    fun relativeGitdirPointerResolvesWorktreeRoot() {
        val root = tmp.root
        val main = File(root, "main").apply { mkdirs() }
        val feat = File(root, "feat").apply { mkdirs() }
        val mainGit = File(main, ".git").apply { mkdirs() }
        val wtAdmin = File(mainGit, "worktrees/feat").apply { mkdirs() }
        // Write relative pointer in admin gitdir file
        val relPointer = wtAdmin.toPath().relativize(File(feat, ".git").toPath()).toString()
        File(wtAdmin, "gitdir").writeText("$relPointer\n")
        // feat/.git points back at admin dir (absolute is fine here)
        File(feat, ".git").writeText("gitdir: ${wtAdmin.path}\n")

        val roots = WorktreeResolver.allWorkingRootsUncached(main.path)
        assertTrue(roots.any { File(it).canonicalPath == feat.canonicalPath })
    }

    @Test
    fun malformedGitFileYieldsEmpty() {
        val dir = tmp.newFolder("malformed")
        File(dir, ".git").writeText("not a gitdir line")
        assertEquals(emptyList<String>(), WorktreeResolver.allWorkingRootsUncached(dir.path))
    }

    @Test
    fun expandBashRootsDisabledReturnsBasePathsOnly() {
        val (main, _, _) = layout()
        val result = WorktreeResolver.expandBashRoots(listOf(main.path), enabled = false, now = 0)
        assertEquals(listOf(main.path), result)
    }

    @Test
    fun expandBashRootsEnabledIncludesWorktrees() {
        val (main, feat, _) = layout()
        val result = WorktreeResolver.expandBashRoots(listOf(main.path), enabled = true, now = 0)
        assertTrue(result.contains(main.path))
        assertTrue(result.contains(feat.path))
    }

    @Test
    fun cachedReadServesSecondCallWithinTtl() {
        val (main, feat, _) = layout()
        val first = WorktreeResolver.allWorkingRoots(main.path, now = 1000)
        // Delete the worktree admin entry; a cached read within TTL must still see feat.
        File(main, ".git/worktrees/feat").deleteRecursively()
        val second = WorktreeResolver.allWorkingRoots(main.path, now = 1000 + 1)
        assertEquals(first, second)
        assertTrue(second.contains(feat.path))
    }
}
