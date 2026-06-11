package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Paths
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * "Claude Edits" tool window: a live list of in-flight files (across all open
 * projects). Rows show the Claude badge, file name, parent path, how long the
 * file has been in flight and the session id. Double-click or Enter opens the
 * file; a header shows the count and ticks the elapsed times once a second.
 */
class GuardToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val state = ApplicationManager.getApplication().getService(GuardState::class.java)
        val model = DefaultListModel<ClaudeEditState>()
        val list = JBList(model)
        list.cellRenderer = GuardCellRenderer({ System.currentTimeMillis() }, project.basePath)
        list.emptyText.text = "Claude is not editing anything right now"

        val header = JBLabel().apply { border = JBUI.Borders.empty(6, 10) }

        fun openSelected() {
            val selected = list.selectedValue ?: return
            val file = LocalFileSystem.getInstance().findFileByPath(selected.path) ?: return
            FileEditorManager.getInstance(project).openFile(file, true)
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelected()
                return true
            }
        }.installOn(list)

        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })

        fun reload() {
            val mine = state.snapshot().filter { fileBelongsToProject(project, it.path) }
            SwingUtilities.invokeLater {
                model.clear()
                mine.forEach { model.addElement(it) }
                header.text = when (val n = model.size) {
                    0 -> "No files in flight"
                    1 -> "1 file in flight"
                    else -> "$n files in flight"
                }
            }
        }

        state.addListener { reload() }
        reload()

        // Tick elapsed times once a second (repaint only, no rebuild).
        val timer = javax.swing.Timer(1000) { if (!model.isEmpty) list.repaint() }
        timer.start()
        Disposer.register(toolWindow.disposable) { timer.stop() }

        val panel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * Whether [path] belongs to [project] — so each project's tool window lists
 * only its own in-flight files when several projects are open at once. A git
 * worktree opened as its own project matches via its own content roots / base
 * path, so its edits show there, not in the main checkout's window.
 */
private fun fileBelongsToProject(project: Project, path: String): Boolean {
    val file = LocalFileSystem.getInstance().findFileByPath(path)
    if (file != null) {
        val inContent = ReadAction.compute<Boolean, RuntimeException> {
            !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(file)
        }
        if (inContent) return true
    }
    val base = project.basePath ?: return false
    val basePath = Paths.get(base)
    val filePath = Paths.get(path)
    return filePath.startsWith(basePath)
}

private class GuardCellRenderer(
    private val clock: () -> Long,
    private val projectBasePath: String?,
) : ColoredListCellRenderer<ClaudeEditState>() {
    override fun customizeCellRenderer(
        list: JList<out ClaudeEditState>,
        value: ClaudeEditState,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = ClaudeIcons.CLAUDE
        border = JBUI.Borders.empty(2, 6)

        val file = File(value.path)
        append(file.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

        val parentLabel = relativeParent(value.path)
        if (parentLabel.isNotEmpty()) {
            append("  $parentLabel", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        append("   ${formatElapsed(clock() - value.startedAt)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        value.sessionId?.takeIf { it.isNotBlank() }?.let {
            append("  ·  ${it.take(8)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        toolTipText = value.path
    }

    /**
     * Parent directory of [filePath], relative to the project root. Empty when
     * the file sits in the root. Falls back to the absolute parent for files
     * outside the project (e.g. another open project's file).
     */
    private fun relativeParent(filePath: String): String {
        val file = File(filePath)
        val parent = file.parentFile?.path ?: return ""
        val base = projectBasePath ?: return parent

        val basePath = Paths.get(base)
        val parentPath = Paths.get(parent)
        if (!parentPath.startsWith(basePath)) return parent

        val relative = basePath.relativize(parentPath).toString()
        return relative
    }

    private fun formatElapsed(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
}
