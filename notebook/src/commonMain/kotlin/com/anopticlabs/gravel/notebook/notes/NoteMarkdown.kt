package com.anopticlabs.gravel.notebook.notes

/** Renders a note's on-disk markdown: a front matter block, a blank line, the body. */
object NoteMarkdown {

    /**
     * [value] as a single-line, double-quoted YAML scalar: control characters removed, edges
     * trimmed, backslashes escaped before quotes.
     */
    fun yamlSafe(value: String): String {
        val escaped = value.stripControlChars().trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /** [recorded] is a voice note's [com.anopticlabs.gravel.notebook.capture.RecordedAt] value; a typed note has none. */
    fun buildMarkdown(title: String, tags: List<String>, content: String, date: String, recorded: String? = null): String {
        val tagsStr = tags.joinToString(", ") { yamlSafe(it) }
        val recordedLine = recorded?.let { "recorded: $it\n" }.orEmpty()
        return "---\ntitle: ${yamlSafe(title)}\ndate: $date\n${recordedLine}tags: [$tagsStr]\ntype: note\n---\n\n$content\n"
    }
}
