package dev.shortsbot.download;

import java.nio.file.Path;

/**
 * One downloaded photo or video.
 *
 * @param width       pixels, 0 if unknown
 * @param height      pixels, 0 if unknown
 * @param durationSec whole seconds, 0 for photos or if unknown
 */
public record MediaItem(MediaType type, Path file, long sizeBytes, int width, int height, int durationSec) {
}
