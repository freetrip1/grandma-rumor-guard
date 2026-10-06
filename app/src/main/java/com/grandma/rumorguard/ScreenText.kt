package com.grandma.rumorguard

import android.view.accessibility.AccessibilityNodeInfo

/** Reads the visible text of a window: context for the AI and a fingerprint of the current video. */
object ScreenText {
    private const val MAX_NODES = 400
    /** Like counts, play times and other number-only labels that change while a video plays. */
    private val NUMERIC = Regex("^[\\d\\s.,:：/万千亿wW+%-]*$")
    private val WHITESPACE = Regex("\\s+")

    fun collect(root: AccessibilityNodeInfo?): List<String> {
        root ?: return emptyList()
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (node.isVisibleToUser) {
                for (cs in arrayOf(node.text, node.contentDescription)) {
                    val t = cs?.toString()?.replace(WHITESPACE, " ")?.trim() ?: continue
                    if (t.length in 2..300 && !NUMERIC.matches(t)) out.add(t)
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::addLast)
        }
        return out.toList()
    }

    /**
     * Fingerprint of the video on screen built from its longer texts (description, title).
     * Empty when the screen exposes no such text, meaning "unknown".
     */
    fun signature(texts: List<String>): String {
        val key = texts.filter { it.length >= 6 }.sorted().joinToString("\n")
        return if (key.isEmpty()) "" else key.hashCode().toString(16)
    }

    fun forPrompt(texts: List<String>): String = texts.joinToString(" | ").take(800)
}
