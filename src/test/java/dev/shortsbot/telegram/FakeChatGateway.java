package dev.shortsbot.telegram;

import dev.shortsbot.download.DownloadResult;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what the bot would have sent to Telegram. */
class FakeChatGateway implements ChatGateway {

    final List<String> uploads = new CopyOnWriteArrayList<>();
    final List<String> resends = new CopyOnWriteArrayList<>();
    final List<String> replies = new CopyOnWriteArrayList<>();
    volatile boolean failResends;
    volatile boolean failUploads;
    volatile boolean timeOutUploads;
    volatile boolean uploadedFileExisted;

    @Override
    public Optional<String> sendVideo(ReplyTarget target, DownloadResult video) throws TelegramApiException {
        if (failUploads) {
            throw new TelegramApiException("upload rejected");
        }
        if (timeOutUploads) {
            throw new TelegramApiException("Unable to execute sendvideo method", new SocketTimeoutException("timeout"));
        }
        uploadedFileExisted = Files.exists(video.file());
        uploads.add(video.title());
        return Optional.of("file-id-" + uploads.size());
    }

    @Override
    public void sendVideo(ReplyTarget target, String fileId) throws TelegramApiException {
        if (failResends) {
            throw new TelegramApiException("wrong file identifier");
        }
        resends.add(fileId);
    }

    @Override
    public void reply(ReplyTarget target, String text) {
        replies.add(text);
    }

    @Override
    public void showUploadingVideo(ReplyTarget target) {
    }
}
