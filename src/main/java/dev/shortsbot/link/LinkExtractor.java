package dev.shortsbot.link;

import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds YouTube Shorts, Instagram Reels and TikTok links in messages. */
public class LinkExtractor {

    public static final int MAX_LINKS_PER_MESSAGE = 5;

    private static final String SCHEME = "^(?:https?://)?";
    private static final String NOT_ID_CHAR = "(?![A-Za-z0-9_-])";

    /**
     * Any URL-looking token on one of the supported domains, used to scan raw text. It must start the text or
     * follow whitespace or an opening bracket/quote, so links embedded inside other URLs are not picked up.
     */
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?<![^\\s(\\[{<«\"'])(?:https?://)?(?:[a-z0-9-]+\\.)*(?:youtube\\.com|youtu\\.be|instagram\\.com|tiktok\\.com)/[^\\s<>\"']+",
            Pattern.CASE_INSENSITIVE);

    private static final List<Rule> RULES = List.of(
            new Rule(SCHEME + "(?:www\\.|m\\.)?youtube\\.com/shorts/([A-Za-z0-9_-]{11})" + NOT_ID_CHAR,
                    m -> new DetectedLink(Platform.YOUTUBE,
                            "https://www.youtube.com/shorts/" + m.group(1), "youtube:" + m.group(1))),
            new Rule(SCHEME + "youtu\\.be/([A-Za-z0-9_-]{11})" + NOT_ID_CHAR,
                    m -> new DetectedLink(Platform.YOUTUBE,
                            "https://youtu.be/" + m.group(1), "youtube:" + m.group(1))),
            new Rule(SCHEME + "(?:www\\.|m\\.)?instagram\\.com/(?:[A-Za-z0-9_.]+/)?(reels?|p)/([A-Za-z0-9_-]+)",
                    m -> new DetectedLink(Platform.INSTAGRAM,
                            "https://www.instagram.com/" + ("p".equalsIgnoreCase(m.group(1)) ? "p" : "reel")
                                    + "/" + m.group(2) + "/",
                            "instagram:" + m.group(2))),
            new Rule(SCHEME + "(?:www\\.|m\\.)?tiktok\\.com/@([A-Za-z0-9_.]+)/video/(\\d+)",
                    m -> new DetectedLink(Platform.TIKTOK,
                            "https://www.tiktok.com/@" + m.group(1) + "/video/" + m.group(2), "tiktok:" + m.group(2))),
            new Rule(SCHEME + "m\\.tiktok\\.com/v/(\\d+)",
                    m -> new DetectedLink(Platform.TIKTOK,
                            "https://m.tiktok.com/v/" + m.group(1) + ".html", "tiktok:" + m.group(1))),
            new Rule(SCHEME + "(vm|vt)\\.tiktok\\.com/([A-Za-z0-9]+)",
                    m -> new DetectedLink(Platform.TIKTOK,
                            "https://" + m.group(1).toLowerCase() + ".tiktok.com/" + m.group(2) + "/",
                            "tiktok-short:" + m.group(2))),
            new Rule(SCHEME + "(?:www\\.|m\\.)?tiktok\\.com/t/([A-Za-z0-9]+)",
                    m -> new DetectedLink(Platform.TIKTOK,
                            "https://www.tiktok.com/t/" + m.group(1) + "/", "tiktok-short:" + m.group(1)))
    );

    /** Extracts links from a message's text and caption, including hidden links behind formatted text. */
    public List<DetectedLink> extract(Message message) {
        var candidates = new ArrayList<String>();
        collect(message.getText(), message.getEntities(), candidates);
        collect(message.getCaption(), message.getCaptionEntities(), candidates);
        return detect(candidates);
    }

    /** Extracts links from plain text. */
    public List<DetectedLink> extract(String text) {
        var candidates = new ArrayList<String>();
        collect(text, null, candidates);
        return detect(candidates);
    }

    private static void collect(String text, List<MessageEntity> entities, List<String> candidates) {
        if (entities != null) {
            for (MessageEntity entity : entities) {
                if (EntityType.TEXTLINK.equals(entity.getType()) && entity.getUrl() != null) {
                    candidates.add(entity.getUrl());
                } else if (EntityType.URL.equals(entity.getType()) && text != null) {
                    substring(text, entity).ifPresent(candidates::add);
                }
            }
        }
        if (text != null) {
            Matcher matcher = CANDIDATE.matcher(text);
            while (matcher.find()) {
                candidates.add(matcher.group());
            }
        }
    }

    /** Entity offsets are in UTF-16 code units, which is exactly what Java string indices are. */
    private static Optional<String> substring(String text, MessageEntity entity) {
        Integer offset = entity.getOffset();
        Integer length = entity.getLength();
        if (offset == null || length == null || offset < 0 || offset + length > text.length()) {
            return Optional.empty();
        }
        return Optional.of(text.substring(offset, offset + length));
    }

    private static List<DetectedLink> detect(List<String> candidates) {
        Map<String, DetectedLink> links = new LinkedHashMap<>();
        for (String candidate : candidates) {
            match(candidate.strip()).ifPresent(link -> links.putIfAbsent(link.canonicalId(), link));
            if (links.size() == MAX_LINKS_PER_MESSAGE) {
                break;
            }
        }
        return List.copyOf(links.values());
    }

    private static Optional<DetectedLink> match(String candidate) {
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(candidate);
            if (matcher.find()) {
                return Optional.of(rule.toLink().apply(matcher));
            }
        }
        return Optional.empty();
    }

    private record Rule(Pattern pattern, Function<Matcher, DetectedLink> toLink) {

        Rule(String regex, Function<Matcher, DetectedLink> toLink) {
            this(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), toLink);
        }
    }
}
