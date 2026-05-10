package dev.pi.intellijdiff

import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class AddToPiContextAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val files = selectedVirtualFiles(event)
        if (files.isEmpty()) {
            notify(project, "No project files selected for Pi context.", NotificationType.WARNING)
            return
        }

        val paths = files.flatMap { file -> flatten(file) }.distinct().sorted()
        project.service<PiContextFocusService>().setFocus(paths)
        notify(project, "Added ${paths.size} file(s) to Pi context for the next prompt.", NotificationType.INFORMATION)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = event.project != null
        // Keep the action clickable in Project View even when this IDE build does not expose
        // selection data during update(); actionPerformed() resolves the selection again.
        event.presentation.isEnabled = event.project != null
    }

    private fun selectedVirtualFiles(event: AnActionEvent): List<VirtualFile> {
        val result = LinkedHashSet<VirtualFile>()

        event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.forEach { result.add(it) }
        event.getData(CommonDataKeys.VIRTUAL_FILE)?.let { result.add(it) }
        event.getData(CommonDataKeys.PSI_ELEMENT)?.let { result.addPsi(it) }
        event.getData(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY)?.forEach { result.addPsi(it) }
        event.getData(PlatformCoreDataKeys.SELECTED_ITEM)?.let { result.addSelectedItem(it) }
        event.getData(PlatformCoreDataKeys.SELECTED_ITEMS)?.forEach { result.addSelectedItem(it) }

        return result.toList()
    }

    private fun MutableSet<VirtualFile>.addSelectedItem(item: Any) {
        when (item) {
            is VirtualFile -> add(item)
            is PsiElement -> addPsi(item)
            is AbstractTreeNode<*> -> item.value?.let { addSelectedItem(it) }
        }
    }

    private fun MutableSet<VirtualFile>.addPsi(psi: PsiElement) {
        when (psi) {
            is PsiFile -> psi.virtualFile?.let { add(it) }
            is PsiDirectory -> add(psi.virtualFile)
        }
    }

    private fun flatten(file: VirtualFile): List<String> {
        if (!file.isDirectory) return listOf(file.path)
        return file.children.flatMap { flatten(it) }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Pi Diff Approval")
            ?.createNotification(message, type)
            ?.notify(project)
    }
}
