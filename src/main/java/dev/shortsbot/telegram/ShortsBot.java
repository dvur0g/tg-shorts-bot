package dev.shortsbot.telegram;

import dev.shortsbot.config.BotConfig;
import dev.shortsbot.link.DetectedLink;
import dev.shortsbot.link.LinkExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.util.DefaultLongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Receives updates from Telegram and hands every supported video link to a worker thread. */
public class ShortsBot extends DefaultLongPollingUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShortsBot.class);
    private static final int MAX_LOGGED_TEXT_LENGTH = 200;

    private static final String HELP_TEXT = """
            Send a YouTube Shorts, Instagram Reels or TikTok link and I'll reply with the video, \
            so nobody has to leave Telegram to watch it.""";

    private final BotConfig config;
    private final String botUsername;
    private final LinkExtractor linkExtractor;
    private final LinkProcessor linkProcessor;
    private final ChatGateway chat;
    private final ExecutorService workers;
    private final Set<Long> reportedIgnoredChats = ConcurrentHashMap.newKeySet();

    public ShortsBot(BotConfig config, String botUsername, LinkExtractor linkExtractor, LinkProcessor linkProcessor,
                     ChatGateway chat, ExecutorService workers) {
        this.config = config;
        this.botUsername = botUsername;
        this.linkExtractor = linkExtractor;
        this.linkProcessor = linkProcessor;
        this.chat = chat;
        this.workers = workers;
    }

    @Override
    public void consume(Update update) {
        try {
            // Edited messages arrive as update.getEditedMessage() and are deliberately ignored,
            // so fixing a typo in a message doesn't post the video again.
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle update {}", update.getUpdateId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleMessage(Message message) throws InterruptedException {
        if (message.getFrom() != null && Boolean.TRUE.equals(message.getFrom().getIsBot())) {
            return;
        }
        Chat chatInfo = message.getChat();
        if (!config.isChatAllowed(chatInfo.getId())) {
            if (reportedIgnoredChats.add(chatInfo.getId())) {
                log.info("Ignoring messages from chat {} ({} '{}'): not listed in ALLOWED_CHAT_IDS",
                        chatInfo.getId(), chatInfo.getType(), describe(chatInfo));
            }
            return;
        }
        if (log.isDebugEnabled()) {
            String text = message.hasText() ? message.getText() : message.getCaption();
            log.debug("Message in chat {} ({} '{}') from {}: {}",
                    chatInfo.getId(), chatInfo.getType(), describe(chatInfo), describe(message.getFrom()), abbreviate(text));
        }

        if (isHelpCommand(message)) {
            reply(ReplyTarget.of(message), HELP_TEXT);
            return;
        }

        List<DetectedLink> links = linkExtractor.extract(message);
        if (links.isEmpty()) {
            return;
        }
        var target = ReplyTarget.of(message);
        for (DetectedLink link : links) {
            log.info("Chat {}: {} link {} from {}", chatInfo.getId(), link.platform(), link.url(), describe(message.getFrom()));
            try {
                workers.execute(() -> linkProcessor.process(target, link));
            } catch (RejectedExecutionException e) {
                log.warn("Dropping {}: the bot is shutting down", link.canonicalId());
            }
        }
    }

    /** Matches /start and /help, also in the /help@botname form used in groups with several bots. */
    private boolean isHelpCommand(Message message) {
        if (!message.isCommand()) {
            return false;
        }
        String[] parts = message.getCommand().toLowerCase(Locale.ROOT).split("@", 2);
        boolean addressedToUs = parts.length == 1 || parts[1].equalsIgnoreCase(botUsername);
        return addressedToUs && (parts[0].equals("/start") || parts[0].equals("/help"));
    }

    private void reply(ReplyTarget target, String text) throws InterruptedException {
        try {
            chat.reply(target, text);
        } catch (TelegramApiException e) {
            log.warn("Could not reply in chat {}: {}", target.chatId(), e.getMessage());
        }
    }

    @Override
    public void close() {
        super.close();
        workers.shutdown();
    }

    private static String describe(Chat chat) {
        if (chat.getTitle() != null) {
            return chat.getTitle();
        }
        return chat.getUserName() != null ? "@" + chat.getUserName() : String.valueOf(chat.getFirstName());
    }

    private static String describe(User user) {
        if (user == null) {
            return "<unknown>";
        }
        return user.getUserName() != null ? "@" + user.getUserName() : user.getFirstName() + " (" + user.getId() + ")";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "<no text>";
        }
        String singleLine = text.replace('\n', ' ');
        return singleLine.length() <= MAX_LOGGED_TEXT_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_LOGGED_TEXT_LENGTH) + "…";
    }
}
