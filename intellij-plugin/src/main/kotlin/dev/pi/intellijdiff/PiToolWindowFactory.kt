package dev.pi.intellijdiff

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollapsiblePanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton

class PiToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(8, 8))
        val label = JBLabel("Manage external Pi Coding Agent sessions.")

        val commandArea = JBTextArea(PiAgentLauncher.externalLaunchCommand(project), 4, 30)
        commandArea.lineWrap = true
        commandArea.wrapStyleWord = false
        commandArea.isEditable = false

        val externalTokenField = JBTextField(PiDiffSettings.getInstance().state.token)
        externalTokenField.emptyText.text = "Token used by externally running Pi"

        fun attachExternalToken() {
            val token = externalTokenField.text.trim()
            if (token.isBlank()) {
                notify(project, "External Pi token cannot be blank.", NotificationType.ERROR)
                return
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    PiAgentLauncher.ensureExtensionAndServer(project, token)
                    val command = PiAgentLauncher.externalLaunchCommand(project, token)
                    ApplicationManager.getApplication().invokeLater {
                        commandArea.text = command
                        notify(project, "Attached token. Diff server is running for external Pi.", NotificationType.INFORMATION)
                    }
                } catch (t: Throwable) {
                    notify(project, "Failed to attach external Pi token: ${t.message}", NotificationType.ERROR)
                }
            }
        }

        fun copyExternalStartCommand() {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    PiAgentLauncher.ensureExtensionAndServer(project)
                    val command = PiAgentLauncher.externalLaunchCommand(project)
                    ApplicationManager.getApplication().invokeLater {
                        externalTokenField.text = PiDiffSettings.getInstance().state.token
                        commandArea.text = command
                        CopyPasteManager.getInstance().setContents(StringSelection(command))
                        notify(project, "Pi start command copied. Diff server is running.", NotificationType.INFORMATION)
                    }
                } catch (t: Throwable) {
                    notify(project, "Failed to prepare external Pi command: ${t.message}", NotificationType.ERROR)
                }
            }
        }

        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton(AllIcons.Actions.Copy).apply {
                toolTipText = "Copy External Start Command"
                addActionListener { copyExternalStartCommand() }
            })
            add(JButton(AllIcons.Actions.SetDefault).apply {
                toolTipText = "Attach External Pi Token"
                addActionListener { attachExternalToken() }
            })
        }

        val inner = JBPanel<JBPanel<*>>(BorderLayout(8, 8))
        inner.add(toolbar, BorderLayout.NORTH)
        inner.add(label, BorderLayout.CENTER)

        val externalSettings = JBPanel<JBPanel<*>>(BorderLayout(8, 8)).apply {
            add(JBLabel("External Pi token"), BorderLayout.NORTH)
            add(externalTokenField, BorderLayout.CENTER)
            add(commandArea, BorderLayout.SOUTH)
        }
        val collapsibleSettings = CollapsiblePanel(externalSettings, true)
        inner.add(collapsibleSettings, BorderLayout.SOUTH)
        panel.add(inner, BorderLayout.NORTH)

        val content = ContentFactory.getInstance().createContent(panel, "Pi", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Pi Diff Approval")
                ?.createNotification(message, type)
                ?.notify(project)
        }
    }
}
