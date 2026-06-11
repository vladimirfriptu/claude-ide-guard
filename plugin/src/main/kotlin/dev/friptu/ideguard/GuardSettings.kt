package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** Persisted plugin settings (application-level). */
@Service(Service.Level.APP)
@State(name = "ClaudeIdeGuardSettings", storages = [Storage("claude-ide-guard.xml")])
class GuardSettings : PersistentStateComponent<GuardSettings.State> {

    data class State(
        var port: Int = GuardServer.DEFAULT_PORT,
        var lockEditorWhileEditing: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(loaded: State) { state = loaded }

    var port: Int
        get() = state.port
        set(value) { state.port = value }

    var lockEditorWhileEditing: Boolean
        get() = state.lockEditorWhileEditing
        set(value) { state.lockEditorWhileEditing = value }

    companion object {
        fun getInstance(): GuardSettings =
            ApplicationManager.getApplication().getService(GuardSettings::class.java)
    }
}
