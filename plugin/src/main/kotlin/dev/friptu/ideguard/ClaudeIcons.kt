package dev.friptu.ideguard

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Bundled icons. */
object ClaudeIcons {
    /** The Claude Code mark, used to badge files Claude is reading or writing. */
    val CLAUDE: Icon = IconLoader.getIcon("/icons/claude.svg", ClaudeIcons::class.java)
}
