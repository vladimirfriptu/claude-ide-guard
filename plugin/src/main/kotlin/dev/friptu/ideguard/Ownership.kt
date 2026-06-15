package dev.friptu.ideguard

import java.io.File
import java.nio.file.Paths

/** Which bucket a path falls into for a given project's tool window. */
enum class OwnershipBucket { OWN, WORKTREE, NONE }

/**
 * One open project as seen by ownership resolution. [directlyContains] is true
 * when the path is inside this project's own content (IntelliJ content roots or
 * base path). [workingRoots] are the OTHER working trees of this project's
 * repository (its own base path excluded).
 */
data class ProjectView(
    val projectId: String,
    val basePath: String,
    val workingRoots: List<String>,
    val directlyContains: (String) -> Boolean,
)

/**
 * Pure ownership rules. A path belongs to exactly one open project:
 *  1. the most specific project that directly contains it (longest base path), else
 *  2. the project whose repository has a worktree containing it (longest root), else
 *  3. nobody.
 */
object Ownership {

    fun isUnder(path: String, root: String): Boolean {
        if (path == root) return true
        val r = if (root.endsWith("/")) root else "$root/"
        return path.startsWith(r)
    }

    fun ownerOf(path: String, projects: List<ProjectView>): String? {
        val direct = projects.filter { it.directlyContains(path) }
        if (direct.isNotEmpty()) return direct.maxByOrNull { it.basePath.length }!!.projectId

        var bestId: String? = null
        var bestLen = -1
        for (p in projects) {
            for (root in p.workingRoots) {
                if (isUnder(path, root) && root.length > bestLen) {
                    bestLen = root.length
                    bestId = p.projectId
                }
            }
        }
        return bestId
    }

    fun bucketFor(path: String, project: ProjectView, projects: List<ProjectView>): OwnershipBucket {
        val owner = ownerOf(path, projects) ?: return OwnershipBucket.NONE
        if (owner != project.projectId) return OwnershipBucket.NONE
        return if (project.directlyContains(path)) OwnershipBucket.OWN else OwnershipBucket.WORKTREE
    }

    /** The longest working root of [project] that contains [path], or null. */
    fun matchingWorkingRoot(path: String, project: ProjectView): String? {
        var best: String? = null
        for (root in project.workingRoots) {
            if (isUnder(path, root) && (best == null || root.length > best.length)) best = root
        }
        return best
    }

    /** Label for a worktree row: "<worktree-dir>/<dir within worktree>" (no file name). */
    fun worktreeParentLabel(path: String, worktreeRoot: String): String {
        val name = File(worktreeRoot.trimEnd('/')).name
        val parent = File(path).parentFile?.path ?: return name
        if (parent != worktreeRoot && !isUnder(parent, worktreeRoot)) return name
        val rootPath = Paths.get(worktreeRoot)
        val parentPath = Paths.get(parent)
        val rel = runCatching { rootPath.relativize(parentPath).toString() }.getOrDefault("")
        return if (rel.isEmpty()) name else "$name/$rel"
    }
}
