package dev.friptu.ideguard

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Replaces the icon of an in-flight file with the Claude mark — shown both on
 * the editor tab (visible even when the tab is active) and in the Project view.
 */
class GuardFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        val state = ApplicationManager.getApplication().getService(GuardState::class.java) ?: return null
        return if (state.isInFlight(file.path)) ClaudeIcons.CLAUDE else null
    }
}
