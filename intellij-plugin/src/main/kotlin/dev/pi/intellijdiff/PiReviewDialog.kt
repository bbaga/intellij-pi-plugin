package dev.pi.intellijdiff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultCellEditor
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

data class ReviewFile(
    val path: String,
    val displayPath: String,
    val before: String,
    val after: String,
    val kind: String,
    val existedBefore: Boolean = true,
    val summary: String = "",
) {
    override fun toString(): String = "[$kind] $displayPath"
}

data class ReviewDecision(
    val path: String,
    val action: String,
    val reason: String = "",
)

data class ReviewComment(
    val path: String,
    val side: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val selectedText: String = "",
)

data class ReviewResult(
    val action: String,
    val request: String = "",
    val decisions: List<ReviewDecision> = emptyList(),
    val comments: List<ReviewComment> = emptyList(),
)

private enum class FileReviewState(val label: String) {
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    FEEDBACK("feedback");

    companion object {
        fun fromLabel(label: String): FileReviewState = entries.firstOrNull { it.label == label } ?: ACCEPTED
    }
}

private data class FileReviewOutcome(val state: FileReviewState, val comments: List<ReviewComment>)

class PiReviewDialog(private val project: Project, private val files: List<ReviewFile>) : DialogWrapper(project, true) {
    private val globalRequestArea = JBTextArea(4, 40)
    private val commentsByPath = linkedMapOf<String, MutableList<ReviewComment>>()
    private lateinit var table: JTable
    private var result = ReviewResult("reject", decisions = files.map { ReviewDecision(it.path, "reject") })

    init {
        title = "Review Pi changes (${files.size} file${if (files.size == 1) "" else "s"})"
        init()
    }

    fun reviewResult(): ReviewResult = result

    override fun createCenterPanel(): JComponent {
        val model = object : DefaultTableModel(arrayOf("Review state", "File", "Summary"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = column == 0
        }
        files.forEach { model.addRow(arrayOf(FileReviewState.ACCEPTED.label, "[${it.kind}] ${it.displayPath}", it.summary.ifBlank { summaryFor(it) })) }

        table = JTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.columnModel.getColumn(0).cellEditor = DefaultCellEditor(JComboBox(FileReviewState.entries.map { it.label }.toTypedArray()))
        table.columnModel.getColumn(0).preferredWidth = 120
        table.columnModel.getColumn(1).preferredWidth = 430
        table.columnModel.getColumn(2).preferredWidth = 520
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && table.selectedRow >= 0) openFileReview(table.selectedRow)
            }
        })

        globalRequestArea.emptyText.text = "Optional global feedback for rejected/feedback files. Double-click a file to inspect its diff or add inline feedback."
        globalRequestArea.lineWrap = true
        globalRequestArea.wrapStyleWord = true

        val panel = JPanel(BorderLayout(8, 8))
        panel.add(JBScrollPane(table), BorderLayout.CENTER)
        panel.add(JBScrollPane(globalRequestArea), BorderLayout.SOUTH)
        panel.preferredSize = java.awt.Dimension(1100, 650)
        return panel
    }

    private fun openFileReview(row: Int) {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val file = files.getOrNull(row) ?: return
        val currentState = FileReviewState.fromLabel(table.getValueAt(row, 0)?.toString().orEmpty())
        val dialog = PiFileReviewDialog(project, file, currentState, commentsByPath[file.path].orEmpty())
        if (!dialog.showAndGet()) return
        val outcome = dialog.outcome()
        commentsByPath[file.path] = outcome.comments.toMutableList()
        val state = if (outcome.comments.isNotEmpty()) FileReviewState.FEEDBACK else outcome.state
        table.setValueAt(state.label, row, 0)
    }

    private fun summaryFor(file: ReviewFile): String {
        if (file.kind == "write" && !file.existedBefore) return "Created new file"
        if (file.kind == "delete") return "Deleted file"
        val beforeLines = file.before.lines().size
        val afterLines = file.after.lines().size
        val delta = afterLines - beforeLines
        return when {
            delta > 0 -> "Edited file; added about $delta line${if (delta == 1) "" else "s"}"
            delta < 0 -> "Edited file; removed about ${-delta} line${if (delta == -1) "" else "s"}"
            else -> "Edited file"
        }
    }

    private fun collectResult(): ReviewResult {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val decisions = files.mapIndexed { index, file ->
            val hasComments = commentsByPath[file.path]?.isNotEmpty() == true
            val state = if (hasComments) FileReviewState.FEEDBACK else FileReviewState.fromLabel(table.getValueAt(index, 0)?.toString().orEmpty())
            ReviewDecision(
                path = file.path,
                action = when (state) {
                    FileReviewState.ACCEPTED -> "accept"
                    FileReviewState.REJECTED -> "reject"
                    FileReviewState.FEEDBACK -> "requestChanges"
                },
            )
        }
        val allComments = commentsByPath.values.flatten()
        val aggregate = when {
            decisions.all { it.action == "accept" } -> "accept"
            decisions.any { it.action == "requestChanges" } -> "requestChanges"
            else -> "mixed"
        }
        return ReviewResult(aggregate, globalRequestArea.text.trim(), decisions, allComments)
    }

    private fun setAll(state: FileReviewState) {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        files.forEachIndexed { row, file ->
            table.setValueAt(if (commentsByPath[file.path]?.isNotEmpty() == true) FileReviewState.FEEDBACK.label else state.label, row, 0)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Submit Review") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                result = collectResult()
                close(OK_EXIT_CODE)
            }
        },
        object : AbstractAction("Accept All") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                setAll(FileReviewState.ACCEPTED)
                result = collectResult()
                close(OK_EXIT_CODE)
            }
        },
        object : AbstractAction("Reject All") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                setAll(FileReviewState.REJECTED)
                result = collectResult()
                close(CANCEL_EXIT_CODE)
            }
        },
    )
}

