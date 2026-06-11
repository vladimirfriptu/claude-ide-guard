package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** Settings page: HTTP port + optional editor lock + lock lease. Applying restarts the server. */
class GuardConfigurable : Configurable {

    private var portField: JBTextField? = null
    private var lockCheckBox: JBCheckBox? = null
    private var leaseField: JBTextField? = null
    private var bashCheckBox: JBCheckBox? = null

    override fun getDisplayName(): String = "Claude IDE Guard"

    override fun createComponent(): JComponent {
        val settings = GuardSettings.getInstance()
        val field = JBTextField(settings.port.toString(), 8)
        val check = JBCheckBox("Lock editor (read-only) while Claude is editing a file", settings.lockEditorWhileEditing)
        val lease = JBTextField(settings.lockLeaseSeconds.toString(), 8)
        portField = field
        lockCheckBox = check
        leaseField = lease
        val bash = JBCheckBox("Detect file access in Bash commands (cat, >, cp, sed -i, …)", settings.bashDetectionEnabled)
        bashCheckBox = bash

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("HTTP server port:", field)
            .addComponent(JBLabel("Default 7337. Bound to 127.0.0.1 only. Changes apply immediately."))
            .addComponent(check)
            .addComponent(JBLabel("Blocks your typing in the IDE; Claude's writes are unaffected. Auto-unlocks when done."))
            .addLabeledComponent("Lock lease (sec):", lease)
            .addComponent(JBLabel("How long a held lock survives without refresh before it is force-released (default 300)."))
            .addComponent(bash)
            .addComponent(JBLabel("Heuristic. Off-by-default-safe; uncheck if it locks the wrong files."))
            .panel
    }

    override fun isModified(): Boolean {
        val settings = GuardSettings.getInstance()
        return portField?.text?.trim() != settings.port.toString() ||
            lockCheckBox?.isSelected != settings.lockEditorWhileEditing ||
            leaseField?.text?.trim() != settings.lockLeaseSeconds.toString() ||
            bashCheckBox?.isSelected != settings.bashDetectionEnabled
    }

    override fun apply() {
        val portText = portField?.text?.trim().orEmpty()
        val port = portText.toIntOrNull() ?: throw ConfigurationException("Port must be a number")
        if (port !in 1..65535) throw ConfigurationException("Port must be in range 1..65535")

        val leaseText = leaseField?.text?.trim().orEmpty()
        val lease = leaseText.toIntOrNull() ?: throw ConfigurationException("Lock lease must be a number")
        if (lease < 1) throw ConfigurationException("Lock lease must be >= 1")

        val settings = GuardSettings.getInstance()
        val portChanged = settings.port != port
        settings.port = port
        settings.lockEditorWhileEditing = lockCheckBox?.isSelected ?: false
        settings.bashDetectionEnabled = bashCheckBox?.isSelected ?: true
        settings.lockLeaseSeconds = lease

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
        bashCheckBox?.isSelected = settings.bashDetectionEnabled
        leaseField?.text = settings.lockLeaseSeconds.toString()
    }

    override fun disposeUIResources() {
        portField = null
        lockCheckBox = null
        leaseField = null
        bashCheckBox = null
    }
}
