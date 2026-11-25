package com.sajat.hangasszisztens

internal object NeoAssistantRules {
    private val wakeWord = Regex(
        "^\\s*(?:neo|neó|néó)(?=$|\\s|[,.:;!?-])\\s*[,.:;!?-]*\\s*",
        RegexOption.IGNORE_CASE
    )
    private val childFriendlyTopic = Regex(
        "(mese|dínó|dinoszaurusz|játék|minecraft|fortnite|hegedű)",
        RegexOption.IGNORE_CASE
    )

    fun stripWakeWord(text: String): String? {
        val trimmed = text.trim()
        val match = wakeWord.find(trimmed) ?: return null
        return trimmed.substring(match.range.last + 1).trim()
    }

    fun isChildFriendlyTopic(text: String): Boolean = childFriendlyTopic.containsMatchIn(text)
}
