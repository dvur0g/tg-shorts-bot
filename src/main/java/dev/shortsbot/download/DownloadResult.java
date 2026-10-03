package dev.shortsbot.download;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A downloaded video. Closing it deletes the temporary directory the video lives in.
 *
 * @param file        the mp4 file
 * @param sizeBytes   size of {@code file}
 * @param width       video width in pixels, 0 if unknown
 * @param height      video height in pixels, 0 if unknown
 * @param durationSec duration in whole seconds, 0 if unknown
 * @param title       video title, may be empty
 * @param workDir     temporary directory owned by this result
 */
public record DownloadResult(
        Path file,
        long sizeBytes,
        int width,
        int height,
        int durationSec,
        String title,
        Path workDir
) implements AutoCloseable {

    @Override
    public void close() {
        deleteRecursively(workDir);
    }

    static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
