package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Optional hard guard: while the "lock editor" setting is on, the editor
 * document of an in-flight file is made read-only, so the user cannot type
 * into a file Claude is writing. Only the in-memory document is locked —
 * Claude's on-disk writes are unaffected, so the agent is never blocked.
 *
 * Unlocking happens whenever a file leaves the in-flight set (via `end` or the
 * TTL sweep) or when the setting is turned off. All mutations run on the EDT,
 * so [locked] needs no extra synchronization.
 */
object GuardEditorLocker {

    private val locked = HashSet<String>()

    fun install(state: GuardState) {
        state.addListener { reconcile() }
    }

    /** Brings document read-only flags in line with the current state + setting. */
    fun reconcile() {
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            val enabled = GuardSettings.getInstance().lockEditorWhileEditing
            val state = app.getService(GuardState::class.java)
            val desired = if (enabled) {
                state.snapshot().filter { it.isEditing }.mapTo(HashSet()) { it.path }
            } else {
                HashSet()
            }

            val fdm = FileDocumentManager.getInstance()
            val lfs = LocalFileSystem.getInstance()

            for (path in desired - locked) {
                val file = lfs.findFileByPath(path) ?: continue
                val document = fdm.getCachedDocument(file) ?: continue
                setReadOnly(document, true)
                locked.add(path)
            }
            for (path in HashSet(locked) - desired) {
                val file = lfs.findFileByPath(path)
                val document = file?.let { fdm.getCachedDocument(it) }
                if (document != null) setReadOnly(document, false)
                locked.remove(path)
            }
        }
    }

    private fun setReadOnly(document: Document, value: Boolean) {
        ApplicationManager.getApplication().runWriteAction {
            document.setReadOnly(value)
        }
    }
}
