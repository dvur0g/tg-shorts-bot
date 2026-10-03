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

    /** Shell snippet that sets $OUT to the value passed after "-o", with %(ext)s resolved for a given extension. */
    private static final String FIND_OUTPUT = """
            prev=""
            for arg in "$@"; do
              if [ "$prev" = "-o" ]; then template="$arg"; fi
              prev="$arg"
            done
            out() { echo "$template" | sed "s/%(ext)s/$1/"; }
            """;

    @TempDir
    Path tmp;

    @Test
    void downloadsVideoAndReadsMetadata() throws Exception {
        var downloader = downloaderWithScript("""
                printf 'fake mp4 bytes' > "$(out mp4)"
                printf '{"width": 720, "height": 1280, "duration": 31.6, "title": "A short"}' > "$(out info.json)"
                """);

        DownloadResult result = downloader.download(LINK);
        assertThat(result.file()).hasFileName("video.mp4").hasContent("fake mp4 bytes");
        assertThat(result.sizeBytes()).isEqualTo(14);
        assertThat(result.width()).isEqualTo(720);
        assertThat(result.height()).isEqualTo(1280);
        assertThat(result.durationSec()).isEqualTo(32);
        assertThat(result.title()).isEqualTo("A short");
        assertThat(result.workDir().getParent()).isEqualTo(tmp.resolve("downloads"));
        assertThat(result.workDir()).exists();

        result.close();
        assertThat(result.workDir()).doesNotExist();
    }

    @Test
    void toleratesMissingMetadata() throws Exception {
        var downloader = downloaderWithScript("printf 'x' > \"$(out mp4)\"");

        try (DownloadResult result = downloader.download(LINK)) {
            assertThat(result.width()).isZero();
            assertThat(result.durationSec()).isZero();
            assertThat(result.title()).isEmpty();
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
                printf 'partial' > "$(out f616.mp4)"
                echo '[download] File is larger than max-filesize (349653 bytes > 102400 bytes). Aborting.'
                """);

        assertReason(downloader, Reason.TOO_LARGE);
        assertThat(tmp.resolve("downloads")).isEmptyDirectory();
    }

    @Test
    void enforcesSizeLimitOnTheFinalFile() throws Exception {
        var downloader = downloaderWithScript(
                "head -c 2097152 /dev/zero > \"$(out mp4)\"", "MAX_FILE_MB", "1");

        assertReason(downloader, Reason.TOO_LARGE);
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
                  if [ "$prev" = "--cookies" ]; then cp "$arg" "$(out mp4)"; fi
                  prev="$arg"
                done
                """, "YTDLP_COOKIES_FILE", cookies.toString());

        try (DownloadResult result = downloader.download(LINK)) {
            assertThat(result.file()).hasContent("# Netscape HTTP Cookie File");
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
                .containsSubsequence("-o", "/work/video.%(ext)s")
                .containsSubsequence("--cookies", "/work/cookies.txt")
                .endsWith("--", LINK.url());
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
