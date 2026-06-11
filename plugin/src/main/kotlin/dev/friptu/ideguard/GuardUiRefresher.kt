package dev.friptu.ideguard

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.IconDeferrer

/**
 * Bridges [GuardState] changes (fired off any thread) to UI repaints on the
 * EDT, across all open projects: editor tab colors and the Project view.
 *
 * Registered exactly once (from [GuardServer.ensureStarted]).
 */
object GuardUiRefresher {

    fun install(state: GuardState) {
        state.addListener { refreshAll() }
    }

    private fun refreshAll() {
        val app = ApplicationManager.getApplication()
        app.invokeLater {
            // File icons (tab + Project view) are served from a deferred cache
            // keyed by PSI mod count, which our lock changes don't bump — clear
            // it so eye/axe badges appear and disappear promptly everywhere.
            IconDeferrer.getInstance().clearCache()
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                FileEditorManagerEx.getInstanceEx(project).refreshIcons()
                ProjectView.getInstance(project).currentProjectViewPane?.updateFromRoot(true)
            }
        }
    }
}
