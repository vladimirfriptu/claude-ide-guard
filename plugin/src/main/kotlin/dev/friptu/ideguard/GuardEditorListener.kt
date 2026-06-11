package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener

/**
 * Records the currently selected file into [GuardState]. Stored for future
 * features; the MVP gate keys only off dirty state.
 */
class GuardEditorListener : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
        val state = ApplicationManager.getApplication().getService(GuardState::class.java)
        state.setActiveFile(event.newFile?.path)
    }
}
