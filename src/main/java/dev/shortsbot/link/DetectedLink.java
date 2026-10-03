package dev.shortsbot.link;

/**
 * A supported video link found in a message.
 *
 * @param platform    where the video is hosted
 * @param url         normalized URL (no tracking parameters) to hand to the downloader
 * @param canonicalId stable key for deduplication and caching, e.g. {@code youtube:dQw4w9WgXcQ}
 */
public record DetectedLink(Platform platform, String url, String canonicalId) {

    /** Instagram {@code /p/} links are posts that may hold photos, videos or both, and usually meaningful text. */
    public boolean isInstagramPost() {
        return platform == Platform.INSTAGRAM && url.contains("instagram.com/p/");
    }
}
