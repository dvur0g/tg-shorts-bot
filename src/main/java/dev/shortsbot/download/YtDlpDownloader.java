package dev.shortsbot.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException.Reason;
import dev.shortsbot.link.DetectedLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Downloads videos by running the yt-dlp command line tool (which uses ffmpeg to merge streams). */
public class YtDlpDownloader implements VideoDownloader {

    private static final Logger log = LoggerFactory.getLogger(YtDlpDownloader.class);

    /** Every item of a post becomes item-N.mp4 / item-N.jpg / item-N.info.json; single videos are item-0. */
    static final String OUTPUT_TEMPLATE = "item-%(playlist_index|0)s.%(ext)s";
    private static final Pattern INFO_FILE = Pattern.compile("item-(\\d+)\\.info\\.json");
    /** Telegram's Bot API upload limits are 50 MB per video and 10 MB per photo. */
    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    /** A Telegram album holds at most 10 items. */
    static final int MAX_POST_ITEMS = 10;
    private static final String STDOUT_FILE = "yt-dlp.out";
    private static final String STDERR_FILE = "yt-dlp.err";
    private static final Duration KILL_GRACE = Duration.ofSeconds(5);
    private static final String FFPROBE = "ffprobe";
    private static final Duration UTILITY_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Prefer H.264 (plays inline in every Telegram client; TikTok often serves H.265 by default),
     * at most 720p, in an mp4/m4a container so merging needs no re-encoding.
     */
    private static final String FORMAT_SORT = "vcodec:h264,res:720,ext:mp4:m4a";

    private final BotConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public YtDlpDownloader(BotConfig config) {
        this.config = config;
    }

    /** Creates the download directory and removes anything left over from a previous run. */
    public void prepareDownloadDir() throws IOException {
        Files.createDirectories(config.downloadDir());
        try (Stream<Path> leftovers = Files.list(config.downloadDir())) {
            leftovers.forEach(DownloadResult::deleteRecursively);
        }
    }

    /** Returns the installed yt-dlp version; fails if yt-dlp can't be run at all. */
    public String version() throws IOException, InterruptedException {
        return runUtility(List.of(config.ytDlpPath(), "--version")).strip();
    }

    /** Runs {@code yt-dlp -U}; failures are logged, not thrown, since an older yt-dlp may still work. */
    public void selfUpdate() throws InterruptedException {
        try {
            log.info("yt-dlp self-update: {}", runUtility(List.of(config.ytDlpPath(), "-U")).strip());
        } catch (IOException e) {
            log.warn("yt-dlp self-update failed: {}", e.getMessage());
        }
    }

    @Override
    public DownloadResult download(DetectedLink link) throws DownloadException, InterruptedException {
        Path workDir;
        try {
            Files.createDirectories(config.downloadDir());
            workDir = Files.createTempDirectory(config.downloadDir(), link.platform().name().toLowerCase(Locale.ROOT) + "-");
        } catch (IOException e) {
            throw new DownloadException(Reason.UNKNOWN, "Can't create a download directory", e);
        }

        boolean success = false;
        try {
            DownloadResult result = runDownload(link, workDir);
            success = true;
            return result;
        } finally {
            if (!success) {
                DownloadResult.deleteRecursively(workDir);
            }
        }
    }

    private DownloadResult runDownload(DetectedLink link, Path workDir) throws DownloadException, InterruptedException {
        List<String> command = buildCommand(link, workDir, copyCookies(workDir));
        log.debug("Running {}", command);

        long started = System.nanoTime();
        int exitCode = runYtDlp(command, workDir);
        String stdout = readQuietly(workDir.resolve(STDOUT_FILE));
        String stderr = readQuietly(workDir.resolve(STDERR_FILE));
        log.debug("yt-dlp exited with {} after {} ms\nstdout:\n{}\nstderr:\n{}",
                exitCode, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), stdout, stderr);

        // Success is judged by the files produced, not the exit code: for an Instagram post with photos
        // yt-dlp reports "No video formats found" and exits with 1 even though the photos were saved.
        var items = new ArrayList<MediaItem>();
        var infos = new ArrayList<JsonNode>();
        boolean droppedTooLarge = false;
        for (int index : itemIndexes(workDir)) {
            JsonNode info = readInfo(workDir.resolve("item-" + index + ".info.json"));
            Optional<MediaItem> item = collectItem(workDir, index, info);
            if (item.isEmpty()) {
                continue;
            }
            if (item.get().sizeBytes() > maxBytes(item.get().type())) {
                log.info("Skipping {} ({} KB): over the Telegram upload limit", item.get().file().getFileName(),
                        item.get().sizeBytes() / 1024);
                droppedTooLarge = true;
                continue;
            }
            items.add(item.get());
            infos.add(info);
        }

