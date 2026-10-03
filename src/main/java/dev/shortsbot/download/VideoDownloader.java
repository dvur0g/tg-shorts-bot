package dev.shortsbot.download;

import dev.shortsbot.link.DetectedLink;

public interface VideoDownloader {

    /**
     * Downloads the video (or the photos and videos of an Instagram post) into a fresh temporary directory.
     * The caller must close the result to delete it.
     *
     * @throws DownloadException if nothing could be downloaded or nothing fits the configured limits
     */
    DownloadResult download(DetectedLink link) throws DownloadException, InterruptedException;
}
