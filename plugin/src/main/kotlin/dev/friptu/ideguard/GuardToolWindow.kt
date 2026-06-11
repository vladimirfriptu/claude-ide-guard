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

/** A row in the tool window: either a section header or a file entry. */
private sealed interface GuardRow {
    data class Section(val title: String) : GuardRow
    data class FileRow(val state: ClaudeEditState) : GuardRow
}

/**
 * "Claude Edits" tool window. Files Claude is editing right now appear at the
 * top; once an edit finishes they drop below a "Recently edited" separator and
 * linger there (see [GuardServer.RECENT_TTL_MILLIS]) before disappearing.
 * Rows show the Claude badge, file name, relative path and timing. Double-click
 * or Enter opens the file; the header shows counts and ticks once a second.
 */
class GuardToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val state = ApplicationManager.getApplication().getService(GuardState::class.java)
        val model = DefaultListModel<GuardRow>()
        val list = JBList(model)
        list.cellRenderer = GuardCellRenderer({ System.currentTimeMillis() }, project.basePath)
        list.emptyText.text = "Claude is not editing anything right now"

        val header = JBLabel().apply { border = JBUI.Borders.empty(6, 10) }

        fun openSelected() {
            val row = list.selectedValue as? GuardRow.FileRow ?: return
            val file = LocalFileSystem.getInstance().findFileByPath(row.state.path) ?: return
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
            val active = mine.filter { it.isEditing }.sortedBy { it.startedAt }
            val recent = mine.filter { !it.isEditing }.sortedByDescending { it.endedAt ?: 0L }
            SwingUtilities.invokeLater {
                model.clear()
                active.forEach { model.addElement(GuardRow.FileRow(it)) }
                if (recent.isNotEmpty()) {
                    model.addElement(GuardRow.Section("Recently edited"))
                    recent.forEach { model.addElement(GuardRow.FileRow(it)) }
                }
                header.text = headerText(active.size, recent.size)
            }
        }

        state.addListener { reload() }
        reload()

        // Tick elapsed / "ago" labels once a second (repaint only, no rebuild).
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

    private fun headerText(active: Int, recent: Int): String {
        if (active == 0 && recent == 0) return "No files in flight"
        val editing = if (active == 1) "1 file editing" else "$active files editing"
        return if (recent == 0) editing else "$editing · $recent recent"
    }
}

/**
 * Whether [path] belongs to [project] — so each project's tool window lists
 * only its own files when several projects are open at once. A git worktree
 * opened as its own project matches via its own content roots / base path, so
 * its edits show there, not in the main checkout's window.
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
) : ColoredListCellRenderer<GuardRow>() {
    override fun customizeCellRenderer(
        list: JList<out GuardRow>,
        value: GuardRow,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        when (value) {
            is GuardRow.Section -> {
                border = JBUI.Borders.empty(5, 6, 1, 6)
                append(value.title, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            is GuardRow.FileRow -> renderFile(value.state)
        }
    }

    private fun renderFile(state: ClaudeEditState) {
        icon = ClaudeIcons.CLAUDE
        border = JBUI.Borders.empty(2, 6)

        val file = File(state.path)
        val nameAttributes = if (state.isEditing) {
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(file.name, nameAttributes)

        val parentLabel = relativeParent(state.path)
        if (parentLabel.isNotEmpty()) {
            append("  $parentLabel", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        val now = clock()
        val timing = if (state.isEditing) {
            formatElapsed(now - state.startedAt)
        } else {
            formatAgo(now - (state.endedAt ?: now))
        }
        append("   $timing", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        state.sessionId?.takeIf { it.isNotBlank() }?.let {
            append("  ·  ${it.take(8)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        toolTipText = state.path
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

    private fun formatAgo(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        if (totalSeconds < 60) return "edited just now"
        val minutes = totalSeconds / 60
        return "edited ${minutes}m ago"
    }
}
