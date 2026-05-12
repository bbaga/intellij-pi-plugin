package dev.pi.intellijdiff

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.ui.content.ContentManager
import org.jetbrains.plugins.terminal.startup.TerminalProcessType
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

object PiAgentLauncher {
    fun start(project: Project, @Suppress("UNUSED_PARAMETER") contentManager: ContentManager? = null) {
        thread(name = "pi-agent-launcher", isDaemon = true) {
            val projectRoot = project.basePath
            if (projectRoot.isNullOrBlank()) {
                notify(project, "Cannot start Pi: project has no local root", NotificationType.ERROR)
                return@thread
            }

            try {
                ApplicationManager.getApplication().invokeAndWait {
                    val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(projectRoot)
                    if (rootFile == null || !rootFile.isDirectory) {
                        throw IllegalStateException("Project root not found: $projectRoot")
                    }
                }

                installPiExtension(project)
                val settings = PiDiffSettings.getInstance().state
                val service = PiDiffApprovalService.getInstance()
                service.start()
                waitForServer(settings)

                ApplicationManager.getApplication().invokeLater {
                    try {
                        TerminalToolWindowTabsManager.getInstance(project)
                            .createTabBuilder()
                            .workingDirectory(projectRoot)
                            .tabName("Pi Agent")
                            .processType(TerminalProcessType.SHELL)
                            .requestFocus(true)
                            .createTab()
                            .view
                            .createSendTextBuilder()
                            .shouldExecute()
                            .send(buildLaunchCommand(settings))
                    } catch (t: Throwable) {
                        notify(project, "Failed to open Pi terminal: ${t.message}", NotificationType.ERROR)
                    }
                }
            } catch (t: Throwable) {
                notify(project, "Failed to start Pi agent: ${t.message}", NotificationType.ERROR)
            }
        }
    }

    private fun waitForServer(settings: PiDiffSettings.State) {
        val deadline = System.currentTimeMillis() + 5_000
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val connection = URL("http://127.0.0.1:${settings.port}/api/pi/health").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 300
                connection.readTimeout = 300
                if (connection.responseCode == 200) return
                lastError = IllegalStateException("HTTP ${connection.responseCode}")
            } catch (t: Throwable) {
                lastError = t
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("Diff approval server did not become ready on port ${settings.port}: ${lastError?.message}")
    }

    fun externalLaunchCommand(project: Project, tokenOverride: String? = null): String {
        val settings = PiDiffSettings.getInstance().state
        val projectRoot = project.basePath
        val cd = if (projectRoot.isNullOrBlank()) "" else "cd ${shellQuote(projectRoot)} && "
        return cd + buildLaunchCommand(settings, tokenOverride?.trim()?.ifBlank { null } ?: settings.token)
    }

    fun ensureExtensionAndServer(project: Project, tokenOverride: String? = null) {
        val customToken = tokenOverride?.trim()?.ifBlank { null }
        val settings = PiDiffSettings.getInstance().state
        if (customToken != null && settings.token != customToken) {
            settings.token = customToken
        }
        installPiExtension(project)
        PiDiffApprovalService.getInstance().start()
        waitForServer(settings)
    }

    private fun buildLaunchCommand(settings: PiDiffSettings.State): String = buildLaunchCommand(settings, settings.token)

    private fun buildLaunchCommand(settings: PiDiffSettings.State, token: String): String {
        return "PI_INTELLIJ_DIFF_PORT=${settings.port} " +
            "PI_INTELLIJ_DIFF_TOKEN=${shellQuote(token)} " +
            settings.piCommand
    }

    private fun installPiExtension(project: Project) {
        val resource = PiAgentLauncher::class.java.classLoader.getResourceAsStream("pi-extension/intellij-diff.ts")
            ?: throw IllegalStateException("Bundled pi extension resource not found")

        val extensionDir = Path.of(System.getProperty("user.home"), ".pi", "agent", "extensions")
        Files.createDirectories(extensionDir)
        val target = extensionDir.resolve("intellij-diff.ts")
        resource.use { input -> Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING) }
        notify(project, "Pi extension installed at $target", NotificationType.INFORMATION)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun notify(project: Project, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Pi Diff Approval")
                ?.createNotification(message, type)
                ?.notify(project)
        }
    }
}
