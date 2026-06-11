package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Runs once per opened project. The server itself is application-level and
 * idempotent, so starting it from here covers the "any project is open" case
 * without per-project ports.
 */
class GuardStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val server = ApplicationManager.getApplication().getService(GuardServer::class.java)
        server.ensureStarted()
    }
}
