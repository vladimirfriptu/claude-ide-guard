package dev.friptu.ideguard

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.application.ApplicationManager

class GuardProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        val state = ApplicationManager.getApplication().getService(GuardState::class.java) ?: return
        val (icon, label) = when (state.modeOf(file.path)) {
            LockMode.WRITE -> ClaudeIcons.WRITE to "writing"
            LockMode.READ -> ClaudeIcons.READ to "reading"
            null -> return
        }
        // Set the node icon here (runs fresh on every updateFromRoot) so the
        // eye/axe shows IN the tree icon, not just as text.
        data.setIcon(icon)
        data.locationString = label
        data.forcedTextForeground = GuardColors.PROJECT_VIEW_FOREGROUND
    }
}
