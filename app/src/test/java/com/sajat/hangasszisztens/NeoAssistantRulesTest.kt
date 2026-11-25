package com.sajat.hangasszisztens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoAssistantRulesTest {
    @Test
    fun stripsNeoWakeWord() {
        assertEquals("mennyi az idő", NeoAssistantRules.stripWakeWord("neo mennyi az idő"))
    }

    @Test
    fun stripsAccentedNeoWakeWord() {
        assertEquals("mennyi az idő", NeoAssistantRules.stripWakeWord("neó mennyi az idő"))
    }

    @Test
    fun stripsAlternativeAccentedNeoWakeWord() {
        assertEquals("mennyi az idő", NeoAssistantRules.stripWakeWord("néó mennyi az idő"))
    }

    @Test
    fun wakeWordIsCaseInsensitive() {
        assertEquals("nyisd ki", NeoAssistantRules.stripWakeWord("NEO nyisd ki"))
    }

    @Test
    fun wakeWordIsRemovedBeforeImmediateQuestion() {
        assertEquals("mennyi az idő?", NeoAssistantRules.stripWakeWord("Neo mennyi az idő?"))
    }

    @Test
    fun leadingAndTrailingWhitespaceIsTrimmed() {
        assertEquals("navigáció haza", NeoAssistantRules.stripWakeWord("  neo   navigáció haza  "))
    }

    @Test
    fun wakeWordPunctuationDoesNotRemainInCommand() {
        assertEquals("indulhatunk", NeoAssistantRules.stripWakeWord("neó, indulhatunk"))
    }

    @Test
    fun wakeWordOnlyReturnsAnEmptyCommand() {
        assertEquals("", NeoAssistantRules.stripWakeWord("neo"))
    }

    @Test
    fun punctuationAfterWakeWordAlsoReturnsAnEmptyCommand() {
        assertEquals("", NeoAssistantRules.stripWakeWord("néó?!"))
    }

    @Test
    fun missingWakeWordReturnsNull() {
        assertNull(NeoAssistantRules.stripWakeWord("mennyi az idő"))
    }

    @Test
    fun wakeWordInsideSentenceIsNotRemoved() {
        assertNull(NeoAssistantRules.stripWakeWord("mondd ki hogy neo"))
    }

    @Test
    fun wakeWordMustEndAtAWordBoundary() {
        assertNull(NeoAssistantRules.stripWakeWord("neon mennyi az idő"))
    }

    @Test
    fun childTopicsSelectTheChildFriendlyPersonality() {
        listOf("mese", "dínók", "Minecraft", "Fortnite", "hegedű").forEach {
            assertTrue(it, NeoAssistantRules.isChildFriendlyTopic(it))
        }
    }

    @Test
    fun ordinaryAdultQuestionKeepsTheDefaultPersonality() {
        assertFalse(NeoAssistantRules.isChildFriendlyTopic("Mennyi az idő?"))
    }
}
