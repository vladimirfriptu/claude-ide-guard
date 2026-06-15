package dev.friptu.ideguard

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the git working-tree roots of a repository, given any one working
 * tree's root path. Pure filesystem reads (no IntelliJ types) so it is
 * unit-testable on temp dirs. Fail-safe: anything unexpected yields empty.
 */
object WorktreeResolver {

    /**
     * All working-tree roots of the repository that [basePath] belongs to,
     * including [basePath]'s own checkout. Empty when there is no git or it
     * cannot be parsed. Paths are returned as-is (no symlink canonicalization)
     * so they match the absolute paths agents report.
     */
    fun allWorkingRootsUncached(basePath: String): List<String> {
        val gitPath = File(basePath, ".git")
        val commonGitDir: File
        val mainRoot: File
        when {
            gitPath.isDirectory -> {
                commonGitDir = gitPath
                mainRoot = File(basePath)
            }
            gitPath.isFile -> {
                val adminDir = parseGitdirFile(gitPath) ?: return emptyList()
                // adminDir == <commonGitDir>/worktrees/<id>
                val adminParentDir = adminDir.parentFile ?: return emptyList()
                commonGitDir = adminParentDir.parentFile ?: return emptyList()
                mainRoot = commonGitDir.parentFile ?: return emptyList()
            }
            else -> return emptyList()
        }
        val roots = LinkedHashSet<String>()
        roots.add(mainRoot.path)
        val worktreesDir = File(commonGitDir, "worktrees")
        val entries = worktreesDir.listFiles() ?: emptyArray()
        for (dir in entries) {
            if (!dir.isDirectory) continue
            val gitdirFile = File(dir, "gitdir")
            if (!gitdirFile.isFile) continue
            val pointer = runCatching { gitdirFile.readText().trim() }.getOrNull() ?: continue
            if (pointer.isEmpty()) continue
            // pointer is the worktree's ".git" FILE; its parent is the worktree root.
            val pointed = File(pointer)
            val wtGitFile = if (pointed.isAbsolute) pointed else File(gitdirFile.parentFile, pointer)
            val wtRoot = wtGitFile.parentFile ?: continue
            roots.add(wtRoot.path)
        }
        return roots.toList()
    }

    private data class Entry(val roots: List<String>, val readAt: Long)

    private const val TTL_MILLIS = 5_000L
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Number of cached base paths — exposed for self-diagnostics. */
    fun cacheSize(): Int = cache.size

    /** Drops every cached entry; safe to call anytime (next read re-populates). */
    fun clearCache() = cache.clear()

    /** Cached wrapper over [allWorkingRootsUncached]; re-reads after [TTL_MILLIS]. */
    fun allWorkingRoots(basePath: String, now: Long = System.currentTimeMillis()): List<String> {
        val hit = cache[basePath]
        if (hit != null && now - hit.readAt < TTL_MILLIS) return hit.roots
        val roots = allWorkingRootsUncached(basePath)
        cache[basePath] = Entry(roots, now)
        return roots
    }

    /**
     * Bash lock roots: union of every base path's repo working roots when
     * worktree activity is [enabled], else just the distinct base paths.
     */
    fun expandBashRoots(
        basePaths: List<String>,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        if (!enabled) return basePaths.distinct()
        val out = LinkedHashSet<String>()
        for (base in basePaths) {
            out.add(base)
            out.addAll(allWorkingRoots(base, now))
        }
        return out.toList()
    }

    /** Reads a `.git` file's "gitdir: <path>" line and returns the admin dir, or null. */
    private fun parseGitdirFile(gitFile: File): File? {
        val text = runCatching { gitFile.readText().trim() }.getOrNull() ?: return null
        val prefix = "gitdir:"
        if (!text.startsWith(prefix)) return null
        val path = text.removePrefix(prefix).trim()
        if (path.isEmpty()) return null
        val pointed = File(path)
        return if (pointed.isAbsolute) pointed else File(gitFile.parentFile, path)
    }
}
