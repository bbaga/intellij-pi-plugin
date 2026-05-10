package dev.pi.intellijdiff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.BorderLayout
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

@Service(Service.Level.APP)
class PiDiffApprovalService : Disposable {
    private val log = Logger.getInstance(PiDiffApprovalService::class.java)
    private var server: HttpServer? = null
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pi-diff-approval").apply { isDaemon = true }
    }
    private val gson = Gson()

    @Synchronized
    fun isRunning(): Boolean = server != null

    @Synchronized
    fun start() {
        if (server != null) return
        val settings = PiDiffSettings.getInstance().state
        try {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", settings.port), 0)
            httpServer.createContext("/api/pi/diff", this::handleDiff)
            httpServer.createContext("/api/pi/review", this::handleReview)
            httpServer.createContext("/api/pi/settings", this::handleSettings)
            httpServer.createContext("/api/pi/context", this::handleContext)
            httpServer.createContext("/api/pi/health", this::handleHealth)
            httpServer.executor = executor
            httpServer.start()
            server = httpServer
            notify("Pi diff approval server listening on 127.0.0.1:${settings.port}", NotificationType.INFORMATION)
        } catch (e: BindException) {
            notify("Could not start Pi diff approval server on port ${settings.port}: port is already in use", NotificationType.ERROR)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    private fun handleHealth(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") return exchange.respond(405, "Only GET is supported")
            val settings = PiDiffSettings.getInstance().state
            exchange.respond(
                200,
                "{\"ok\":true,\"port\":${settings.port},\"tokenRequired\":${settings.token.isNotBlank()}}",
                "application/json",
            )
        } finally {
            exchange.close()
        }
    }

    private fun handleSettings(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") return exchange.respond(405, "Only GET is supported")
            if (!isAuthorized(exchange)) return exchange.respond(401, "Invalid token")
            val s = PiDiffSettings.getInstance().state
            exchange.respond(
                200,
                "{\"approveCreates\":${s.approveCreates},\"approveEdits\":${s.approveEdits},\"approveDeletes\":${s.approveDeletes},\"reviewMode\":\"${s.reviewMode}\"}",
                "application/json",
            )
        } catch (t: Throwable) {
            log.warn(t)
            exchange.respond(500, t.message ?: t.javaClass.name)
        } finally {
            exchange.close()
        }
    }

    private fun handleReview(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") return exchange.respond(405, "Only POST is supported")
            if (!isAuthorized(exchange)) return exchange.respond(401, "Invalid token")
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val obj = JsonParser.parseString(body).asJsonObject
            val files = obj.getAsJsonArray("files").map { element ->
                val f = element.asJsonObject
                ReviewFile(
                    path = f.get("path").asString,
                    displayPath = f.get("displayPath").asString,
                    before = f.get("before").asString,
                    after = f.get("after").asString,
                    kind = f.get("kind").asString,
                    existedBefore = !f.has("existedBefore") || f.get("existedBefore").asBoolean,
                )
            }
            val result = showReviewAndWait(files)
            exchange.respond(200, gson.toJson(result), "application/json")
        } catch (t: Throwable) {
            log.warn(t)
            exchange.respond(500, t.message ?: t.javaClass.name)
        } finally {
            exchange.close()
        }
    }

    private fun handleDiff(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") return exchange.respond(405, "Only POST is supported")
            if (!isAuthorized(exchange)) return exchange.respond(401, "Invalid token")

            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = DiffApprovalRequest.fromJson(body) ?: return exchange.respond(400, "Invalid JSON")
            val accepted = showDiffAndWait(request)
            val json = if (accepted) "{\"accepted\":true}" else "{\"accepted\":false,\"reason\":\"Rejected in IntelliJ\"}"
            exchange.respond(200, json, "application/json")
        } catch (t: Throwable) {
            log.warn(t)
            exchange.respond(500, t.message ?: t.javaClass.name)
        } finally {
            exchange.close()
        }
    }

    private fun handleContext(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") return exchange.respond(405, "Only POST is supported")
            if (!isAuthorized(exchange)) return exchange.respond(401, "Invalid token")

            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val parsed = try { JsonParser.parseString(body).asJsonObject } catch (_: Throwable) { JsonObject() }
            val cwd = if (parsed.has("cwd") && !parsed.get("cwd").isJsonNull) parsed.get("cwd").asString else ""
            val consumeFocus = parsed.has("consumeFocus") && !parsed.get("consumeFocus").isJsonNull && parsed.get("consumeFocus").asBoolean
            var json = JsonObject().apply { addProperty("hasSelection", false) }
            ApplicationManager.getApplication().invokeAndWait {
                val project = chooseProjectByCwd(cwd) ?: ProjectManager.getInstance().openProjects.firstOrNull()
                json = if (project == null) JsonObject().apply { addProperty("hasSelection", false) } else editorSelectionContext(project, consumeFocus)
            }
            exchange.respond(200, json.toString(), "application/json")
        } catch (t: Throwable) {
            log.warn(t)
            exchange.respond(500, t.message ?: t.javaClass.name)
        } finally {
            exchange.close()
        }
    }

    private fun isAuthorized(exchange: HttpExchange): Boolean {
        val expected = PiDiffSettings.getInstance().state.token
        val actual = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") ?: ""
        return expected.isBlank() || actual == expected
    }

    private fun showDiffAndWait(request: DiffApprovalRequest): Boolean {
        val future = CompletableFuture<Boolean>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val project = chooseProject(request) ?: throw IllegalStateException("No open IntelliJ project for ${request.path}")
                val dialog = PiDiffDialog(project, request)
                future.complete(dialog.showAndGet())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get()
    }

    private fun showReviewAndWait(files: List<ReviewFile>): ReviewResult {
        val future = CompletableFuture<ReviewResult>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: throw IllegalStateException("No open IntelliJ project")
                val dialog = PiReviewDialog(project, files)
                dialog.show()
                future.complete(dialog.reviewResult())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get()
    }

    private fun chooseProject(request: DiffApprovalRequest): Project? =
        chooseProjectByCwd(request.cwd, request.path) ?: ProjectManager.getInstance().openProjects.firstOrNull()

    private fun chooseProjectByCwd(cwd: String, path: String = ""): Project? {
        val projects = ProjectManager.getInstance().openProjects.toList()
        return projects.firstOrNull { project ->
            val base = project.basePath ?: return@firstOrNull false
            cwd.startsWith(base) || path.startsWith(base)
        }
    }

    private fun editorSelectionContext(project: Project, consumeFocus: Boolean): JsonObject {
        val result = JsonObject()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val focusFiles = project.service<PiContextFocusService>().getFocus(consumeFocus)
        result.addProperty("hasSelection", false)
        result.add("focusFiles", com.google.gson.JsonArray().apply { focusFiles.forEach { add(it) } })

        if (editor == null) {
            return result
        }

        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (file != null) {
            result.addProperty("activeFile", file.path)
            result.addProperty("activeLanguage", file.extension ?: "")
        }

        val selection = editor.selectionModel
        if (!selection.hasSelection()) {
            return result
        }

        val selectedText = selection.selectedText ?: ""
        if (selectedText.isBlank()) {
            return result
        }

        val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
        val endLine = editor.document.getLineNumber(selection.selectionEnd) + 1
        result.addProperty("hasSelection", true)
        result.addProperty("path", file?.path ?: "")
        result.addProperty("startLine", startLine)
        result.addProperty("endLine", endLine)
        result.addProperty("language", file?.extension ?: "")
        result.addProperty("text", selectedText.take(20_000))
        result.addProperty("truncated", selectedText.length > 20_000)
        return result
    }

    override fun dispose() {
        stop()
        executor.shutdownNow()
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Diff Approval")
            ?.createNotification(message, type)
            ?.notify(null)
    }

    companion object {
        fun getInstance(): PiDiffApprovalService = service()
    }
}

