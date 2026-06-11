package dev.friptu.ideguard

import com.intellij.ui.JBColor
import java.awt.Color

/** Shared highlight palette for in-flight files (light / dark variants). */
object GuardColors {
    /** Soft amber tab background while Claude is editing a file. */
    val TAB_BACKGROUND: JBColor = JBColor(Color(0xFF, 0xEC, 0xB3), Color(0x5A, 0x4A, 0x1F))

    /** Foreground for the Project view node label of an in-flight file. */
    val PROJECT_VIEW_FOREGROUND: JBColor = JBColor(Color(0xB5, 0x6A, 0x00), Color(0xF0, 0xB4, 0x4B))
}
