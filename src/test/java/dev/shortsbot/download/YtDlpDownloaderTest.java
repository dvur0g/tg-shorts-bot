package dev.shortsbot.download;

import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException.Reason;
import dev.shortsbot.link.DetectedLink;
import dev.shortsbot.link.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisabledOnOs(OS.WINDOWS)
class YtDlpDownloaderTest {

    private static final DetectedLink LINK =
            new DetectedLink(Platform.YOUTUBE, "https://www.youtube.com/shorts/dQw4w9WgXcQ", "youtube:dQw4w9WgXcQ");

    private static final DetectedLink POST =
            new DetectedLink(Platform.INSTAGRAM, "https://www.instagram.com/p/abc/", "instagram:abc");

    /** Shell snippet defining out INDEX EXT, which prints the path yt-dlp would use for that item and extension. */
    private static final String FIND_OUTPUT = """
            prev=""
            for arg in "$@"; do
              if [ "$prev" = "-o" ]; then template="$arg"; fi
              prev="$arg"
            done
            out() { echo "$template" | sed "s/%(playlist_index|0)s/$1/; s/%(ext)s/$2/"; }
            """;

    @TempDir
    Path tmp;

    @Test
    void downloadsVideoAndReadsMetadata() throws Exception {
        var downloader = downloaderWithScript("""
                printf 'fake mp4 bytes' > "$(out 0 mp4)"
                printf '{"width": 720, "height": 1280, "duration": 31.6, "title": "A short", "formats": [{}]}' \\
                  > "$(out 0 info.json)"
                """);

        DownloadResult result = downloader.download(LINK);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(MediaType.VIDEO);
            assertThat(item.file()).hasFileName("item-0.mp4").hasContent("fake mp4 bytes");
            assertThat(item.sizeBytes()).isEqualTo(14);
            assertThat(item.width()).isEqualTo(720);
            assertThat(item.height()).isEqualTo(1280);
            assertThat(item.durationSec()).isEqualTo(32);
        });
        assertThat(result.title()).isEqualTo("A short");
        assertThat(result.workDir().getParent()).isEqualTo(tmp.resolve("downloads"));
        assertThat(result.workDir()).exists();

        result.close();
        assertThat(result.workDir()).doesNotExist();
    }

    @Test
    void toleratesMissingMetadata() throws Exception {
        var downloader = downloaderWithScript("""
                printf 'x' > "$(out 0 mp4)"
                printf '{}' > "$(out 0 info.json)"
                """);

        try (DownloadResult result = downloader.download(LINK)) {
            assertThat(result.items().getFirst().width()).isZero();
            assertThat(result.items().getFirst().durationSec()).isZero();
            assertThat(result.title()).isEmpty();
            assertThat(result.caption()).isEmpty();
        }
    }

    @Test
    void reportsVideosRejectedByDurationFilter() throws Exception {
        var downloader = downloaderWithScript(
                "echo '[download] Some title does not pass filter (duration <=? 180), skipping ..'");

        assertReason(downloader, Reason.TOO_LONG);
        assertThat(tmp.resolve("downloads")).isEmptyDirectory();
    }

    @Test
    void reportsVideosRejectedBySizeLimitAndIgnoresPartialFiles() throws Exception {
        var downloader = downloaderWithScript("""
                printf 'partial' > "$(out 0 f616.mp4)"
                echo '[download] File is larger than max-filesize (349653 bytes > 102400 bytes). Aborting.'
                """);

        assertReason(downloader, Reason.TOO_LARGE);
        assertThat(tmp.resolve("downloads")).isEmptyDirectory();
    }

    @Test
    void enforcesSizeLimitOnTheFinalFile() throws Exception {
        var downloader = downloaderWithScript("""
                head -c 2097152 /dev/zero > "$(out 0 mp4)"
                printf '{}' > "$(out 0 info.json)"
                """, "MAX_FILE_MB", "1");

        assertReason(downloader, Reason.TOO_LARGE);
    }

    @Test
    void retriesAtLowerResolutionWhenTooLarge() throws Exception {
        var downloader = downloaderWithScript("""
                case "$*" in
                  *res:720*) echo '[download] File is larger than max-filesize (60000000 bytes > 51380224 bytes). Aborting.' ;;
                  *res:480*) printf 'small' > "$(out 0 mp4)"; printf '{"height": 480}' > "$(out 0 info.json)" ;;
                esac
                """);

        try (DownloadResult result = downloader.download(LINK)) {
            assertThat(result.items().getFirst().height()).isEqualTo(480);
        }
        assertThat(tmp.resolve("downloads")).as("the failed 720p attempt was cleaned up too").isEmptyDirectory();
    }

    @Test
    void reportsTooLargeWhenEvenTheLowerResolutionIs() throws Exception {
        var downloader = downloaderWithScript(
                "echo '[download] File is larger than max-filesize (1 bytes > 0 bytes). Aborting.'");

        assertReason(downloader, Reason.TOO_LARGE);
        assertThat(tmp.resolve("downloads")).isEmptyDirectory();
    }

    @Test
    void mapsErrorsFromStderr() throws Exception {
        var downloader = downloaderWithScript("""
                echo '[Instagram] Ddr0y02hRCY: Downloading JSON metadata' >&2
                echo 'ERROR: [Instagram] Ddr0y02hRCY: There is no video in this post' >&2
                exit 1
                """);

        assertThatThrownBy(() -> downloader.download(LINK))
                .isInstanceOfSatisfying(DownloadException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.NOT_A_VIDEO);
                    assertThat(e.getMessage()).isEqualTo("[Instagram] Ddr0y02hRCY: There is no video in this post");
                });
        assertThat(tmp.resolve("downloads")).isEmptyDirectory();
    }

    @Test
    void killsDownloadsThatTakeTooLong() throws Exception {
        var downloader = downloaderWithScript("sleep 30", "DOWNLOAD_TIMEOUT_SEC", "1");

        long started = System.currentTimeMillis();
        assertReason(downloader, Reason.TIMEOUT);
        assertThat(System.currentTimeMillis() - started).isLessThan(10_000);
    }

    @Test
    void reportsMissingBinary() {
        var downloader = new YtDlpDownloader(config("YTDLP_PATH", tmp.resolve("does-not-exist").toString()));

        assertReason(downloader, Reason.UNKNOWN);
    }

    @Test
    void passesACopyOfTheCookiesFile() throws Exception {
        Path cookies = Files.writeString(tmp.resolve("cookies.txt"), "# Netscape HTTP Cookie File\n");
        var downloader = downloaderWithScript("""
                prev=""
                for arg in "$@"; do
                  if [ "$prev" = "--cookies" ]; then cp "$arg" "$(out 0 mp4)"; fi
                  prev="$arg"
                done
                printf '{}' > "$(out 0 info.json)"
                """, "YTDLP_COOKIES_FILE", cookies.toString());

        try (DownloadResult result = downloader.download(LINK)) {
            assertThat(result.items().getFirst().file()).hasContent("# Netscape HTTP Cookie File");
        }
    }

    @Test
    void buildsCommandWithLimitsAndCookies() {
        var downloader = new YtDlpDownloader(config("MAX_DURATION_SEC", "60", "MAX_FILE_MB", "20"));

        var command = downloader.buildCommand(LINK, Path.of("/work"), Optional.of(Path.of("/work/cookies.txt")));

        assertThat(command.getFirst()).isEqualTo("yt-dlp");
        assertThat(command).containsSubsequence("--match-filter", "duration <=? 60")
                .containsSubsequence("--max-filesize", "20M")
                .containsSubsequence("-S", "vcodec:h264,res:720,ext:mp4:m4a")
                .containsSubsequence("--merge-output-format", "mp4")
                .containsSubsequence("-o", "/work/item-%(playlist_index|0)s.%(ext)s")
                .containsSubsequence("--no-playlist", "--playlist-items", "1")
                .containsSubsequence("--cookies", "/work/cookies.txt")
                .doesNotContain("--write-thumbnail", "--ignore-no-formats-error")
                .endsWith("--", LINK.url());
    }

    @Test
    void buildsCommandThatFetchesWholeInstagramPosts() {
        var command = new YtDlpDownloader(config()).buildCommand(POST, Path.of("/work"), Optional.empty());

        assertThat(command)
                .containsSubsequence("--playlist-items", "1:10")
                .contains("--ignore-no-formats-error", "--write-thumbnail")
                .containsSubsequence("--convert-thumbnails", "jpg")
                .doesNotContain("--no-playlist")
                .endsWith("--", POST.url());
    }

    @Test
    void collectsPhotosAndVideosOfAPostInOrderWithItsText() throws Exception {
        var downloader = downloaderWithScript("""
                printf 'jpg1' > "$(out 1 jpg)"
                printf '{"description": "  Post text  ", "title": "Post by someone", "formats": []}' > "$(out 1 info.json)"
                printf 'mp4' > "$(out 2 mp4)"
                printf 'thumb' > "$(out 2 jpg)"
                printf '{"description": "Post text", "width": 720, "height": 1280, "duration": 5, "formats": [{}]}' \\
                  > "$(out 2 info.json)"
                printf 'jpg3' > "$(out 10 jpg)"
                printf '{"description": "Post text"}' > "$(out 10 info.json)"
                echo 'ERROR: [Instagram] abc: No video formats found!' >&2
                exit 1
                """);

        try (DownloadResult result = downloader.download(POST)) {
            assertThat(result.items()).extracting(item -> item.file().getFileName().toString())
                    .containsExactly("item-1.jpg", "item-2.mp4", "item-10.jpg");
            assertThat(result.items()).extracting(MediaItem::type)
                    .containsExactly(MediaType.PHOTO, MediaType.VIDEO, MediaType.PHOTO);
            assertThat(result.caption()).isEqualTo("Post text");
            assertThat(result.title()).isEqualTo("Post by someone");
        }
    }

    @Test
    void skipsVideosOfAPostThatWereNotDownloaded() throws Exception {
        // A video rejected by the duration filter leaves only its thumbnail behind; that must not be sent as a photo.
        var downloader = downloaderWithScript("""
                printf 'jpg' > "$(out 1 jpg)"
                printf '{"formats": []}' > "$(out 1 info.json)"
                printf 'thumb' > "$(out 2 jpg)"
                printf '{"formats": [{}]}' > "$(out 2 info.json)"
                """);

        try (DownloadResult result = downloader.download(POST)) {
            assertThat(result.items()).extracting(MediaItem::type).containsExactly(MediaType.PHOTO);
        }
    }

    @Test
    void reportsPostsWithNothingToSend() throws Exception {
        var downloader = downloaderWithScript("""
                echo 'ERROR: [Instagram] abc: No video formats found!' >&2
                exit 1
                """);

        assertThatThrownBy(() -> downloader.download(POST))
                .isInstanceOfSatisfying(DownloadException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_A_VIDEO));
    }

    @Test
    void dropsPhotosOverTheTelegramLimit() throws Exception {
        var downloader = downloaderWithScript("""
                head -c 11534336 /dev/zero > "$(out 1 jpg)"
                printf '{"formats": []}' > "$(out 1 info.json)"
                printf 'small' > "$(out 2 jpg)"
                printf '{"formats": []}' > "$(out 2 info.json)"
                """);

        try (DownloadResult result = downloader.download(POST)) {
            assertThat(result.items()).extracting(item -> item.file().getFileName().toString())
                    .containsExactly("item-2.jpg");
        }
    }

    @Test
    void omitsCookiesWhenNotConfigured() {
        var command = new YtDlpDownloader(config()).buildCommand(LINK, Path.of("/work"), Optional.empty());

        assertThat(command).doesNotContain("--cookies");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies | LOGIN_REQUIRED",
            "ERROR: [Instagram] abc: Requested content is not available, rate-limit reached or login required  | LOGIN_REQUIRED",
            "ERROR: [youtube] aaaaaaaaaaa: This video is unavailable                                            | UNAVAILABLE",
            "ERROR: [youtube] abc: Private video. Sign in if you've been granted access to this video           | LOGIN_REQUIRED",
            "ERROR: [TikTok] 123: Unable to download webpage: HTTP Error 404: Not Found                         | UNAVAILABLE",
            "ERROR: [Instagram] abc: No video formats found!                                                    | NOT_A_VIDEO",
            "ERROR: unable to download video data: <urlopen error timed out>                                    | UNKNOWN",
    })
    void classifiesErrors(String stderr, Reason expected) {
        assertThat(YtDlpDownloader.classifyError(stderr, 1).reason()).isEqualTo(expected);
    }

    @Test
    void prepareRemovesLeftovers() throws Exception {
        Path downloads = Files.createDirectories(tmp.resolve("downloads").resolve("youtube-123"));
        Files.writeString(downloads.resolve("video.mp4"), "old");

        new YtDlpDownloader(config()).prepareDownloadDir();

        assertThat(tmp.resolve("downloads")).isEmptyDirectory();
    }

    private YtDlpDownloader downloaderWithScript(String body, String... extraEnv) throws IOException {
        Path script = tmp.resolve("fake-yt-dlp.sh");
        Files.writeString(script, "#!/bin/sh\n" + FIND_OUTPUT + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));

        var env = new String[extraEnv.length + 2];
        env[0] = "YTDLP_PATH";
        env[1] = script.toString();
        System.arraycopy(extraEnv, 0, env, 2, extraEnv.length);
        return new YtDlpDownloader(config(env));
    }

    private BotConfig config(String... keyValues) {
        var env = new HashMap<String, String>();
        env.put("BOT_TOKEN", "123:abc");
        env.put("DOWNLOAD_DIR", tmp.resolve("downloads").toString());
        for (int i = 0; i < keyValues.length; i += 2) {
            env.put(keyValues[i], keyValues[i + 1]);
        }
        return BotConfig.fromEnv(env);
    }

    private static void assertReason(YtDlpDownloader downloader, Reason reason) {
        assertThatThrownBy(() -> downloader.download(LINK))
                .isInstanceOfSatisfying(DownloadException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
