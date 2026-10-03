package dev.shortsbot.telegram;

import dev.shortsbot.download.MediaType;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what the bot would have sent to Telegram. */
class FakeChatGateway implements ChatGateway {

    record Sent(List<OutgoingMedia> media, String caption) {
    }

    final List<Sent> sent = new CopyOnWriteArrayList<>();
    final List<String> replies = new CopyOnWriteArrayList<>();
    volatile boolean failResends;
    volatile boolean failUploads;
    volatile boolean timeOutUploads;
    volatile boolean uploadedFilesExisted;
    private int nextFileId = 1;

    @Override
    public synchronized List<String> sendMedia(ReplyTarget target, List<OutgoingMedia> media, String caption)
            throws TelegramApiException {
        boolean upload = media.getFirst().isUpload();
        if (upload && failUploads) {
            throw new TelegramApiException("upload rejected");
        }
        if (upload && timeOutUploads) {
            throw new TelegramApiException("Unable to execute sendvideo method", new SocketTimeoutException("timeout"));
        }
        if (!upload && failResends) {
            throw new TelegramApiException("wrong file identifier");
        }
        if (upload) {
            uploadedFilesExisted = media.stream().allMatch(m -> Files.exists(m.file()));
        }
        sent.add(new Sent(media, caption));
        var fileIds = new ArrayList<String>();
        for (OutgoingMedia item : media) {
            fileIds.add(item.isUpload() ? "file-id-" + nextFileId++ : item.fileId());
        }
        return fileIds;
    }

    @Override
    public void reply(ReplyTarget target, String text) {
        replies.add(text);
    }

    @Override
    public void showUploading(ReplyTarget target, MediaType type) {
    }
}
