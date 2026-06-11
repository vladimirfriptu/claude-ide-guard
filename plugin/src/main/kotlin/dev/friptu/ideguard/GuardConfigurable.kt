package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** Settings page: HTTP port + optional editor lock. Applying restarts the server. */
class GuardConfigurable : Configurable {

    private var portField: JBTextField? = null
    private var lockCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "Claude IDE Guard"

    override fun createComponent(): JComponent {
        val settings = GuardSettings.getInstance()
        val field = JBTextField(settings.port.toString(), 8)
        val check = JBCheckBox("Lock editor (read-only) while Claude is editing a file", settings.lockEditorWhileEditing)
        portField = field
        lockCheckBox = check

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("HTTP server port:", field)
            .addComponent(JBLabel("Default 7337. Bound to 127.0.0.1 only. Changes apply immediately."))
            .addComponent(check)
            .addComponent(JBLabel("Blocks your typing in the IDE; Claude's writes are unaffected. Auto-unlocks when done."))
            .panel
    }

    override fun isModified(): Boolean {
        val settings = GuardSettings.getInstance()
        return portField?.text?.trim() != settings.port.toString() ||
            lockCheckBox?.isSelected != settings.lockEditorWhileEditing
    }

    override fun apply() {
        val text = portField?.text?.trim().orEmpty()
        val port = text.toIntOrNull() ?: throw ConfigurationException("Port must be a number")
        if (port !in 1..65535) throw ConfigurationException("Port must be in range 1..65535")

        val settings = GuardSettings.getInstance()
        val portChanged = settings.port != port
        settings.port = port
        settings.lockEditorWhileEditing = lockCheckBox?.isSelected ?: false

        if (portChanged) {
            ApplicationManager.getApplication().getService(GuardServer::class.java).restart()
        }
        // Reflect the (possibly toggled) lock setting on currently in-flight files.
        GuardEditorLocker.reconcile()
    }

    override fun reset() {
        val settings = GuardSettings.getInstance()
        portField?.text = settings.port.toString()
        lockCheckBox?.isSelected = settings.lockEditorWhileEditing
    }

    override fun disposeUIResources() {
        portField = null
        lockCheckBox = null
    }
}
