package dev.pi.intellijdiff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel
import javax.swing.JComboBox

data class ReviewFile(
    val path: String,
    val displayPath: String,
    val before: String,
    val after: String,
    val kind: String,
    val existedBefore: Boolean = true,
) {
    override fun toString(): String = "[$kind] $displayPath"
}

data class ReviewDecision(
    val path: String,
    val action: String,
    val reason: String = "",
)

data class ReviewResult(
    val action: String,
    val request: String = "",
    val decisions: List<ReviewDecision> = emptyList(),
)

class PiReviewDialog(private val project: Project, private val files: List<ReviewFile>) : DialogWrapper(project, true) {
    private val disposable = Disposer.newDisposable("PiReviewDialog")
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    private val globalRequestArea = JBTextArea(4, 40)
    private lateinit var table: JTable
    private var result = ReviewResult("reject", decisions = files.map { ReviewDecision(it.path, "reject") })

    init {
        title = "Review Pi changes (${files.size} file${if (files.size == 1) "" else "s"})"
        init()
    }

    fun reviewResult(): ReviewResult = result

    override fun createCenterPanel(): JComponent {
        val model = object : DefaultTableModel(arrayOf("Decision", "File", "Reason"), 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = column == 0 || column == 2
        }
        files.forEach { model.addRow(arrayOf("accept", "[${it.kind}] ${it.displayPath}", "")) }

        table = JTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.getColumnModel().getColumn(0).cellEditor = DefaultCellEditor(JComboBox(arrayOf("accept", "reject", "requestChanges")))
        table.getColumnModel().getColumn(0).preferredWidth = 140
        table.getColumnModel().getColumn(1).preferredWidth = 420
        table.getColumnModel().getColumn(2).preferredWidth = 420
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) showFile(files.getOrNull(table.selectedRow) ?: files.first())
        }

        val splitter = JBSplitter(false, 0.35f)
        splitter.firstComponent = JBScrollPane(table)
        splitter.secondComponent = diffPanel.component

        globalRequestArea.emptyText.text = "Optional global feedback for rejected/requested-change files."
        globalRequestArea.lineWrap = true
        globalRequestArea.wrapStyleWord = true

        val panel = JPanel(BorderLayout(8, 8))
        panel.add(splitter, BorderLayout.CENTER)
        panel.add(JBScrollPane(globalRequestArea), BorderLayout.SOUTH)
        panel.preferredSize = java.awt.Dimension(1300, 850)

        if (files.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
            showFile(files.first())
        }
        return panel
    }

    private fun showFile(file: ReviewFile) {
        val vf = LocalFileSystem.getInstance().findFileByPath(file.path)
        val factory = DiffContentFactory.getInstance()
        val before = if (vf != null) factory.create(project, file.before, vf.fileType) else factory.create(file.before)
        val after = if (vf != null) factory.create(project, file.after, vf.fileType) else factory.create(file.after)
        diffPanel.setRequest(SimpleDiffRequest("${file.kind}: ${file.displayPath}", before, after, "Before Pi", "After Pi"))
    }

    private fun collectResult(): ReviewResult {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val decisions = files.mapIndexed { index, file ->
            ReviewDecision(
                path = file.path,
                action = table.getValueAt(index, 0)?.toString() ?: "accept",
                reason = table.getValueAt(index, 2)?.toString()?.trim().orEmpty(),
            )
        }
        val aggregate = when {
            decisions.all { it.action == "accept" } -> "accept"
            decisions.any { it.action == "requestChanges" } -> "requestChanges"
            else -> "mixed"
        }
        return ReviewResult(aggregate, globalRequestArea.text.trim(), decisions)
    }

    private fun setAll(action: String) {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        for (row in files.indices) table.setValueAt(action, row, 0)
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
                setAll("accept")
                result = collectResult()
                close(OK_EXIT_CODE)
            }
        },
        object : AbstractAction("Reject All") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                setAll("reject")
                result = collectResult()
                close(CANCEL_EXIT_CODE)
            }
        },
    )

    override fun dispose() {
        Disposer.dispose(disposable)
        super.dispose()
    }
}
