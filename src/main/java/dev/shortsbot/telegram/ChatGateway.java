package dev.shortsbot.telegram;

import dev.shortsbot.download.MediaType;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

/** The few Telegram operations the bot needs. */
public interface ChatGateway {

    /**
     * Sends one photo/video, or an album of 2-10, as a reply.
     *
     * @param caption text shown under the media (at most {@link #CAPTION_LIMIT} characters), or {@code null}
     * @return the Telegram file_id of each item in the same order; an element is {@code null} if Telegram didn't return one
     */
    List<String> sendMedia(ReplyTarget target, List<OutgoingMedia> media, String caption)
            throws TelegramApiException, InterruptedException;

    void reply(ReplyTarget target, String text) throws TelegramApiException, InterruptedException;

    /** Shows "sending video…" / "sending photo…" in the chat for a few seconds. */
    void showUploading(ReplyTarget target, MediaType type) throws TelegramApiException, InterruptedException;

    int CAPTION_LIMIT = 1024;
    int MESSAGE_LIMIT = 4096;
}
