package dev.shortsbot.telegram;

import dev.shortsbot.download.DownloadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

public class TelegramChatGateway implements ChatGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramChatGateway.class);
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_RETRY_AFTER_SEC = 60;

    private final TelegramClient client;

    public TelegramChatGateway(TelegramClient client) {
        this.client = client;
    }

    @Override
    public Optional<String> sendVideo(ReplyTarget target, DownloadResult video) throws TelegramApiException, InterruptedException {
        var builder = SendVideo.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .video(new InputFile(video.file().toFile(), video.file().getFileName().toString()))
                .supportsStreaming(true)
                .disableNotification(true)
                .replyParameters(replyTo(target));
        if (video.width() > 0 && video.height() > 0) {
            builder.width(video.width()).height(video.height());
        }
        if (video.durationSec() > 0) {
            builder.duration(video.durationSec());
        }
        SendVideo request = builder.build();
        return fileIdOf(withRateLimitRetry(() -> client.execute(request)));
    }

    @Override
    public void sendVideo(ReplyTarget target, String fileId) throws TelegramApiException, InterruptedException {
        SendVideo request = SendVideo.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .video(new InputFile(fileId))
                .supportsStreaming(true)
                .disableNotification(true)
                .replyParameters(replyTo(target))
                .build();
        withRateLimitRetry(() -> client.execute(request));
    }

    @Override
    public void reply(ReplyTarget target, String text) throws TelegramApiException, InterruptedException {
        SendMessage request = SendMessage.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .text(text)
                .disableNotification(true)
                .replyParameters(replyTo(target))
                .build();
        withRateLimitRetry(() -> client.execute(request));
    }

    @Override
    public void showUploadingVideo(ReplyTarget target) throws TelegramApiException, InterruptedException {
        SendChatAction request = SendChatAction.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .action(ActionType.UPLOAD_VIDEO.toString())
                .build();
        withRateLimitRetry(() -> client.execute(request));
    }

    private static ReplyParameters replyTo(ReplyTarget target) {
        return ReplyParameters.builder()
                .messageId(target.messageId())
                .allowSendingWithoutReply(true)
                .build();
    }

    /** Telegram may answer a video without audio as an animation (GIF); its file_id still works for resending. */
    private static Optional<String> fileIdOf(Message sent) {
        if (sent.getVideo() != null) {
            return Optional.of(sent.getVideo().getFileId());
        }
        if (sent.getAnimation() != null) {
            return Optional.of(sent.getAnimation().getFileId());
        }
        return Optional.empty();
    }

    private static <T> T withRateLimitRetry(TelegramCall<T> call) throws TelegramApiException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.execute();
            } catch (TelegramApiRequestException e) {
                Integer retryAfter = e.getParameters() != null ? e.getParameters().getRetryAfter() : null;
                if (e.getErrorCode() != TOO_MANY_REQUESTS || retryAfter == null || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                int waitSec = Math.min(retryAfter, MAX_RETRY_AFTER_SEC);
                log.warn("Telegram rate limit hit, retrying in {} s", waitSec);
                Thread.sleep(waitSec * 1000L);
            }
        }
    }

    @FunctionalInterface
    private interface TelegramCall<T> {
        T execute() throws TelegramApiException;
    }
}
