package dev.shortsbot.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable application configuration, read from environment variables.
 * See {@code .env.example} for the meaning of every variable.
 */
public record BotConfig(
        String botToken,
        Set<Long> allowedChatIds,
        int maxDurationSec,
        int maxFileMb,
        Duration downloadTimeout,
        int workerThreads,
        int maxQueuedLinks,
        int rateLimitPerMinute,
        Path downloadDir,
        String ytDlpPath,
        Optional<Path> ytDlpCookiesFile,
        boolean ytDlpAutoUpdate,
        boolean replyWithErrors,
        boolean vpnEnabled,
        Optional<String> vpnSsUrl
) {

    public static BotConfig fromEnv() {
        return fromEnv(System.getenv());
    }

    public static BotConfig fromEnv(Map<String, String> env) {
        var reader = new EnvReader(env);
        var config = new BotConfig(
                reader.required("BOT_TOKEN"),
                reader.chatIds("ALLOWED_CHAT_IDS"),
                reader.positiveInt("MAX_DURATION_SEC", 180),
                reader.intInRange("MAX_FILE_MB", 49, 1, 50),
                Duration.ofSeconds(reader.positiveInt("DOWNLOAD_TIMEOUT_SEC", 120)),
                reader.positiveInt("WORKER_THREADS", 2),
                reader.positiveInt("MAX_QUEUED_LINKS", 20),
                reader.intInRange("RATE_LIMIT_PER_MINUTE", 10, 0, Integer.MAX_VALUE),
                Path.of(reader.string("DOWNLOAD_DIR", "/tmp/shortsbot")),
                reader.string("YTDLP_PATH", "yt-dlp"),
                reader.optional("YTDLP_COOKIES_FILE").map(Path::of),
                reader.bool("YTDLP_AUTO_UPDATE", false),
                reader.bool("REPLY_WITH_ERRORS", true),
                reader.bool("VPN_ENABLED", false),
                reader.optional("VPN_SS_URL")
        );
        if (config.vpnEnabled() && config.vpnSsUrl().isEmpty()) {
            throw new ConfigException("VPN_ENABLED=true requires VPN_SS_URL to be set");
        }
        return config;
    }

    public boolean isChatAllowed(long chatId) {
        return allowedChatIds.isEmpty() || allowedChatIds.contains(chatId);
    }

    /** Keeps secrets (bot token, VPN key) out of logs. */
    @Override
    public String toString() {
        return "BotConfig[allowedChatIds=" + (allowedChatIds.isEmpty() ? "<all>" : allowedChatIds)
                + ", maxDurationSec=" + maxDurationSec
                + ", maxFileMb=" + maxFileMb
                + ", downloadTimeout=" + downloadTimeout
                + ", workerThreads=" + workerThreads
                + ", maxQueuedLinks=" + maxQueuedLinks
                + ", rateLimitPerMinute=" + (rateLimitPerMinute == 0 ? "<off>" : rateLimitPerMinute)
                + ", downloadDir=" + downloadDir
                + ", ytDlpPath=" + ytDlpPath
                + ", ytDlpCookiesFile=" + ytDlpCookiesFile.map(Path::toString).orElse("<none>")
                + ", ytDlpAutoUpdate=" + ytDlpAutoUpdate
                + ", replyWithErrors=" + replyWithErrors
                + ", vpnEnabled=" + vpnEnabled
                + "]";
    }

    private record EnvReader(Map<String, String> env) {

        Optional<String> optional(String name) {
            return Optional.ofNullable(env.get(name)).map(String::strip).filter(v -> !v.isEmpty());
        }

        String required(String name) {
            return optional(name).orElseThrow(() -> new ConfigException(name + " is required but not set"));
        }

        String string(String name, String defaultValue) {
            return optional(name).orElse(defaultValue);
        }

        int positiveInt(String name, int defaultValue) {
            return intInRange(name, defaultValue, 1, Integer.MAX_VALUE);
        }

        int intInRange(String name, int defaultValue, int min, int max) {
            var raw = optional(name);
            if (raw.isEmpty()) {
                return defaultValue;
            }
            int value;
            try {
                value = Integer.parseInt(raw.get());
            } catch (NumberFormatException e) {
                throw new ConfigException(name + " must be an integer, got '" + raw.get() + "'");
            }
            if (value < min || value > max) {
                throw new ConfigException(name + " must be between " + min + " and " + max + ", got " + value);
            }
            return value;
        }

        boolean bool(String name, boolean defaultValue) {
            return optional(name)
                    .map(v -> switch (v.toLowerCase()) {
                        case "true", "1", "yes", "on" -> true;
                        case "false", "0", "no", "off" -> false;
                        default -> throw new ConfigException(name + " must be true or false, got '" + v + "'");
                    })
                    .orElse(defaultValue);
        }

        Set<Long> chatIds(String name) {
            return optional(name)
                    .map(v -> Arrays.stream(v.split(","))
                            .map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .map(s -> parseChatId(name, s))
                            .collect(Collectors.toUnmodifiableSet()))
                    .orElse(Set.of());
        }

        private static long parseChatId(String name, String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ConfigException(name + " contains an invalid chat id '" + value + "'");
            }
        }
    }
}
