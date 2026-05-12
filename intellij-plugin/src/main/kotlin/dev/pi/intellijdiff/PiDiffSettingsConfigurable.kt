package dev.pi.intellijdiff

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class PiDiffSettingsConfigurable : Configurable {
    private val settings = PiDiffSettings.getInstance()
    private var panel: JPanel? = null
    private val portField = JBTextField()
    private val tokenField = JBTextField()
    private val piCommandField = JBTextField()
    private val approveCreates = JBCheckBox("Show diff for file creation")
    private val approveEdits = JBCheckBox("Show diff for edits")
    private val approveDeletes = JBCheckBox("Show diff for deletes")
    private val reviewMode = JComboBox(arrayOf("pre-apply", "post-prompt-review"))
    private val storeOriginalsOnDisk = JBCheckBox("Store original file contents on disk during post-prompt review")
    private val originalsCacheDir = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Originals Cache Directory"))
    }

    override fun getDisplayName(): String = "Pi Diff Approval"

    override fun createComponent(): JComponent {
        reset()
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Server port", portField)
            .addLabeledComponent("Bearer token", tokenField)
            .addLabeledComponent("Pi command", piCommandField)
            .addLabeledComponent("Review mode", reviewMode)
            .addComponent(storeOriginalsOnDisk)
            .addLabeledComponent("Originals cache directory", originalsCacheDir)
            .addComponent(approveCreates)
            .addComponent(approveEdits)
            .addComponent(approveDeletes)
            .addComponent(JBLabel("pre-apply blocks before each mutation; post-prompt-review lets Pi finish then reviews all changed files."))
            .addComponent(JBLabel("Set PI_INTELLIJ_DIFF_PORT and PI_INTELLIJ_DIFF_TOKEN for pi."))
            .panel
        return panel!!
    }

    override fun isModified(): Boolean =
        portField.text != settings.state.port.toString() ||
            tokenField.text != settings.state.token ||
            piCommandField.text != settings.state.piCommand ||
            approveCreates.isSelected != settings.state.approveCreates ||
            approveEdits.isSelected != settings.state.approveEdits ||
            approveDeletes.isSelected != settings.state.approveDeletes ||
            reviewMode.selectedItem as String != settings.state.reviewMode ||
            storeOriginalsOnDisk.isSelected != settings.state.storeOriginalsOnDisk ||
            originalsCacheDir.text != settings.state.originalsCacheDir

    override fun apply() {
        val oldPort = settings.state.port
        settings.state.port = portField.text.toIntOrNull() ?: 63345
        settings.state.token = tokenField.text.trim().ifBlank { PiDiffSettings.generateToken() }
        settings.state.piCommand = piCommandField.text.trim().ifBlank { "pi" }
        settings.state.approveCreates = approveCreates.isSelected
        settings.state.approveEdits = approveEdits.isSelected
        settings.state.approveDeletes = approveDeletes.isSelected
        settings.state.reviewMode = reviewMode.selectedItem as String
        settings.state.storeOriginalsOnDisk = storeOriginalsOnDisk.isSelected
        settings.state.originalsCacheDir = originalsCacheDir.text.trim().ifBlank { PiDiffSettings.defaultOriginalsCacheDir() }

        if (settings.state.port != oldPort) {
            try {
                PiDiffApprovalService.getInstance().restart()
            } catch (t: Throwable) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Pi Diff Approval")
                    ?.createNotification("Failed to restart Pi diff approval server: ${t.message}", NotificationType.ERROR)
                    ?.notify(null)
            }
        }
    }

    override fun reset() {
        portField.text = settings.state.port.toString()
        tokenField.text = settings.state.token
        piCommandField.text = settings.state.piCommand
        approveCreates.isSelected = settings.state.approveCreates
        approveEdits.isSelected = settings.state.approveEdits
        approveDeletes.isSelected = settings.state.approveDeletes
        reviewMode.selectedItem = settings.state.reviewMode
        storeOriginalsOnDisk.isSelected = settings.state.storeOriginalsOnDisk
        originalsCacheDir.text = settings.state.originalsCacheDir.ifBlank { PiDiffSettings.defaultOriginalsCacheDir() }
    }

    override fun disposeUIResources() {
        panel = null
    }
}
