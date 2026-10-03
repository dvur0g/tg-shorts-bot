package dev.shortsbot.telegram;

import dev.shortsbot.cache.LruCache;
import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException;
import dev.shortsbot.download.DownloadException.Reason;
import dev.shortsbot.download.DownloadResult;
import dev.shortsbot.download.MediaItem;
import dev.shortsbot.download.MediaType;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class LinkProcessorTest {

    private static final ReplyTarget TARGET = new ReplyTarget(-100L, null, 7);
    private static final DetectedLink REEL =
            new DetectedLink(Platform.INSTAGRAM, "https://www.instagram.com/reel/abc/", "instagram:abc");
    private static final DetectedLink POST =
            new DetectedLink(Platform.INSTAGRAM, "https://www.instagram.com/p/xyz/", "instagram:xyz");

    @TempDir
    Path tmp;

    private final FakeChatGateway chat = new FakeChatGateway();
    private final LruCache<SentPost> cache = new LruCache<>(10);
    private final ScriptedDownloader downloader = new ScriptedDownloader();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void downloadsSendsCachesAndCleansUp() throws Exception {
        DownloadResult video = downloader.willReturn("reel text", MediaType.VIDEO);

        processor(true).process(TARGET, REEL);

        assertThat(chat.sent).singleElement().satisfies(sent -> {
            assertThat(sent.media()).extracting(OutgoingMedia::type).containsExactly(MediaType.VIDEO);
            assertThat(sent.caption()).as("reels are sent without text").isNull();
        });
        assertThat(chat.uploadedFilesExisted).isTrue();
        assertThat(video.workDir()).doesNotExist();
        assertThat(cache.get("instagram:abc")).hasValueSatisfying(post ->
                assertThat(post.media()).extracting(OutgoingMedia::fileId).containsExactly("file-id-1"));
        assertThat(chat.replies).isEmpty();
    }

    @Test
    void sendsPostItemsAsOneAlbumWithTheText() throws Exception {
        downloader.willReturn("Look at this", MediaType.PHOTO, MediaType.VIDEO, MediaType.PHOTO);

        processor(true).process(TARGET, POST);

        assertThat(chat.sent).singleElement().satisfies(sent -> {
            assertThat(sent.media()).extracting(OutgoingMedia::type)
                    .containsExactly(MediaType.PHOTO, MediaType.VIDEO, MediaType.PHOTO);
            assertThat(sent.caption()).isEqualTo("Look at this");
        });
        assertThat(chat.replies).isEmpty();
    }

    @Test
    void sendsLongPostTextAsSeparateMessage() throws Exception {
        String text = "a".repeat(ChatGateway.CAPTION_LIMIT + 1);
        downloader.willReturn(text, MediaType.PHOTO);

        processor(true).process(TARGET, POST);

        assertThat(chat.sent).singleElement().satisfies(sent -> assertThat(sent.caption()).isNull());
        assertThat(chat.replies).containsExactly(text);
    }

    @Test
    void resendsCachedPostWithItsText() throws Exception {
        downloader.willReturn("Caption", MediaType.PHOTO, MediaType.PHOTO);
        var processor = processor(true);
        processor.process(TARGET, POST);

        processor.process(TARGET, POST);

        assertThat(downloader.calls).isEqualTo(1);
        assertThat(chat.sent).hasSize(2);
        assertThat(chat.sent.get(1).media()).extracting(OutgoingMedia::fileId).containsExactly("file-id-1", "file-id-2");
        assertThat(chat.sent.get(1).caption()).isEqualTo("Caption");
    }

    @Test
    void downloadsAgainWhenCachedFileIdIsRejected() throws Exception {
        cache.put("instagram:abc", new SentPost(List.of(
                new OutgoingMedia(MediaType.VIDEO, null, "stale-id", 0, 0, 0)), ""));
        chat.failResends = true;
        downloader.willReturn("", MediaType.VIDEO);

        processor(true).process(TARGET, REEL);

        assertThat(chat.sent).singleElement().satisfies(sent -> assertThat(sent.media().getFirst().isUpload()).isTrue());
        assertThat(cache.get("instagram:abc")).hasValueSatisfying(post ->
                assertThat(post.media().getFirst().fileId()).isEqualTo("file-id-1"));
    }

    @Test
    void repliesWithReasonAndDoesNotRetryPermanentFailures() {
        downloader.willFail(Reason.TOO_LONG);

        processor(true).process(TARGET, REEL);

        assertThat(downloader.calls).isEqualTo(1);
        assertThat(chat.replies).containsExactly("⏱ Too long: I only fetch videos up to 3 min.");
        assertThat(chat.sent).isEmpty();
    }

    @Test
    void retriesTransientFailuresOnce() throws Exception {
        downloader.willFail(Reason.UNKNOWN);
        downloader.willReturn("", MediaType.VIDEO);

        processor(true).process(TARGET, REEL);

        assertThat(downloader.calls).isEqualTo(2);
        assertThat(chat.sent).hasSize(1);
        assertThat(chat.replies).isEmpty();
    }

    @Test
    void repliesOnceWhenRetryAlsoFails() {
        downloader.willFail(Reason.TIMEOUT);
        downloader.willFail(Reason.TIMEOUT);

        processor(true).process(TARGET, REEL);

        assertThat(downloader.calls).isEqualTo(2);
        assertThat(chat.replies).containsExactly("⌛ The download took too long, gave up.");
    }

    @Test
    void mentionsPlatformWhenLoginIsRequired() {
        downloader.willFail(Reason.LOGIN_REQUIRED);

        processor(true).process(TARGET, REEL);

        assertThat(chat.replies).containsExactly("🔒 Instagram wants a login for this one, so I can't fetch it.");
    }

    @Test
    void staysSilentWhenErrorRepliesAreDisabled() {
        downloader.willFail(Reason.UNAVAILABLE);

        processor(false).process(TARGET, REEL);

        assertThat(chat.replies).isEmpty();
    }

    @Test
    void cleansUpAndRepliesWhenUploadFails() throws Exception {
        DownloadResult video = downloader.willReturn("", MediaType.VIDEO);
        chat.failUploads = true;

        processor(true).process(TARGET, REEL);

        assertThat(video.workDir()).doesNotExist();
        assertThat(cache.get("instagram:abc")).isEmpty();
        assertThat(chat.replies).containsExactly("😕 Downloaded the video but Telegram didn't accept it.");
    }

    @Test
    void staysSilentWhenUploadTimesOutBecauseItMayHaveSucceeded() throws Exception {
        DownloadResult video = downloader.willReturn("", MediaType.VIDEO);
        chat.timeOutUploads = true;

        processor(true).process(TARGET, REEL);

        assertThat(video.workDir()).doesNotExist();
        assertThat(chat.replies).isEmpty();
    }

    @Test
    void truncatesWithoutSplittingEmoji() {
        assertThat(LinkProcessor.truncate("short", 10)).isEqualTo("short");
        assertThat(LinkProcessor.truncate("abcdefghijkl", 10)).isEqualTo("abcdefghi…");
        // "😀" is two UTF-16 chars; cutting at the limit would leave half of it.
        assertThat(LinkProcessor.truncate("abcdefgh😀xyz", 10)).isEqualTo("abcdefgh…");
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

        DownloadResult willReturn(String caption, MediaType... types) throws Exception {
            Path workDir = Files.createTempDirectory(tmp, "job-");
            var items = new ArrayList<MediaItem>();
            for (int i = 0; i < types.length; i++) {
                Path file = Files.writeString(workDir.resolve("item-" + i + (types[i] == MediaType.PHOTO ? ".jpg" : ".mp4")), "x");
                items.add(new MediaItem(types[i], file, 1, 720, 1280, types[i] == MediaType.VIDEO ? 10 : 0));
            }
            var result = new DownloadResult(items, caption, "title", workDir);
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