        if (items.isEmpty()) {
            if (droppedTooLarge) {
                throw new DownloadException(Reason.TOO_LARGE, "Larger than the " + config.maxFileMb() + " MB limit");
            }
            throw exitCode != 0 ? classifyError(stderr, exitCode) : classifySkip(stdout);
        }
        JsonNode first = infos.getFirst();
        return new DownloadResult(items, first.path("description").asText("").strip(), first.path("title").asText(""), workDir);
    }

    private static List<Integer> itemIndexes(Path workDir) throws DownloadException {
        try (Stream<Path> files = Files.list(workDir)) {
            return files
                    .map(file -> INFO_FILE.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DownloadException(Reason.UNKNOWN, "Can't list downloaded files", e);
        }
    }

    /**
     * An item is a video if yt-dlp saved item-N.mp4. Otherwise, if the item has no video formats at all, it is a
     * photo, saved as item-N.jpg by --write-thumbnail. Anything else (e.g. a video skipped by the duration filter,
     * which leaves only its thumbnail) is not sendable.
     */
    private Optional<MediaItem> collectItem(Path workDir, int index, JsonNode info) throws DownloadException, InterruptedException {
        Path video = workDir.resolve("item-" + index + ".mp4");
        if (Files.isRegularFile(video)) {
            return Optional.of(videoItem(video, info));
        }
        Path photo = workDir.resolve("item-" + index + ".jpg");
        if (Files.isRegularFile(photo) && info.path("formats").isEmpty()) {
            return Optional.of(new MediaItem(MediaType.PHOTO, photo, sizeOf(photo), 0, 0, 0));
        }
        return Optional.empty();
    }

    private MediaItem videoItem(Path video, JsonNode info) throws DownloadException, InterruptedException {
        int width = info.path("width").asInt(0);
        int height = info.path("height").asInt(0);
        int duration = (int) Math.round(info.path("duration").asDouble(0));
        if (width == 0 || height == 0 || duration == 0) {
            // Instagram often returns no dimensions/duration without a login; read them from the file instead.
            JsonNode probe = probe(video);
            JsonNode stream = probe.path("streams").path(0);
            width = width != 0 ? width : stream.path("width").asInt(0);
            height = height != 0 ? height : stream.path("height").asInt(0);
            duration = duration != 0 ? duration : (int) Math.round(probe.path("format").path("duration").asDouble(0));
        }
        return new MediaItem(MediaType.VIDEO, video, sizeOf(video), width, height, duration);
    }

    private JsonNode probe(Path video) throws InterruptedException {
        try {
            return objectMapper.readTree(runUtility(List.of(
                    FFPROBE, "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream=width,height:format=duration",
                    "-of", "json",
                    video.toString())));
        } catch (IOException e) {
            log.warn("ffprobe failed for {}; sending video without dimensions: {}", video, e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    List<String> buildCommand(DetectedLink link, Path workDir, Optional<Path> cookiesFile) {
        var command = new ArrayList<>(List.of(
                config.ytDlpPath(),
                "--no-progress",
                "--no-warnings",
                "--no-colors",
                "--restrict-filenames",
                "--socket-timeout", "20",
                // "<=?" lets videos without a known duration through instead of rejecting them
                "--match-filter", "duration <=? " + config.maxDurationSec(),
                "--max-filesize", config.maxFileMb() + "M",
                "-S", FORMAT_SORT,
                "--merge-output-format", "mp4",
                "--remux-video", "mp4",
                "--write-info-json",
                "--no-write-playlist-metafiles",
                "-o", workDir.resolve(OUTPUT_TEMPLATE).toString()));
        if (link.isInstagramPost()) {
            // A post may be a carousel of photos and videos. Photos have no formats, so yt-dlp must not give up
            // on them; their full-size image is the "thumbnail", which yt-dlp downloads through the same proxy.
            command.addAll(List.of(
                    "--playlist-items", "1:" + MAX_POST_ITEMS,
                    "--ignore-no-formats-error",
                    "--write-thumbnail",
                    "--convert-thumbnails", "jpg"));
        } else {
            command.addAll(List.of("--no-playlist", "--playlist-items", "1"));
        }
        cookiesFile.ifPresent(cookies -> command.addAll(List.of("--cookies", cookies.toString())));
        command.add("--");
        command.add(link.url());
        return command;
    }

    /**
     * yt-dlp writes cookies back to the file when it exits, which fails on a read-only mount
     * and could corrupt the original, so every download gets its own copy.
     */
    private Optional<Path> copyCookies(Path workDir) {
        if (config.ytDlpCookiesFile().isEmpty()) {
            return Optional.empty();
        }
        Path source = config.ytDlpCookiesFile().get();
        if (!Files.isReadable(source)) {
            log.warn("Cookies file {} is not readable; downloading without cookies", source);
            return Optional.empty();
        }
        try {
            return Optional.of(Files.copy(source, workDir.resolve("cookies.txt")));
        } catch (IOException e) {
            log.warn("Can't copy cookies file {}; downloading without cookies", source, e);
            return Optional.empty();
        }
    }

    private int runYtDlp(List<String> command, Path workDir) throws DownloadException, InterruptedException {
        Process process;
        try {
            process = newProcess(command)
                    .redirectOutput(workDir.resolve(STDOUT_FILE).toFile())
                    .redirectError(workDir.resolve(STDERR_FILE).toFile())
                    .start();
        } catch (IOException e) {
            throw new DownloadException(Reason.UNKNOWN, "Can't start yt-dlp: " + e.getMessage(), e);
        }

        try {
            if (!process.waitFor(config.downloadTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new DownloadException(Reason.TIMEOUT,
                        "Download took longer than " + config.downloadTimeout().toSeconds() + " seconds");
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        }
    }

    private static String runUtility(List<String> command) throws IOException, InterruptedException {
        Process process = newProcess(command).redirectErrorStream(true).start();
        // Output of these commands is tiny, so reading it fully before waiting can't fill the pipe.
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(UTILITY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            kill(process);
            throw new IOException("'" + String.join(" ", command) + "' timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("'" + String.join(" ", command) + "' exited with " + process.exitValue() + ": " + output.strip());
        }
        return output;
    }

    private static ProcessBuilder newProcess(List<String> command) {
        var builder = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        return builder;
    }

    private static File nullDevice() {
        return new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }

    /** Kills yt-dlp together with the ffmpeg processes it may have started. */
    private static void kill(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    static DownloadException classifyError(String stderr, int exitCode) {
        String message = lastErrorLine(stderr).orElse("yt-dlp exited with code " + exitCode);
        String lower = stderr.toLowerCase(Locale.ROOT);
        Reason reason;
        if (containsAny(lower, "there is no video in this post", "no video formats found", "no video could be found")) {
            reason = Reason.NOT_A_VIDEO;
        } else if (containsAny(lower, "login required", "log in", "sign in", "--cookies", "authentication", "not logged in")) {
            reason = Reason.LOGIN_REQUIRED;
        } else if (containsAny(lower, "unavailable", "private video", "has been removed", "not available",
                "does not exist", "http error 404", "http error 410")) {
            reason = Reason.UNAVAILABLE;
        } else {
            reason = Reason.UNKNOWN;
        }
        return new DownloadException(reason, message);
    }

    /** yt-dlp exits with 0 but downloads nothing when a video is rejected by --match-filter or --max-filesize. */
    private DownloadException classifySkip(String stdout) {
        if (stdout.contains("does not pass filter")) {
            return new DownloadException(Reason.TOO_LONG, "Video is longer than " + config.maxDurationSec() + " seconds");
        }
        if (stdout.contains("larger than max-filesize")) {
            return new DownloadException(Reason.TOO_LARGE, "Video is larger than " + config.maxFileMb() + " MB");
        }
        return new DownloadException(Reason.UNKNOWN, "yt-dlp finished without producing anything to send");
    }

    private static Optional<String> lastErrorLine(String stderr) {
        return stderr.lines()
                .filter(line -> line.startsWith("ERROR:"))
                .reduce((first, second) -> second)
                .map(line -> line.substring("ERROR:".length()).strip());
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode readInfo(Path infoFile) {
        try {
            return objectMapper.readTree(infoFile.toFile());
        } catch (IOException e) {
            log.warn("Can't read yt-dlp metadata {}; sending video without dimensions", infoFile, e);
            return objectMapper.createObjectNode();
        }
    }

    private long maxBytes(MediaType type) {
        return type == MediaType.PHOTO ? MAX_PHOTO_BYTES : config.maxFileMb() * 1024L * 1024L;
    }

    private static long sizeOf(Path file) throws DownloadException {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new DownloadException(Reason.UNKNOWN, "Can't read downloaded file", e);
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