class PiDiffDialog(private val project: Project, private val request: DiffApprovalRequest) : DialogWrapper(project, true) {
    private val disposable = Disposer.newDisposable("PiDiffDialog")
    private val panel = JPanel(BorderLayout())

    init {
        title = "Pi wants to ${request.kind} ${request.displayPath.ifBlank { request.path }}"
        setOKButtonText("Accept")
        setCancelButtonText("Reject")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        val file = LocalFileSystem.getInstance().findFileByPath(request.path)
        val factory = DiffContentFactory.getInstance()
        val before = if (file != null) factory.create(project, request.before, file.fileType) else factory.create(request.before)
        val after = if (file != null) factory.create(project, request.after, file.fileType) else factory.create(request.after)
        diffPanel.setRequest(SimpleDiffRequest(request.displayPath, before, after, "Before", "After"))
        panel.add(diffPanel.component, BorderLayout.CENTER)
        panel.preferredSize = java.awt.Dimension(1100, 750)
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

    override fun dispose() {
        Disposer.dispose(disposable)
        super.dispose()
    }
}

data class DiffApprovalRequest(
    val requestId: String,
    val cwd: String,
    val path: String,
    val displayPath: String,
    val kind: String,
    val before: String,
    val after: String,
) {
    companion object {
        fun fromJson(json: String): DiffApprovalRequest? {
            val obj = JsonParser.parseString(json).asJsonObject
            fun string(name: String): String = if (obj.has(name) && !obj.get(name).isJsonNull) obj.get(name).asString else ""
            val path = string("path")
            if (path.isBlank()) return null
            return DiffApprovalRequest(
                requestId = string("requestId"),
                cwd = string("cwd"),
                path = path,
                displayPath = string("displayPath"),
                kind = string("kind").ifBlank { "edit" },
                before = string("before"),
                after = string("after"),
            )
        }
    }
}

private fun HttpExchange.respond(status: Int, text: String, contentType: String = "text/plain") {
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "$contentType; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.write(bytes)
}
