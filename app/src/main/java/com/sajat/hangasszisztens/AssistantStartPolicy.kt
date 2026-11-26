package com.sajat.hangasszisztens

internal object AssistantStartPolicy {
    const val USER_START_ACTION = "com.sajat.hangasszisztens.USER_START"

    fun accepts(action: String?): Boolean = action == USER_START_ACTION
}
