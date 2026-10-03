package dev.shortsbot.telegram;

import java.util.List;

/** What was sent for a link, by file_id, so the same link can be answered again without downloading. */
public record SentPost(List<OutgoingMedia> media, String caption) {

    public SentPost {
        media = List.copyOf(media);
    }
}
