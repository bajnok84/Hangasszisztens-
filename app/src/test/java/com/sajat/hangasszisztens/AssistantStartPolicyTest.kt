package com.sajat.hangasszisztens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStartPolicyTest {
    @Test
    fun acceptsOnlyTheExplicitUserStartAction() {
        assertTrue(AssistantStartPolicy.accepts(AssistantStartPolicy.USER_START_ACTION))
        assertFalse(AssistantStartPolicy.accepts(null))
        assertFalse(AssistantStartPolicy.accepts(""))
        assertFalse(AssistantStartPolicy.accepts("android.intent.action.MEDIA_BUTTON"))
        assertFalse(AssistantStartPolicy.accepts("com.sajat.hangasszisztens.AUTO_START"))
    }
}
