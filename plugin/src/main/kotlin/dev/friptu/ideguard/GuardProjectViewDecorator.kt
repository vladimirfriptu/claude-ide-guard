package dev.friptu.ideguard

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.application.ApplicationManager

class GuardProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        val state = ApplicationManager.getApplication().getService(GuardState::class.java) ?: return
        val label = when (state.modeOf(file.path)) {
            LockMode.WRITE -> "writing"
            LockMode.READ -> "reading"
            null -> return
        }
        // Set the Claude icon here (runs fresh on every updateFromRoot) so the
        // tree badge updates reliably.
        data.setIcon(ClaudeIcons.CLAUDE)
        data.locationString = label
        data.forcedTextForeground = GuardColors.PROJECT_VIEW_FOREGROUND
    }
}
