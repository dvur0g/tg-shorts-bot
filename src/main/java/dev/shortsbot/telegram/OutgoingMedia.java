package dev.shortsbot.telegram;

import dev.shortsbot.download.MediaItem;
import dev.shortsbot.download.MediaType;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A photo or video to send: either a local file to upload or a Telegram file_id of something sent before.
 * Exactly one of {@code file} and {@code fileId} is set.
 */
public record OutgoingMedia(MediaType type, Path file, String fileId, int width, int height, int durationSec) {

    public OutgoingMedia {
        if ((file == null) == (fileId == null)) {
            throw new IllegalArgumentException("Exactly one of file and fileId must be set");
        }
    }

    public static OutgoingMedia upload(MediaItem item) {
        return new OutgoingMedia(item.type(), item.file(), null, item.width(), item.height(), item.durationSec());
    }

    public OutgoingMedia withFileId(String newFileId) {
        return new OutgoingMedia(type, null, Objects.requireNonNull(newFileId), width, height, durationSec);
    }

    public boolean isUpload() {
        return file != null;
    }
}
