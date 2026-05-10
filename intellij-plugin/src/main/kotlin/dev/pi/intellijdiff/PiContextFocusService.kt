package dev.pi.intellijdiff

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class PiContextFocusService(private val project: Project) {
    private val lock = Any()
    private var focusPaths: List<String> = emptyList()

    fun setFocus(paths: List<String>) {
        synchronized(lock) {
            focusPaths = paths.distinct().sorted()
        }
    }

    fun getFocus(consume: Boolean): List<String> {
        synchronized(lock) {
            val result = focusPaths
            if (consume) focusPaths = emptyList()
            return result
        }
    }
}
