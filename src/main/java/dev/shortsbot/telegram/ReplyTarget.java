package dev.shortsbot.telegram;

import org.telegram.telegrambots.meta.api.objects.message.Message;

/**
 * Where the bot answers: the chat, the forum topic (if any) and the message being replied to.
 *
 * @param threadId forum topic id, or {@code null} outside forum topics
 */
public record ReplyTarget(long chatId, Integer threadId, int messageId) {

    public static ReplyTarget of(Message message) {
        Integer threadId = Boolean.TRUE.equals(message.getIsTopicMessage()) ? message.getMessageThreadId() : null;
        return new ReplyTarget(message.getChatId(), threadId, message.getMessageId());
    }
}
