package dev.pi.intellijdiff

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton

class PiToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(8, 8))
        val label = JBLabel("Start Pi Coding Agent at the project root.")
        val startButton = JButton("Start Pi Agent")
        startButton.addActionListener { PiAgentLauncher.start(project) }

        val commandArea = JBTextArea(PiAgentLauncher.externalLaunchCommand(project), 4, 30)
        commandArea.lineWrap = true
        commandArea.wrapStyleWord = false
        commandArea.isEditable = false

        val copyButton = JButton("Copy External Start Command")
        copyButton.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    PiAgentLauncher.ensureExtensionAndServer(project)
                    val command = PiAgentLauncher.externalLaunchCommand(project)
                    ApplicationManager.getApplication().invokeLater {
                        commandArea.text = command
                        CopyPasteManager.getInstance().setContents(StringSelection(command))
                        notify(project, "Pi start command copied. Diff server is running.", NotificationType.INFORMATION)
                    }
                } catch (t: Throwable) {
                    notify(project, "Failed to prepare external Pi command: ${t.message}", NotificationType.ERROR)
                }
            }
        }

        val inner = JBPanel<JBPanel<*>>(BorderLayout(8, 8))
        inner.add(label, BorderLayout.NORTH)

        val buttons = JBPanel<JBPanel<*>>(BorderLayout(8, 8))
        buttons.add(startButton, BorderLayout.NORTH)
        buttons.add(copyButton, BorderLayout.SOUTH)
        inner.add(buttons, BorderLayout.CENTER)
        inner.add(commandArea, BorderLayout.SOUTH)
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
