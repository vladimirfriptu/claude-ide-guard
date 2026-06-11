package dev.friptu.ideguard

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

/** Decides whether the file at an absolute path has unsaved changes in the IDE. */
fun interface DirtyChecker {
    fun isDirty(absPath: String): Boolean
}

/**
 * Real implementation backed by [FileDocumentManager], which is
 * application-level and therefore sees unsaved documents across all open
 * projects. A file is "dirty" only if its document is loaded AND unsaved.
 */
class PlatformDirtyChecker : DirtyChecker {
    override fun isDirty(absPath: String): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        val file = LocalFileSystem.getInstance().findFileByPath(absPath) ?: return@compute false
        val fdm = FileDocumentManager.getInstance()
        val document = fdm.getCachedDocument(file) ?: return@compute false
        fdm.isDocumentUnsaved(document)
    }
}
