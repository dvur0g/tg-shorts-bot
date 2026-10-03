package dev.shortsbot;

import dev.shortsbot.cache.LruCache;
import dev.shortsbot.config.BotConfig;
import dev.shortsbot.config.ConfigException;
import dev.shortsbot.download.YtDlpDownloader;
import dev.shortsbot.link.LinkExtractor;
import dev.shortsbot.telegram.LinkProcessor;
import dev.shortsbot.telegram.SentPost;
import dev.shortsbot.telegram.ShortsBot;
import dev.shortsbot.telegram.TelegramChatGateway;
import dev.shortsbot.telegram.TelegramHttp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final Duration DOWNLOAD_RETRY_DELAY = Duration.ofSeconds(3);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);
    private static final int SENT_CACHE_SIZE = 500;

    private Main() {
    }

    public static void main(String[] args) {
        BotConfig config;
        try {
            config = BotConfig.fromEnv();
        } catch (ConfigException e) {
            log.error("Invalid configuration: {}", e.getMessage());
            System.exit(2);
            return;
        }
        log.info("Starting tg-shorts-bot with {}", config);
        if (config.vpnEnabled()) {
            log.warn("VPN_ENABLED=true is not supported yet; continuing without VPN");
        }

        try {
            run(config);
        } catch (Exception e) {
            log.error("Bot stopped because of an unrecoverable error", e);
            System.exit(1);
        }
    }

    private static void run(BotConfig config) throws Exception {
        var downloader = new YtDlpDownloader(config);
        prepareDownloader(config, downloader);

        TelegramClient telegramClient = new OkHttpTelegramClient(TelegramHttp.newClient(), config.botToken());
        User me = logBotIdentity(telegramClient);

        var chat = new TelegramChatGateway(telegramClient);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("chat-action").daemon().factory());
        ExecutorService workers = Executors.newFixedThreadPool(config.workerThreads(),
                Thread.ofPlatform().name("download-", 1).factory());
        var processor = new LinkProcessor(config, downloader, chat, new LruCache<SentPost>(SENT_CACHE_SIZE), scheduler,
                DOWNLOAD_RETRY_DELAY);
        var bot = new ShortsBot(config, me.getUserName(), new LinkExtractor(), processor, chat, workers);

        var application = new TelegramBotsLongPollingApplication();
        application.registerBot(config.botToken(), bot);
        log.info("Bot is running; waiting for links");

        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            try {
                application.close();
                bot.close();
                if (!workers.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                    log.warn("Downloads still running after {} s, cancelling them", SHUTDOWN_GRACE.toSeconds());
                    workers.shutdownNow();
                    workers.awaitTermination(10, TimeUnit.SECONDS);
                }
                scheduler.shutdownNow();
            } catch (Exception e) {
                log.warn("Error during shutdown", e);
            } finally {
                stopped.countDown();
            }
        }, "shutdown"));
        stopped.await();
    }

    private static void prepareDownloader(BotConfig config, YtDlpDownloader downloader) throws IOException, InterruptedException {
        downloader.prepareDownloadDir();
        if (config.ytDlpAutoUpdate()) {
            downloader.selfUpdate();
        }
        try {
            log.info("Using yt-dlp {}", downloader.version());
        } catch (IOException e) {
            throw new IOException("yt-dlp is not usable (YTDLP_PATH=" + config.ytDlpPath() + "): " + e.getMessage(), e);
        }
    }

    /** Verifies the token and warns if privacy mode would hide group messages from the bot. */
    private static User logBotIdentity(TelegramClient telegramClient) throws TelegramApiException {
        User me = telegramClient.execute(new GetMe());
        log.info("Authorized as @{} (id {})", me.getUserName(), me.getId());
        if (!Boolean.TRUE.equals(me.getCanReadAllGroupMessages())) {
            log.warn("Privacy mode is ON: in groups the bot will only see commands and mentions. "
                    + "Disable it in @BotFather (/setprivacy -> Disable), then remove and re-add the bot to the group.");
        }
        return me;
    }
}
