package dev.shortsbot.telegram;

import dev.shortsbot.cache.FileIdCache;
import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException;
import dev.shortsbot.download.DownloadResult;
import dev.shortsbot.download.VideoDownloader;
import dev.shortsbot.link.DetectedLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Turns one detected link into a video reply: cache lookup, download (with one retry), upload, error reply. */
public class LinkProcessor {

    private static final Logger log = LoggerFactory.getLogger(LinkProcessor.class);

    /** Telegram shows a chat action for about 5 seconds, so it is refreshed slightly more often. */
    private static final long CHAT_ACTION_INTERVAL_SEC = 4;

    private final BotConfig config;
    private final VideoDownloader downloader;
    private final ChatGateway chat;
    private final FileIdCache cache;
    private final ScheduledExecutorService scheduler;
    private final Duration retryDelay;

    public LinkProcessor(BotConfig config, VideoDownloader downloader, ChatGateway chat, FileIdCache cache,
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
        Optional<String> fileId = cache.get(link.canonicalId());
        if (fileId.isEmpty()) {
            return false;
        }
        try {
            chat.sendVideo(target, fileId.get());
            log.info("Sent {} from cache", link.canonicalId());
            return true;
        } catch (TelegramApiException e) {
            log.warn("Resending cached {} failed, downloading again: {}", link.canonicalId(), e.getMessage());
            cache.remove(link.canonicalId());
            return false;
        }
    }

    private void downloadAndSend(ReplyTarget target, DetectedLink link) throws InterruptedException {
        ScheduledFuture<?> chatAction = scheduler.scheduleAtFixedRate(
                () -> showUploadingVideo(target), 0, CHAT_ACTION_INTERVAL_SEC, TimeUnit.SECONDS);
        long started = System.nanoTime();
        try (DownloadResult video = downloadWithRetry(link)) {
            long downloadedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            chat.sendVideo(target, video).ifPresent(fileId -> cache.put(link.canonicalId(), fileId));
            log.info("Sent {} ({} KB, downloaded in {} ms, total {} ms)", link.canonicalId(), video.sizeBytes() / 1024,
                    downloadedMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (DownloadException e) {
            chatAction.cancel(false);
            log.info("Could not download {}: {} - {}", link.url(), e.reason(), e.getMessage());
            replyWithError(target, FailureMessages.forReason(e.reason(), link, config));
        } catch (TelegramApiException e) {
            chatAction.cancel(false);
            if (isTimeout(e)) {
                // The upload may well have succeeded and the video may still appear, so don't claim it failed.
                log.warn("Upload of {} to chat {} timed out waiting for Telegram's answer", link.canonicalId(), target.chatId());
                return;
            }
            log.error("Could not send {} to chat {}", link.canonicalId(), target.chatId(), e);
            replyWithError(target, FailureMessages.uploadFailed());
        } finally {
            chatAction.cancel(false);
        }
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

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private void showUploadingVideo(ReplyTarget target) {
        try {
            chat.showUploadingVideo(target);
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
