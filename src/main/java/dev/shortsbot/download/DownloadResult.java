package dev.shortsbot.download;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Everything downloaded for one link: a single video, or the photos and videos of an Instagram post.
 * Closing it deletes the temporary directory the files live in.
 *
 * @param items   at least one item, in the order of the original post
 * @param caption the post's text, empty if there is none
 * @param title   title as reported by the site, may be empty
 * @param workDir temporary directory owned by this result
 */
public record DownloadResult(List<MediaItem> items, String caption, String title, Path workDir) implements AutoCloseable {

    public DownloadResult {
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A download result needs at least one item");
        }
    }

    public long totalBytes() {
        return items.stream().mapToLong(MediaItem::sizeBytes).sum();
    }

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