private class PiFileReviewDialog(
    private val project: Project,
    private val file: ReviewFile,
    initialState: FileReviewState,
    initialComments: List<ReviewComment>,
) : DialogWrapper(project, true) {
    private val disposable = Disposer.newDisposable("PiFileReviewDialog")
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private val comments = initialComments.toMutableList()
    private val commentHighlighters = mutableListOf<RangeHighlighter>()
    private val commentInlays = mutableListOf<Inlay<*>>()
    private val pendingCommentInlays = mutableListOf<Inlay<*>>()
    private val lineActionHighlighters = mutableListOf<RangeHighlighter>()
    private val actionListenerEditors = mutableSetOf<Editor>()
    private var editorReadyDisposable: Disposable? = null
    private var state = initialState

    init {
        title = "Review ${file.displayPath}"
        init()
    }

    fun outcome(): FileReviewOutcome = FileReviewOutcome(if (comments.isNotEmpty()) FileReviewState.FEEDBACK else state, comments.toList())

    override fun createCenterPanel(): JComponent {
        val vf = LocalFileSystem.getInstance().findFileByPath(file.path)
        val factory = DiffContentFactory.getInstance()
        val before = if (vf != null) factory.create(project, file.before, vf.fileType) else factory.create(file.before)
        val after = if (vf != null) factory.create(project, file.after, vf.fileType) else factory.create(file.after)
        diffPanel.setRequest(SimpleDiffRequest("${file.kind}: ${file.displayPath}", before, after, "Before Pi", "After Pi"))
        installLineCommentActions()
        return JPanel(BorderLayout()).apply {
            add(diffPanel.component, BorderLayout.CENTER)
            preferredSize = java.awt.Dimension(1300, 850)
        }
    }

    private fun selectedDiffEditor(): Editor? {
        focusedEditor()?.takeIf { isDiffEditorForFile(it) && it.selectionModel.hasSelection() }?.let { return it }
        return EditorFactory.getInstance().allEditors.firstOrNull { isDiffEditorForFile(it) && it.selectionModel.hasSelection() }
    }

    private fun focusedEditor(): Editor? {
        val focusOwner: Component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
        val dataContext = DataManager.getInstance().getDataContext(focusOwner)
        return CommonDataKeys.EDITOR.getData(dataContext)
    }

    private fun isDiffEditorForFile(editor: Editor): Boolean =
        SwingUtilities.isDescendingFrom(editor.component, diffPanel.component) &&
            (editor.document.text == file.before || editor.document.text == file.after)

    private fun installLineCommentActions() {
        clearLineActionHighlighters()
        editorReadyDisposable?.let { Disposer.dispose(it) }
        editorReadyDisposable = null
        ApplicationManager.getApplication().invokeLater {
            val editors = EditorFactory.getInstance().allEditors.filter { isDiffEditorForFile(it) }
            if (editors.isNotEmpty()) {
                editors.forEach { installHoverLineCommentAction(it) }
                refreshCommentRenderers()
                return@invokeLater
            }
            val listenerDisposable = Disposer.newDisposable(disposable, "PiFileReviewDialog.editorReadyListener")
            val listener = object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!isDiffEditorForFile(event.editor)) return@invokeLater
                        installHoverLineCommentAction(event.editor)
                        refreshCommentRenderers()
                        Disposer.dispose(listenerDisposable)
                        if (editorReadyDisposable == listenerDisposable) editorReadyDisposable = null
                    }
                }
            }
            editorReadyDisposable = listenerDisposable
            EditorFactory.getInstance().addEditorFactoryListener(listener, listenerDisposable)
        }
    }

    private fun installHoverLineCommentAction(editor: Editor) {
        if (!actionListenerEditors.add(editor)) return
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (!isDiffEditorForFile(editor)) return
                if (!editor.selectionModel.hasSelection()) return clearLineActionHighlighters()
                val targetLine = editor.document.getLineNumber(editor.selectionModel.selectionStart).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
                showLineActionHighlighter(editor, targetLine)
            }
        }, disposable)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                if (!isDiffEditorForFile(editor)) return clearLineActionHighlighters()
                if (editor.selectionModel.hasSelection()) {
                    val targetLine = editor.document.getLineNumber(editor.selectionModel.selectionStart).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
                    showLineActionHighlighter(editor, targetLine)
                    return
                }
                if (!event.isInLineNumberGutter()) return clearLineActionHighlighters()
                showLineActionHighlighter(editor, event.logicalPosition.line.coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0)))
            }
        }, disposable)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(event: EditorMouseEvent) {
                if (!editor.selectionModel.hasSelection()) clearLineActionHighlighters()
            }
        }, disposable)
    }

    private fun EditorMouseEvent.isInLineNumberGutter(): Boolean =
        area == EditorMouseEventArea.LINE_NUMBERS_AREA || area == EditorMouseEventArea.LINE_MARKERS_AREA || area == EditorMouseEventArea.ANNOTATIONS_AREA

    private fun showLineActionHighlighter(editor: Editor, line: Int) {
        val existing = lineActionHighlighters.singleOrNull()
        if (existing != null && existing.isValid && existing.gutterIconRenderer == AddReviewCommentGutterIcon(editor, line)) return
        clearLineActionHighlighters()
        val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
        highlighter.gutterIconRenderer = AddReviewCommentGutterIcon(editor, line)
        lineActionHighlighters.add(highlighter)
    }

    private fun showCommentEditorFromSelection() {
        val editor = selectedDiffEditor() ?: return Messages.showWarningDialog(project, "Highlight one or more lines in the diff first, or click a + icon in the diff gutter.", "No Diff Selection")
        val selection = editor.selectionModel
        val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
        val endLine = editor.document.getLineNumber((selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)) + 1
        showCommentEditor(editor, startLine, endLine, selection.selectedText.orEmpty())
    }

    private fun showCommentEditor(editor: Editor, startLine: Int, endLine: Int, selectedText: String = "", existingIndex: Int? = null, initialText: String = "") {
        val editorEx = editor as? EditorEx ?: return
        clearPendingCommentEditors()
        val line = (endLine - 1).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
        val offset = editor.document.getLineEndOffset(line)
        val textArea = JBTextArea(initialText, 4, 48).apply {
            lineWrap = true
            wrapStyleWord = true
            emptyText.text = "Leave feedback for ${file.displayPath}:$startLine-$endLine"
        }
        val panel = commentPanel().apply {
            add(JLabel("Feedback on ${file.displayPath}:$startLine-$endLine"), BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(JButton("Cancel").apply { addActionListener { clearPendingCommentEditors() } })
                add(JButton("Save").apply {
                    addActionListener {
                        val text = textArea.text.trim()
                        if (text.isBlank()) return@addActionListener
                        val comment = ReviewComment(file.path, sideForEditor(editor), startLine, endLine, text, selectedText.take(4_000))
                        if (existingIndex != null && existingIndex in comments.indices) comments[existingIndex] = comment else comments.add(comment)
                        state = FileReviewState.FEEDBACK
                        clearPendingCommentEditors()
                        refreshCommentRenderers()
                    }
                })
            }, BorderLayout.SOUTH)
        }
        addEmbeddedPanel(editorEx, panel, offset, null)?.let { pendingCommentInlays.add(it) }
        SwingUtilities.invokeLater { textArea.requestFocusInWindow() }
    }

    private fun refreshCommentRenderers() {
        clearCommentRenderers()
        val editors = EditorFactory.getInstance().allEditors.filter { isDiffEditorForFile(it) }
        comments.forEachIndexed { index, comment ->
            val editor = editors.firstOrNull { comment.side == "unknown" || sideForEditor(it) == comment.side } ?: return@forEachIndexed
            addGutterMarkers(editor, comment)
            addEmbeddedComment(editor, index, comment)
        }
    }

    private fun addGutterMarkers(editor: Editor, comment: ReviewComment) {
        val startLine = (comment.startLine - 1).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
        val endLine = (comment.endLine - 1).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
        listOf(
            Triple(startLine, "Feedback starts here", AllIcons.General.ArrowDown),
            Triple(endLine, "Feedback ends here", AllIcons.General.ArrowUp),
        ).distinctBy { it.first }.forEach { (line, tooltip, icon) ->
            val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
            highlighter.gutterIconRenderer = ReviewCommentGutterIcon(comment, tooltip, icon)
            highlighter.errorStripeTooltip = "$tooltip: ${comment.text}"
            commentHighlighters.add(highlighter)
        }
    }

    private fun addEmbeddedComment(editor: Editor, index: Int, comment: ReviewComment) {
        val editorEx = editor as? EditorEx ?: return
        val line = (comment.endLine - 1).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
        val offset = editor.document.getLineEndOffset(line)
        val panel = commentPanel().apply {
            add(JLabel("<html><b>Feedback ${index + 1}</b><br>${escapeHtml(comment.text)}</html>"), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(JButton("Edit").apply { addActionListener { showCommentEditor(editor, comment.startLine, comment.endLine, comment.selectedText, index, comment.text) } })
                add(JButton("Delete").apply { addActionListener { deleteComment(index) } })
            }, BorderLayout.SOUTH)
        }
        addEmbeddedPanel(editorEx, panel, offset, ReviewCommentGutterIcon(comment, "Pi feedback", AllIcons.General.Note))?.let { commentInlays.add(it) }
    }

    private fun addEmbeddedPanel(editor: EditorEx, panel: JPanel, offset: Int, gutterIcon: GutterIconRenderer?): Inlay<*>? {
        val properties = EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), { gutterIcon }, true, false, 100, offset)
        return EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, properties)
    }

    private fun commentPanel(): JPanel = JPanel(BorderLayout(8, 6)).apply {
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createEmptyBorder(4, 24, 4, 4),
            javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(com.intellij.ui.JBColor.border()),
                javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8),
            ),
        )
    }

    private fun deleteComment(index: Int) {
        if (index !in comments.indices) return
        comments.removeAt(index)
        refreshCommentRenderers()
    }

    private fun sideForEditor(editor: Editor): String = when (editor.document.text) {
        file.before -> "before"
        file.after -> "after"
        else -> "unknown"
    }

    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    private fun clearLineActionHighlighters() {
        lineActionHighlighters.forEach { it.dispose() }
        lineActionHighlighters.clear()
    }

    private fun clearCommentRenderers() {
        commentHighlighters.forEach { it.dispose() }
        commentHighlighters.clear()
        commentInlays.forEach { it.dispose() }
        commentInlays.clear()
    }

    private fun clearPendingCommentEditors() {
        pendingCommentInlays.forEach { it.dispose() }
        pendingCommentInlays.clear()
    }

    private inner class ReviewCommentGutterIcon(private val comment: ReviewComment, private val tooltipPrefix: String = "Pi feedback", private val icon: Icon = AllIcons.General.Note) : GutterIconRenderer() {
        override fun getIcon(): Icon = icon
        override fun getTooltipText(): String = "$tooltipPrefix: ${comment.text}"
        override fun getClickAction(): AnAction = object : AnAction("Show Pi Feedback") {
            override fun actionPerformed(e: AnActionEvent) {
                Messages.showInfoMessage(project, comment.text, "Pi Feedback")
            }
        }
        override fun getAlignment(): Alignment = Alignment.RIGHT
        override fun equals(other: Any?): Boolean = other is ReviewCommentGutterIcon && other.comment == comment && other.tooltipPrefix == tooltipPrefix
        override fun hashCode(): Int = 31 * comment.hashCode() + tooltipPrefix.hashCode()
    }

    private inner class AddReviewCommentGutterIcon(private val editor: Editor, private val line: Int) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.Add
        override fun getTooltipText(): String = if (editor.selectionModel.hasSelection()) "Add feedback for selected lines" else "Add feedback"
        override fun getClickAction(): AnAction = object : AnAction("Add Pi Feedback") {
            override fun actionPerformed(e: AnActionEvent) {
                val selection = editor.selectionModel
                if (selection.hasSelection()) {
                    val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
                    val endLine = editor.document.getLineNumber((selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart)) + 1
                    showCommentEditor(editor, startLine, endLine, selection.selectedText.orEmpty())
                } else {
                    showCommentEditor(editor, line + 1, line + 1)
                }
            }
        }
        override fun getAlignment(): Alignment = Alignment.LEFT
        override fun equals(other: Any?): Boolean = other is AddReviewCommentGutterIcon && other.editor == editor && other.line == line
        override fun hashCode(): Int = 31 * editor.hashCode() + line
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("Save Feedback") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                state = FileReviewState.FEEDBACK
                close(OK_EXIT_CODE)
            }
        },
        object : AbstractAction("Accept File") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                state = if (comments.isNotEmpty()) FileReviewState.FEEDBACK else FileReviewState.ACCEPTED
                close(OK_EXIT_CODE)
            }
        },
        object : AbstractAction("Reject File") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                state = if (comments.isNotEmpty()) FileReviewState.FEEDBACK else FileReviewState.REJECTED
                close(OK_EXIT_CODE)
            }
        },
        object : AbstractAction("Add Feedback from Selection") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                showCommentEditorFromSelection()
            }
        },
    )

    override fun dispose() {
        editorReadyDisposable?.let { Disposer.dispose(it) }
        editorReadyDisposable = null
        clearLineActionHighlighters()
        clearPendingCommentEditors()
        clearCommentRenderers()
        Disposer.dispose(disposable)
        super.dispose()
    }
}
