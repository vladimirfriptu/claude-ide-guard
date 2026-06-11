package dev.friptu.ideguard

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Bundled icons. */
object ClaudeIcons {
    val WRITE: Icon = IconLoader.getIcon("/icons/claude-write.svg", ClaudeIcons::class.java)
    val READ: Icon = IconLoader.getIcon("/icons/claude-read.svg", ClaudeIcons::class.java)
}
