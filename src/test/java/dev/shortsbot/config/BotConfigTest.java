package dev.shortsbot.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotConfigTest {

    private static Map<String, String> env(String... keyValues) {
        var env = new HashMap<String, String>();
        env.put("BOT_TOKEN", "123:abc");
        for (int i = 0; i < keyValues.length; i += 2) {
            env.put(keyValues[i], keyValues[i + 1]);
        }
        return env;
    }

    @Test
    void usesDefaultsWhenOnlyTokenIsSet() {
        var config = BotConfig.fromEnv(env());

        assertThat(config.botToken()).isEqualTo("123:abc");
        assertThat(config.allowedChatIds()).isEmpty();
        assertThat(config.maxDurationSec()).isEqualTo(180);
        assertThat(config.maxFileMb()).isEqualTo(49);
        assertThat(config.downloadTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(config.workerThreads()).isEqualTo(2);
        assertThat(config.maxQueuedLinks()).isEqualTo(20);
        assertThat(config.rateLimitPerMinute()).isEqualTo(10);
        assertThat(config.downloadDir()).isEqualTo(Path.of("/tmp/shortsbot"));
        assertThat(config.ytDlpPath()).isEqualTo("yt-dlp");
        assertThat(config.ytDlpCookiesFile()).isEmpty();
        assertThat(config.ytDlpAutoUpdate()).isFalse();
        assertThat(config.replyWithErrors()).isTrue();
        assertThat(config.vpnEnabled()).isFalse();
        assertThat(config.vpnSsUrl()).isEmpty();
    }

    @Test
    void readsAllValues() {
        var config = BotConfig.fromEnv(env(
                "ALLOWED_CHAT_IDS", " -1001234567890, 42 ,",
                "MAX_DURATION_SEC", "60",
                "MAX_FILE_MB", "20",
                "DOWNLOAD_TIMEOUT_SEC", "30",
                "WORKER_THREADS", "4",
                "MAX_QUEUED_LINKS", "5",
                "RATE_LIMIT_PER_MINUTE", "0",
                "DOWNLOAD_DIR", "/data/dl",
                "YTDLP_PATH", "/usr/local/bin/yt-dlp",
                "YTDLP_COOKIES_FILE", "/app/secrets/cookies.txt",
                "YTDLP_AUTO_UPDATE", "yes",
                "REPLY_WITH_ERRORS", "false",
                "VPN_ENABLED", "TRUE",
                "VPN_SS_URL", "ss://secret@host:1234"));

        assertThat(config.allowedChatIds()).containsExactlyInAnyOrder(-1001234567890L, 42L);
        assertThat(config.maxDurationSec()).isEqualTo(60);
        assertThat(config.maxFileMb()).isEqualTo(20);
        assertThat(config.downloadTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.workerThreads()).isEqualTo(4);
        assertThat(config.maxQueuedLinks()).isEqualTo(5);
        assertThat(config.rateLimitPerMinute()).isZero();
        assertThat(config.downloadDir()).isEqualTo(Path.of("/data/dl"));
        assertThat(config.ytDlpPath()).isEqualTo("/usr/local/bin/yt-dlp");
        assertThat(config.ytDlpCookiesFile()).contains(Path.of("/app/secrets/cookies.txt"));
        assertThat(config.ytDlpAutoUpdate()).isTrue();
        assertThat(config.replyWithErrors()).isFalse();
        assertThat(config.vpnEnabled()).isTrue();
        assertThat(config.vpnSsUrl()).isEqualTo(Optional.of("ss://secret@host:1234"));
    }

    @Test
    void blankValuesFallBackToDefaults() {
        var config = BotConfig.fromEnv(env("MAX_DURATION_SEC", "  ", "YTDLP_COOKIES_FILE", ""));

        assertThat(config.maxDurationSec()).isEqualTo(180);
        assertThat(config.ytDlpCookiesFile()).isEmpty();
    }

    @Test
    void requiresBotToken() {
        assertThatThrownBy(() -> BotConfig.fromEnv(Map.of()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("BOT_TOKEN");
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThatThrownBy(() -> BotConfig.fromEnv(env("WORKER_THREADS", "two")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("WORKER_THREADS");
        assertThatThrownBy(() -> BotConfig.fromEnv(env("WORKER_THREADS", "0")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> BotConfig.fromEnv(env("MAX_FILE_MB", "51")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("MAX_FILE_MB");
    }

    @Test
    void rejectsInvalidBooleansAndChatIds() {
        assertThatThrownBy(() -> BotConfig.fromEnv(env("VPN_ENABLED", "maybe")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("VPN_ENABLED");
        assertThatThrownBy(() -> BotConfig.fromEnv(env("ALLOWED_CHAT_IDS", "123,abc")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("abc");
    }

    @Test
    void vpnRequiresUrl() {
        assertThatThrownBy(() -> BotConfig.fromEnv(env("VPN_ENABLED", "true")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("VPN_SS_URL");
    }

    @Test
    void allowsEveryChatWhenListIsEmpty() {
        assertThat(BotConfig.fromEnv(env()).isChatAllowed(-100L)).isTrue();

        var restricted = BotConfig.fromEnv(env("ALLOWED_CHAT_IDS", "-100"));
        assertThat(restricted.isChatAllowed(-100L)).isTrue();
        assertThat(restricted.isChatAllowed(-200L)).isFalse();
    }

    @Test
    void toStringHidesSecrets() {
        var config = BotConfig.fromEnv(env("VPN_ENABLED", "true", "VPN_SS_URL", "ss://topsecret@host:1"));

        assertThat(config.toString()).doesNotContain("123:abc").doesNotContain("topsecret");
    }
}
