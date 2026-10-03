package dev.shortsbot.telegram;

import dev.shortsbot.cache.LruCache;
import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException;
import dev.shortsbot.download.DownloadException.Reason;
import dev.shortsbot.link.LinkExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ShortsBotTest {

    private static final long GROUP_ID = -100L;

    private final FakeChatGateway chat = new FakeChatGateway();
    private final List<String> downloadedUrls = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final ShortsBot bot = newBot();

    @AfterEach
    void stopExecutors() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    @Test
    void processesEveryLinkInAMessage() throws Exception {
        bot.consume(update(message(GROUP_ID, false,
                "https://youtu.be/dQw4w9WgXcQ and https://vt.tiktok.com/ZSxyz789/")));

        assertThat(drainWorkers()).containsExactly("https://youtu.be/dQw4w9WgXcQ", "https://vt.tiktok.com/ZSxyz789/");
    }

    @Test
    void ignoresMessagesFromOtherChats() throws Exception {
        bot.consume(update(message(-999L, false, "https://youtu.be/dQw4w9WgXcQ")));

        assertThat(drainWorkers()).isEmpty();
    }

    @Test
    void ignoresMessagesFromBots() throws Exception {
        bot.consume(update(message(GROUP_ID, true, "https://youtu.be/dQw4w9WgXcQ")));

        assertThat(drainWorkers()).isEmpty();
    }

    @Test
    void ignoresEditedMessages() throws Exception {
        var update = new Update();
        update.setEditedMessage(message(GROUP_ID, false, "https://youtu.be/dQw4w9WgXcQ"));

        bot.consume(update);

        assertThat(drainWorkers()).isEmpty();
    }

    @Test
    void dropsLinksOverTheRateLimit() throws Exception {
        bot.consume(update(message(GROUP_ID, false,
                "https://youtu.be/aaaaaaaaaaa https://youtu.be/bbbbbbbbbbb https://youtu.be/ccccccccccc")));
        bot.consume(update(message(GROUP_ID, false, "https://youtu.be/ddddddddddd")));

        assertThat(drainWorkers()).hasSize(3).doesNotContain("https://youtu.be/ddddddddddd");
    }

    @Test
    void answersHelpCommands() {
        bot.consume(update(command("/help")));
        bot.consume(update(command("/start@shorts_test_bot")));
        bot.consume(update(command("/help@some_other_bot")));
        bot.consume(update(command("/settings")));

        assertThat(chat.replies).hasSize(2).allSatisfy(reply -> assertThat(reply).contains("TikTok"));
    }

    private List<String> drainWorkers() throws InterruptedException {
        workers.shutdown();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        return downloadedUrls;
    }

    private ShortsBot newBot() {
        var config = BotConfig.fromEnv(Map.of(
                "BOT_TOKEN", "123:abc",
                "ALLOWED_CHAT_IDS", String.valueOf(GROUP_ID),
                "REPLY_WITH_ERRORS", "false"));
        var processor = new LinkProcessor(config, link -> {
            downloadedUrls.add(link.url());
            throw new DownloadException(Reason.UNAVAILABLE, "test");
        }, chat, new LruCache<>(10), scheduler, Duration.ZERO);
        return new ShortsBot(config, "shorts_test_bot", new LinkExtractor(), processor, chat, workers,
                new RateLimiter(3, Duration.ofMinutes(1), Clock.systemUTC()));
    }

    private static Update update(Message message) {
        var update = new Update();
        update.setMessage(message);
        return update;
    }

    private static Message command(String text) {
        var message = message(GROUP_ID, false, text);
        message.setEntities(List.of(MessageEntity.builder()
                .type(EntityType.BOTCOMMAND).offset(0).length(text.length()).build()));
        return message;
    }

    private static Message message(long chatId, boolean fromBot, String text) {
        var message = new Message();
        message.setMessageId(1);
        message.setChat(Chat.builder().id(chatId).type("group").title("Test").build());
        message.setFrom(User.builder().id(1L).firstName("Friend").isBot(fromBot).build());
        message.setText(text);
        return message;
    }
}
