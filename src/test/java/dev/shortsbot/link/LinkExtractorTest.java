package dev.shortsbot.link;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LinkExtractorTest {

    private final LinkExtractor extractor = new LinkExtractor();

    @ParameterizedTest
    @CsvSource({
            // YouTube Shorts
            "https://www.youtube.com/shorts/dQw4w9WgXcQ,               YOUTUBE, https://www.youtube.com/shorts/dQw4w9WgXcQ, youtube:dQw4w9WgXcQ",
            "https://youtube.com/shorts/dQw4w9WgXcQ?si=AbCdEf123,      YOUTUBE, https://www.youtube.com/shorts/dQw4w9WgXcQ, youtube:dQw4w9WgXcQ",
            "http://m.youtube.com/shorts/dQw4w9WgXcQ/,                 YOUTUBE, https://www.youtube.com/shorts/dQw4w9WgXcQ, youtube:dQw4w9WgXcQ",
            "youtube.com/shorts/dQw4w9WgXcQ,                           YOUTUBE, https://www.youtube.com/shorts/dQw4w9WgXcQ, youtube:dQw4w9WgXcQ",
            "HTTPS://WWW.YOUTUBE.COM/shorts/dQw4w9WgXcQ,               YOUTUBE, https://www.youtube.com/shorts/dQw4w9WgXcQ, youtube:dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?feature=shared,              YOUTUBE, https://youtu.be/dQw4w9WgXcQ,               youtube:dQw4w9WgXcQ",
            // Instagram
            "https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=MWQ1ZGUx, INSTAGRAM, https://www.instagram.com/reel/C1a2B3c4D5e/, instagram:C1a2B3c4D5e",
            "https://instagram.com/reels/C1a2B3c4D5e,                  INSTAGRAM, https://www.instagram.com/reel/C1a2B3c4D5e/, instagram:C1a2B3c4D5e",
            "https://www.instagram.com/p/C1a2B3c4D5e/,                 INSTAGRAM, https://www.instagram.com/p/C1a2B3c4D5e/,    instagram:C1a2B3c4D5e",
            "https://www.instagram.com/some.user_1/reel/C1a2B3c4D5e/,  INSTAGRAM, https://www.instagram.com/reel/C1a2B3c4D5e/, instagram:C1a2B3c4D5e",
            "https://m.instagram.com/reel/C1a2-B3_c4D/,                INSTAGRAM, https://www.instagram.com/reel/C1a2-B3_c4D/, instagram:C1a2-B3_c4D",
            // TikTok
            "https://www.tiktok.com/@some.user_1/video/7301234567890123456?is_from_webapp=1, TIKTOK, https://www.tiktok.com/@some.user_1/video/7301234567890123456, tiktok:7301234567890123456",
            "https://m.tiktok.com/v/7301234567890123456.html,          TIKTOK, https://m.tiktok.com/v/7301234567890123456.html, tiktok:7301234567890123456",
            "https://vm.tiktok.com/ZMabc123/,                          TIKTOK, https://vm.tiktok.com/ZMabc123/,              tiktok-short:ZMabc123",
            "vt.tiktok.com/ZSxyz789,                                   TIKTOK, https://vt.tiktok.com/ZSxyz789/,              tiktok-short:ZSxyz789",
            "https://www.tiktok.com/t/ZTabc123/,                       TIKTOK, https://www.tiktok.com/t/ZTabc123/,           tiktok-short:ZTabc123",
    })
    void recognizesSupportedLinks(String input, Platform platform, String url, String canonicalId) {
        assertThat(extractor.extract(input))
                .containsExactly(new DetectedLink(platform, url, canonicalId));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/@channel",
            "https://youtu.be/short",
            "https://www.youtube.com/shorts/dQw4w9WgXcQextra",
            "https://music.youtube.com/shorts/dQw4w9WgXcQ",
            "https://fakeyoutube.com/shorts/dQw4w9WgXcQ",
            "https://evil.example/?u=youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.instagram.com/some.user/",
            "https://www.instagram.com/stories/some.user/123456/",
            "https://www.tiktok.com/@some.user",
            "https://www.tiktok.com/@some.user/photo/7301234567890123456",
            "https://vimeo.com/123456",
            "just some text without links",
            "",
    })
    void ignoresUnsupportedLinks(String input) {
        assertThat(extractor.extract(input)).isEmpty();
    }

    @Test
    void findsLinksInsideTextAndStripsTrailingPunctuation() {
        var links = extractor.extract("""
                lol look at this (https://youtu.be/dQw4w9WgXcQ).
                and this one: https://vm.tiktok.com/ZMabc123!!""");

        assertThat(links).extracting(DetectedLink::canonicalId)
                .containsExactly("youtube:dQw4w9WgXcQ", "tiktok-short:ZMabc123");
    }

    @Test
    void deduplicatesTheSameVideoInDifferentForms() {
        var links = extractor.extract(
                "https://youtu.be/dQw4w9WgXcQ https://www.youtube.com/shorts/dQw4w9WgXcQ?si=x youtu.be/dQw4w9WgXcQ");

        assertThat(links).extracting(DetectedLink::canonicalId).containsExactly("youtube:dQw4w9WgXcQ");
    }

    @Test
    void capsTheNumberOfLinksPerMessage() {
        var text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            text.append("https://www.tiktok.com/@user/video/").append(1000 + i).append(' ');
        }

        assertThat(extractor.extract(text.toString())).hasSize(LinkExtractor.MAX_LINKS_PER_MESSAGE);
    }

    @Test
    void readsHiddenTextLinks() {
        var message = new Message();
        message.setText("check this out");
        message.setEntities(List.of(MessageEntity.builder()
                .type(EntityType.TEXTLINK).offset(6).length(8)
                .url("https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=abc")
                .build()));

        assertThat(extractor.extract(message)).extracting(DetectedLink::canonicalId)
                .containsExactly("instagram:C1a2B3c4D5e");
    }

    @Test
    void readsUrlEntitiesWithNonAsciiTextBeforeThem() {
        var text = "😂😂 смотри youtube.com/shorts/dQw4w9WgXcQ";
        var url = "youtube.com/shorts/dQw4w9WgXcQ";
        var message = new Message();
        message.setText(text);
        message.setEntities(List.of(MessageEntity.builder()
                .type(EntityType.URL).offset(text.indexOf(url)).length(url.length())
                .build()));

        assertThat(extractor.extract(message)).extracting(DetectedLink::canonicalId)
                .containsExactly("youtube:dQw4w9WgXcQ");
    }

    @Test
    void readsCaptions() {
        var message = new Message();
        message.setCaption("forwarded https://vt.tiktok.com/ZSxyz789/");

        assertThat(extractor.extract(message)).extracting(DetectedLink::platform)
                .containsExactly(Platform.TIKTOK);
    }

    @Test
    void handlesMessagesWithoutText() {
        assertThat(extractor.extract(new Message())).isEmpty();
    }
}
