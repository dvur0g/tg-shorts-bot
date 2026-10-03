package dev.shortsbot.telegram;

import dev.shortsbot.download.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    public List<String> sendMedia(ReplyTarget target, List<OutgoingMedia> media, String caption)
            throws TelegramApiException, InterruptedException {
        if (media.isEmpty()) {
            throw new IllegalArgumentException("Nothing to send");
        }
        if (media.size() > 1) {
            return sendAlbum(target, media, caption);
        }
        OutgoingMedia item = media.getFirst();
        Message sent = item.type() == MediaType.PHOTO ? sendPhoto(target, item, caption) : sendVideo(target, item, caption);
        var fileIds = new ArrayList<String>();
        fileIds.add(fileIdOf(sent));
        return fileIds;
    }

    private Message sendVideo(ReplyTarget target, OutgoingMedia video, String caption)
            throws TelegramApiException, InterruptedException {
        var builder = SendVideo.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .video(inputFile(video))
                .caption(caption)
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
        return withRateLimitRetry(() -> client.execute(request));
    }

    private Message sendPhoto(ReplyTarget target, OutgoingMedia photo, String caption)
            throws TelegramApiException, InterruptedException {
        SendPhoto request = SendPhoto.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .photo(inputFile(photo))
                .caption(caption)
                .disableNotification(true)
                .replyParameters(replyTo(target))
                .build();
        return withRateLimitRetry(() -> client.execute(request));
    }

    private List<String> sendAlbum(ReplyTarget target, List<OutgoingMedia> media, String caption)
            throws TelegramApiException, InterruptedException {
        var inputs = new ArrayList<InputMedia>();
        for (OutgoingMedia item : media) {
            // Telegram shows the caption of the first item as the album's caption.
            inputs.add(inputMedia(item, inputs.isEmpty() ? caption : null));
        }
        SendMediaGroup request = SendMediaGroup.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .medias(inputs)
                .disableNotification(true)
                .replyParameters(replyTo(target))
                .build();
        List<Message> sent = withRateLimitRetry(() -> client.execute(request));
        var fileIds = new ArrayList<String>();
        for (int i = 0; i < media.size(); i++) {
            fileIds.add(i < sent.size() ? fileIdOf(sent.get(i)) : null);
        }
        return fileIds;
    }

    private static InputMedia inputMedia(OutgoingMedia item, String caption) {
        if (item.type() == MediaType.PHOTO) {
            var builder = InputMediaPhoto.builder().caption(caption);
            if (item.isUpload()) {
                builder.media(item.file().toFile(), item.file().getFileName().toString());
            } else {
                builder.media(item.fileId());
            }
            return builder.build();
        }
        var builder = InputMediaVideo.builder().caption(caption).supportsStreaming(true);
        if (item.isUpload()) {
            builder.media(item.file().toFile(), item.file().getFileName().toString());
        } else {
            builder.media(item.fileId());
        }
        if (item.width() > 0 && item.height() > 0) {
            builder.width(item.width()).height(item.height());
        }
        if (item.durationSec() > 0) {
            builder.duration(item.durationSec());
        }
        return builder.build();
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
    public void showUploading(ReplyTarget target, MediaType type) throws TelegramApiException, InterruptedException {
        SendChatAction request = SendChatAction.builder()
                .chatId(target.chatId())
                .messageThreadId(target.threadId())
                .action((type == MediaType.PHOTO ? ActionType.UPLOAD_PHOTO : ActionType.UPLOAD_VIDEO).toString())
                .build();
        withRateLimitRetry(() -> client.execute(request));
    }

    private static InputFile inputFile(OutgoingMedia media) {
        return media.isUpload()
                ? new InputFile(media.file().toFile(), media.file().getFileName().toString())
                : new InputFile(media.fileId());
    }

    private static ReplyParameters replyTo(ReplyTarget target) {
        return ReplyParameters.builder()
                .messageId(target.messageId())
                .allowSendingWithoutReply(true)
                .build();
    }

    /**
     * For photos Telegram returns several sizes; the largest one's file_id resends the photo in full quality.
     * A video without audio may come back as an animation (GIF); its file_id still works for resending.
     */
    private static String fileIdOf(Message sent) {
        if (sent.getVideo() != null) {
            return sent.getVideo().getFileId();
        }
        if (sent.getPhoto() != null && !sent.getPhoto().isEmpty()) {
            return sent.getPhoto().stream()
                    .max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                    .map(PhotoSize::getFileId)
                    .orElse(null);
        }
        if (sent.getAnimation() != null) {
            return sent.getAnimation().getFileId();
        }
        return null;
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
