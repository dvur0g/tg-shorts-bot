package dev.shortsbot.telegram;

import dev.shortsbot.cache.FileIdCache;
import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException;
import dev.shortsbot.download.DownloadException.Reason;
import dev.shortsbot.download.DownloadResult;
import dev.shortsbot.download.VideoDownloader;
import dev.shortsbot.link.DetectedLink;
import dev.shortsbot.link.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class LinkProcessorTest {

    private static final ReplyTarget TARGET = new ReplyTarget(-100L, null, 7);
    private static final DetectedLink LINK =
            new DetectedLink(Platform.INSTAGRAM, "https://www.instagram.com/reel/abc/", "instagram:abc");

    @TempDir
    Path tmp;

    private final FakeChatGateway chat = new FakeChatGateway();
    private final FileIdCache cache = new FileIdCache();
    private final ScriptedDownloader downloader = new ScriptedDownloader();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void downloadsSendsCachesAndCleansUp() throws Exception {
        DownloadResult video = downloader.willReturn("first");

        processor(true).process(TARGET, LINK);

        assertThat(chat.uploads).containsExactly("first");
        assertThat(chat.uploadedFileExisted).isTrue();
        assertThat(video.workDir()).doesNotExist();
        assertThat(cache.get("instagram:abc")).contains("file-id-1");
        assertThat(chat.replies).isEmpty();
    }

    @Test
    void resendsCachedVideoWithoutDownloading() {
        cache.put("instagram:abc", "cached-id");

        processor(true).process(TARGET, LINK);

        assertThat(chat.resends).containsExactly("cached-id");
        assertThat(downloader.calls).isZero();
    }

    @Test
    void downloadsAgainWhenCachedFileIdIsRejected() throws Exception {
        cache.put("instagram:abc", "stale-id");
        chat.failResends = true;
        downloader.willReturn("fresh");

        processor(true).process(TARGET, LINK);

        assertThat(chat.uploads).containsExactly("fresh");
        assertThat(cache.get("instagram:abc")).contains("file-id-1");
    }

    @Test
    void repliesWithReasonAndDoesNotRetryPermanentFailures() {
        downloader.willFail(Reason.TOO_LONG);

        processor(true).process(TARGET, LINK);

        assertThat(downloader.calls).isEqualTo(1);
        assertThat(chat.replies).containsExactly("⏱ Too long: I only fetch videos up to 3 min.");
        assertThat(chat.uploads).isEmpty();
    }

    @Test
    void retriesTransientFailuresOnce() throws Exception {
        downloader.willFail(Reason.UNKNOWN);
        downloader.willReturn("second try");

        processor(true).process(TARGET, LINK);

        assertThat(downloader.calls).isEqualTo(2);
        assertThat(chat.uploads).containsExactly("second try");
        assertThat(chat.replies).isEmpty();
    }

    @Test
    void repliesOnceWhenRetryAlsoFails() {
        downloader.willFail(Reason.TIMEOUT);
        downloader.willFail(Reason.TIMEOUT);

        processor(true).process(TARGET, LINK);

        assertThat(downloader.calls).isEqualTo(2);
        assertThat(chat.replies).containsExactly("⌛ The download took too long, gave up.");
    }

    @Test
    void mentionsPlatformWhenLoginIsRequired() {
        downloader.willFail(Reason.LOGIN_REQUIRED);

        processor(true).process(TARGET, LINK);

        assertThat(chat.replies).containsExactly("🔒 Instagram wants a login for this one, so I can't fetch it.");
    }

    @Test
    void staysSilentWhenErrorRepliesAreDisabled() {
        downloader.willFail(Reason.UNAVAILABLE);

        processor(false).process(TARGET, LINK);

        assertThat(chat.replies).isEmpty();
    }

    @Test
    void cleansUpAndRepliesWhenUploadFails() throws Exception {
        DownloadResult video = downloader.willReturn("rejected");
        chat.failUploads = true;

        processor(true).process(TARGET, LINK);

        assertThat(video.workDir()).doesNotExist();
        assertThat(cache.get("instagram:abc")).isEmpty();
        assertThat(chat.replies).containsExactly("😕 Downloaded the video but Telegram didn't accept it.");
    }

    @Test
    void staysSilentWhenUploadTimesOutBecauseItMayHaveSucceeded() throws Exception {
        DownloadResult video = downloader.willReturn("slow");
        chat.timeOutUploads = true;

        processor(true).process(TARGET, LINK);

        assertThat(video.workDir()).doesNotExist();
        assertThat(chat.replies).isEmpty();
    }

    private LinkProcessor processor(boolean replyWithErrors) {
        var config = BotConfig.fromEnv(Map.of(
                "BOT_TOKEN", "123:abc",
                "REPLY_WITH_ERRORS", String.valueOf(replyWithErrors)));
        return new LinkProcessor(config, downloader, chat, cache, scheduler, Duration.ZERO);
    }

    /** Returns queued results or failures in order. */
    private class ScriptedDownloader implements VideoDownloader {

        private final Deque<Object> outcomes = new ArrayDeque<>();
        int calls;

        DownloadResult willReturn(String title) throws Exception {
            Path workDir = Files.createTempDirectory(tmp, "job-");
            Path file = Files.writeString(workDir.resolve("video.mp4"), "mp4");
            var result = new DownloadResult(file, 3, 720, 1280, 10, title, workDir);
            outcomes.add(result);
            return result;
        }

        void willFail(Reason reason) {
            outcomes.add(new DownloadException(reason, reason.name()));
        }

        @Override
        public DownloadResult download(DetectedLink link) throws DownloadException {
            calls++;
            Object outcome = outcomes.remove();
            if (outcome instanceof DownloadException e) {
                throw e;
            }
            return (DownloadResult) outcome;
        }
    }
}
