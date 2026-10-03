package dev.shortsbot.download;

import dev.shortsbot.link.DetectedLink;

public interface VideoDownloader {

    /**
     * Downloads the video into a fresh temporary directory. The caller must close the result to delete it.
     *
     * @throws DownloadException if the video can't be downloaded or doesn't fit the configured limits
     */
    DownloadResult download(DetectedLink link) throws DownloadException, InterruptedException;
}
