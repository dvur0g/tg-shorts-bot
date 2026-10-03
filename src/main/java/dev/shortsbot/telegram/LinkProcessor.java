package dev.shortsbot.telegram;

import dev.shortsbot.cache.LruCache;
import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException;
import dev.shortsbot.download.DownloadResult;
import dev.shortsbot.download.MediaType;
import dev.shortsbot.download.VideoDownloader;
import dev.shortsbot.link.DetectedLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Turns one detected link into a reply: cache lookup, download (with one retry), upload, error reply.
 * Instagram posts are sent as a photo/video or album together with the post's text.
 */
public class LinkProcessor {

    private static final Logger log = LoggerFactory.getLogger(LinkProcessor.class);

    /** Telegram shows a chat action for about 5 seconds, so it is refreshed slightly more often. */
    private static final long CHAT_ACTION_INTERVAL_SEC = 4;

    private final BotConfig config;
    private final VideoDownloader downloader;
    private final ChatGateway chat;
    private final LruCache<SentPost> cache;
    private final ScheduledExecutorService scheduler;
    private final Duration retryDelay;

    public LinkProcessor(BotConfig config, VideoDownloader downloader, ChatGateway chat, LruCache<SentPost> cache,
                         ScheduledExecutorService scheduler, Duration retryDelay) {
        this.config = config;
        this.downloader = downloader;
        this.chat = chat;
        this.cache = cache;
        this.scheduler = scheduler;
        this.retryDelay = retryDelay;
    }

    public void process(ReplyTarget target, DetectedLink link) {
        try {
            if (sendCached(target, link)) {
                return;
            }
            downloadAndSend(target, link);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Stopped processing {} because the bot is shutting down", link.canonicalId());
        }
    }

    private boolean sendCached(ReplyTarget target, DetectedLink link) throws InterruptedException {
        Optional<SentPost> cached = cache.get(link.canonicalId());
        if (cached.isEmpty()) {
            return false;
        }
        try {
            deliver(target, cached.get().media(), cached.get().caption());
            log.info("Sent {} from cache", link.canonicalId());
            return true;
        } catch (TelegramApiException e) {
            log.warn("Resending cached {} failed, downloading again: {}", link.canonicalId(), e.getMessage());
            cache.remove(link.canonicalId());
            return false;
        }
    }

    private void downloadAndSend(ReplyTarget target, DetectedLink link) throws InterruptedException {
        MediaType expected = link.isInstagramPost() ? MediaType.PHOTO : MediaType.VIDEO;
        ScheduledFuture<?> chatAction = scheduler.scheduleAtFixedRate(
                () -> showUploading(target, expected), 0, CHAT_ACTION_INTERVAL_SEC, TimeUnit.SECONDS);
        long started = System.nanoTime();
        try (DownloadResult result = downloadWithRetry(link)) {
            long downloadedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            List<OutgoingMedia> media = result.items().stream().map(OutgoingMedia::upload).toList();
            // Reels and other short videos are sent without text; for posts the text is often the point.
            String caption = link.isInstagramPost() ? result.caption() : "";
            List<String> fileIds = deliver(target, media, caption);
            remember(link, media, fileIds, caption);
            log.info("Sent {} ({} item(s), {} KB, downloaded in {} ms, total {} ms)", link.canonicalId(),
                    media.size(), result.totalBytes() / 1024, downloadedMs,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (DownloadException e) {
            chatAction.cancel(false);
            log.info("Could not download {}: {} - {}", link.url(), e.reason(), e.getMessage());
            replyWithError(target, FailureMessages.forReason(e.reason(), link, config));
        } catch (TelegramApiException e) {
            chatAction.cancel(false);
            if (isTimeout(e)) {
                // The upload may well have succeeded and the media may still appear, so don't claim it failed.
                log.warn("Upload of {} to chat {} timed out waiting for Telegram's answer", link.canonicalId(), target.chatId());
                return;
            }
            log.error("Could not send {} to chat {}", link.canonicalId(), target.chatId(), e);
            replyWithError(target, FailureMessages.uploadFailed());
        } finally {
            chatAction.cancel(false);
        }
    }

    /**
     * Sends the media with the caption under it. Telegram captions are limited to 1024 characters, so a longer
     * text goes into a separate reply right after the media instead of being cut short.
     */
    private List<String> deliver(ReplyTarget target, List<OutgoingMedia> media, String caption)
            throws TelegramApiException, InterruptedException {
        if (caption.isEmpty()) {
            return chat.sendMedia(target, media, null);
        }
        if (caption.length() <= ChatGateway.CAPTION_LIMIT) {
            return chat.sendMedia(target, media, caption);
        }
        List<String> fileIds = chat.sendMedia(target, media, null);
        try {
            chat.reply(target, truncate(caption, ChatGateway.MESSAGE_LIMIT));
        } catch (TelegramApiException e) {
            log.warn("Could not send the post text to chat {}: {}", target.chatId(), e.getMessage());
        }
        return fileIds;
    }

    private void remember(DetectedLink link, List<OutgoingMedia> media, List<String> fileIds, String caption) {
        if (fileIds.size() != media.size() || fileIds.stream().anyMatch(Objects::isNull)) {
            log.debug("Not caching {}: Telegram didn't return a file_id for every item", link.canonicalId());
            return;
        }
        var cached = new ArrayList<OutgoingMedia>();
        for (int i = 0; i < media.size(); i++) {
            cached.add(media.get(i).withFileId(fileIds.get(i)));
        }
        cache.put(link.canonicalId(), new SentPost(cached, caption));
    }

    private DownloadResult downloadWithRetry(DetectedLink link) throws DownloadException, InterruptedException {
        try {
            return downloader.download(link);
        } catch (DownloadException e) {
            if (!e.reason().isRetryable()) {
                throw e;
            }
            log.info("Download of {} failed ({}: {}), retrying once", link.url(), e.reason(), e.getMessage());
            Thread.sleep(retryDelay.toMillis());
            return downloader.download(link);
        }
    }

    static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        int end = limit - 1;
        // Don't cut an emoji or other surrogate pair in half.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private void showUploading(ReplyTarget target, MediaType type) {
        try {
            chat.showUploading(target, type);
        } catch (TelegramApiException e) {
            log.debug("Could not send chat action: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void replyWithError(ReplyTarget target, String text) throws InterruptedException {
        if (!config.replyWithErrors()) {
            return;
        }
        try {
            chat.reply(target, text);
        } catch (TelegramApiException e) {
            log.warn("Could not send error reply to chat {}: {}", target.chatId(), e.getMessage());
        }
    }
}
