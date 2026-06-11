package dev.friptu.ideguard

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.ProjectManager

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
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                FileEditorManagerEx.getInstanceEx(project).refreshIcons()
                ProjectView.getInstance(project).currentProjectViewPane?.updateFromRoot(true)
            }
        }
    }
}
