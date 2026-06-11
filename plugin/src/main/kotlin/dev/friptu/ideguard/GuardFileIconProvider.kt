package dev.friptu.ideguard

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class GuardFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        val state = ApplicationManager.getApplication().getService(GuardState::class.java) ?: return null
        return when (state.modeOf(file.path)) {
            LockMode.WRITE -> ClaudeIcons.WRITE
            LockMode.READ -> ClaudeIcons.READ
            null -> null
        }
    }
}
