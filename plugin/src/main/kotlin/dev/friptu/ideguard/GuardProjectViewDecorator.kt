package dev.friptu.ideguard

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.application.ApplicationManager

/** Badges Project view nodes of in-flight files with a label + accent color. */
class GuardProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        val state = ApplicationManager.getApplication().getService(GuardState::class.java) ?: return
        if (!state.isInFlight(file.path)) return
        data.locationString = "✎ Claude"
        data.forcedTextForeground = GuardColors.PROJECT_VIEW_FOREGROUND
    }
}
