package dev.shortsbot;

import dev.shortsbot.config.BotConfig;
import dev.shortsbot.config.ConfigException;
import dev.shortsbot.link.LinkExtractor;
import dev.shortsbot.telegram.ShortsBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.CountDownLatch;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

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
        TelegramClient telegramClient = new OkHttpTelegramClient(config.botToken());
        logBotIdentity(telegramClient);

        var bot = new ShortsBot(config, new LinkExtractor());
        var application = new TelegramBotsLongPollingApplication();
        application.registerBot(config.botToken(), bot);
        log.info("Bot is running; waiting for messages");

        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            try {
                application.close();
                bot.close();
            } catch (Exception e) {
                log.warn("Error during shutdown", e);
            } finally {
                stopped.countDown();
            }
        }, "shutdown"));
        stopped.await();
    }

    /** Verifies the token and warns if privacy mode would hide group messages from the bot. */
    private static void logBotIdentity(TelegramClient telegramClient) throws TelegramApiException {
        User me = telegramClient.execute(new GetMe());
        log.info("Authorized as @{} (id {})", me.getUserName(), me.getId());
        if (!Boolean.TRUE.equals(me.getCanReadAllGroupMessages())) {
            log.warn("Privacy mode is ON: in groups the bot will only see commands and mentions. "
                    + "Disable it in @BotFather (/setprivacy -> Disable), then remove and re-add the bot to the group.");
        }
    }
}
