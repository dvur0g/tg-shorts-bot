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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Receives updates from Telegram. For now it only logs incoming messages and the video links found in them. */
public class ShortsBot extends DefaultLongPollingUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShortsBot.class);
    private static final int MAX_LOGGED_TEXT_LENGTH = 200;

    private final BotConfig config;
    private final LinkExtractor linkExtractor;
    private final Set<Long> reportedIgnoredChats = ConcurrentHashMap.newKeySet();

    public ShortsBot(BotConfig config, LinkExtractor linkExtractor) {
        this.config = config;
        this.linkExtractor = linkExtractor;
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage()) {
                handleMessage(update.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle update {}", update.getUpdateId(), e);
        }
    }

    private void handleMessage(Message message) {
        Chat chat = message.getChat();
        if (!config.isChatAllowed(chat.getId())) {
            if (reportedIgnoredChats.add(chat.getId())) {
                log.info("Ignoring messages from chat {} ({} '{}'): not listed in ALLOWED_CHAT_IDS",
                        chat.getId(), chat.getType(), describe(chat));
            }
            return;
        }

        String text = message.hasText() ? message.getText() : message.getCaption();
        log.info("Message in chat {} ({} '{}') from {}: {}",
                chat.getId(), chat.getType(), describe(chat), describe(message.getFrom()), abbreviate(text));

        for (DetectedLink link : linkExtractor.extract(message)) {
            log.info("Detected {} link {} ({})", link.platform(), link.url(), link.canonicalId());
        }
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
