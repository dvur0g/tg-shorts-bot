package dev.shortsbot.telegram;

import dev.shortsbot.download.DownloadResult;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;

/** The few Telegram operations the bot needs. */
public interface ChatGateway {

    /** Uploads a video as a reply and returns its Telegram file_id for reuse. */
    Optional<String> sendVideo(ReplyTarget target, DownloadResult video) throws TelegramApiException, InterruptedException;

    /** Sends a video that is already on Telegram's servers. */
    void sendVideo(ReplyTarget target, String fileId) throws TelegramApiException, InterruptedException;

    void reply(ReplyTarget target, String text) throws TelegramApiException, InterruptedException;

    /** Shows "sending video…" in the chat for a few seconds. */
    void showUploadingVideo(ReplyTarget target) throws TelegramApiException, InterruptedException;
}
