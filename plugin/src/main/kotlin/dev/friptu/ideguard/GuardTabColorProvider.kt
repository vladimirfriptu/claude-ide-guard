package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/** Tints editor tabs of files Claude currently has in flight. */
class GuardTabColorProvider : EditorTabColorProvider {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? {
        val state = ApplicationManager.getApplication().getService(GuardState::class.java) ?: return null
        return if (state.isInFlight(file.path)) GuardColors.TAB_BACKGROUND else null
    }
}
