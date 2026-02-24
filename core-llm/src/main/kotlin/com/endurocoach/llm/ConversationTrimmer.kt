package com.endurocoach.llm

import com.endurocoach.domain.ConversationMessage

/**
 * Trims a conversation history list to fit within a character budget.
 * Always keeps the most recent messages, dropping the oldest first.
 * This ensures the LLM always has the most contextually relevant exchanges
 * without exceeding input token limits.
 */
object ConversationTrimmer {

    /**
     * Returns a sublist of [messages] whose combined character count
     * (role + content) fits within [maxChars], keeping the tail of the list.
     */
    fun trimToCharBudget(
        messages: List<ConversationMessage>,
        maxChars: Int
    ): List<ConversationMessage> {
        if (messages.isEmpty()) return messages

        var total = 0
        val kept = ArrayDeque<ConversationMessage>()

        for (msg in messages.reversed()) {
            val len = msg.role.length + msg.content.length + 2 // +2 for separators
            if (total + len > maxChars) break
            kept.addFirst(msg)
            total += len
        }

        return kept.toList()
    }
}
