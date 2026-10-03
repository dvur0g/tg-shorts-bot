package dev.shortsbot;

import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException;
import dev.shortsbot.download.DownloadResult;
import dev.shortsbot.download.YtDlpDownloader;
import dev.shortsbot.link.DetectedLink;
import dev.shortsbot.link.LinkExtractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;

/**
 * Debugging tool: downloads the given links with the same settings the bot uses and saves them to {@code downloads/}.
 * <pre>java -cp tg-shorts-bot.jar dev.shortsbot.DownloadCli &lt;url&gt;...</pre>
 */
public final class DownloadCli {

    private DownloadCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: java -cp tg-shorts-bot.jar dev.shortsbot.DownloadCli <url>...");
            System.exit(2);
        }
        var env = new HashMap<>(System.getenv());
        env.putIfAbsent("BOT_TOKEN", "unused-by-download-cli");
        var downloader = new YtDlpDownloader(BotConfig.fromEnv(env));
        System.out.println("yt-dlp " + downloader.version());

        Path outputDir = Files.createDirectories(Path.of("downloads"));
        var extractor = new LinkExtractor();
        int failures = 0;
        for (String arg : args) {
            var links = extractor.extract(arg);
            if (links.isEmpty()) {
                System.out.println("SKIP  " + arg + " (not a supported link)");
                continue;
            }
            for (DetectedLink link : links) {
                long started = System.currentTimeMillis();
                try (DownloadResult result = downloader.download(link)) {
                    Path target = outputDir.resolve(link.canonicalId().replace(':', '_') + ".mp4");
                    Files.copy(result.file(), target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.printf("OK    %s -> %s (%.1f MB, %dx%d, %ds, %d ms) %s%n",
                            link.url(), target, result.sizeBytes() / 1048576.0, result.width(), result.height(),
                            result.durationSec(), System.currentTimeMillis() - started, result.title());
                } catch (DownloadException e) {
                    failures++;
                    System.out.printf("FAIL  %s: %s - %s%n", link.url(), e.reason(), e.getMessage());
                }
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
