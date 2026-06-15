package dev.friptu.ideguard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
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

/** A row in the tool window: section header, file entry, or worktree block. */
private sealed interface GuardRow {
    data class Section(val title: String) : GuardRow
    data class FileRow(val view: FileView) : GuardRow
    data class WorktreeHeader(val count: Int, val expanded: Boolean) : GuardRow
    data class WorktreeFileRow(val view: FileView, val label: String) : GuardRow
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

        var worktreesExpanded = false
        var reloadRef: (() -> Unit)? = null

        val header = JBLabel().apply { border = JBUI.Borders.empty(6, 10) }

        fun openSelected() {
            val row = list.selectedValue as? GuardRow.FileRow ?: return
            val file = LocalFileSystem.getInstance().findFileByPath(row.view.path) ?: return
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

        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1) return
                val idx = list.locationToIndex(e.point)
                if (idx < 0 || idx >= model.size) return
                if (model.getElementAt(idx) is GuardRow.WorktreeHeader) {
                    worktreesExpanded = !worktreesExpanded
                    reloadRef?.invoke()
                }
            }
        })

        fun reload() {
            val showWt = GuardSettings.getInstance().showWorktreeActivity
            val views = buildProjectViews()
            val thisView = views.firstOrNull { it.projectId == project.locationHash }
            val snapshot = state.snapshot()

            val own = ArrayList<FileView>()
            val wt = ArrayList<Pair<FileView, String>>()
            if (thisView != null) {
                for (v in snapshot) {
                    when (Ownership.bucketFor(v.path, thisView, views)) {
                        OwnershipBucket.OWN -> own.add(v)
                        OwnershipBucket.WORKTREE -> if (showWt) {
                            val root = Ownership.matchingWorkingRoot(v.path, thisView)
                            if (root != null) wt.add(v to Ownership.worktreeParentLabel(v.path, root))
                        }
                        OwnershipBucket.NONE -> {}
                    }
                }
            }

            val active = own.filter { it.isActive }.sortedBy { it.startedAt }
            val recent = own.filter { !it.isActive }.sortedByDescending { it.endedAt ?: 0L }
            val wtSorted = wt.sortedWith(
                compareByDescending<Pair<FileView, String>> { it.first.isActive }
                    .thenByDescending { it.first.startedAt },
            )
            SwingUtilities.invokeLater {
                // Keep all worktreesExpanded access on the EDT (the toggle runs here too).
                if (wtSorted.isEmpty()) worktreesExpanded = false
                model.clear()
                active.forEach { model.addElement(GuardRow.FileRow(it)) }
                if (recent.isNotEmpty()) {
                    model.addElement(GuardRow.Section("Recently edited"))
                    recent.forEach { model.addElement(GuardRow.FileRow(it)) }
                }
                if (showWt && wtSorted.isNotEmpty()) {
                    model.addElement(GuardRow.WorktreeHeader(wtSorted.size, worktreesExpanded))
                    if (worktreesExpanded) {
                        wtSorted.forEach { (view, label) -> model.addElement(GuardRow.WorktreeFileRow(view, label)) }
                    }
                }
                header.text = headerText(active.size, recent.size)
            }
        }
        reloadRef = ::reload

        // Tie the listener to the tool window's lifecycle. GuardState is an
        // app-level service that outlives this content, so without unsubscribing
        // every reopened tool window (or closed project) would leak its whole
        // Swing tree and Project, and every lock change would re-run stale reloads.
        val subscription = state.addListener { reload() }
        Disposer.register(toolWindow.disposable) { subscription.unsubscribe() }
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

/** Builds an ownership view for every open project (worktree roots from git). */
private fun buildProjectViews(): List<ProjectView> {
    val projects = ProjectManager.getInstance().openProjects
    return projects.mapNotNull { p ->
        val base = p.basePath ?: return@mapNotNull null
        val working = WorktreeResolver.allWorkingRoots(base).filter { it != base }
        ProjectView(
            projectId = p.locationHash,
            basePath = base,
            workingRoots = working,
        ) { path -> directlyContains(p, path) }
    }
}

/** True if [path] is in [project]'s content roots or under its base path. */
private fun directlyContains(project: Project, path: String): Boolean {
    val file = LocalFileSystem.getInstance().findFileByPath(path)
    if (file != null) {
        val inContent = try {
            ReadAction.compute<Boolean, RuntimeException> {
                !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(file)
            }
        } catch (e: com.intellij.serviceContainer.AlreadyDisposedException) {
            false
        }
        if (inContent) return true
    }
    val base = project.basePath ?: return false
    return Ownership.isUnder(path, base)
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
            is GuardRow.WorktreeHeader -> {
                border = JBUI.Borders.empty(5, 6, 1, 6)
                val arrow = if (value.expanded) "▾" else "▸"
                append("$arrow Worktrees (${value.count})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            is GuardRow.FileRow -> renderFile(value.view)
            is GuardRow.WorktreeFileRow -> renderWorktreeFile(value.view, value.label)
        }
    }

    private fun renderFile(view: FileView) {
        icon = ClaudeIcons.CLAUDE
        border = JBUI.Borders.empty(2, 6)

        val file = File(view.path)
        val nameAttributes = if (view.isActive) {
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(file.name, nameAttributes)

        val parentLabel = relativeParent(view.path)
        if (parentLabel.isNotEmpty()) {
            append("  $parentLabel", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        append("   ${timingLabel(view)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        val firstSession = view.sessionIds.firstOrNull()
        if (firstSession != null) {
            append("  ·  ${firstSession.take(8)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        toolTipText = view.path
    }

    private fun renderWorktreeFile(view: FileView, label: String) {
        icon = ClaudeIcons.CLAUDE
        border = JBUI.Borders.empty(2, 16) // indented under the Worktrees header

        val file = File(view.path)
        val nameAttributes = if (view.isActive) {
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
        } else {
            SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(file.name, nameAttributes)
        append("  $label", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        append("   ${timingLabel(view)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        toolTipText = view.path + "  —  open the worktree as its own project to edit"
    }

    private fun timingLabel(view: FileView): String {
        val now = clock()
        return when {
            view.isActive && view.mode == LockMode.WRITE -> "writing " + formatElapsed(now - view.startedAt)
            view.isActive -> "reading " + formatElapsed(now - view.startedAt)
            else -> formatAgo(now - (view.endedAt ?: now))
        }
    }

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
