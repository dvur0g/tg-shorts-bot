package dev.shortsbot.telegram;

import dev.shortsbot.config.BotConfig;
import dev.shortsbot.download.DownloadException.Reason;
import dev.shortsbot.link.DetectedLink;

/** Short, human-friendly replies for videos the bot couldn't deliver. */
final class FailureMessages {

    private FailureMessages() {
    }

    static String forReason(Reason reason, DetectedLink link, BotConfig config) {
        return switch (reason) {
            case TOO_LONG -> "⏱ Too long: I only fetch videos up to " + formatDuration(config.maxDurationSec()) + ".";
            case TOO_LARGE -> "📦 Too big: I can only upload videos up to " + config.maxFileMb() + " MB.";
            case NOT_A_VIDEO -> "🤷 Found nothing I can send in that post.";
            case LOGIN_REQUIRED -> "🔒 " + link.platform().displayName() + " wants a login for this one, so I can't fetch it.";
            case UNAVAILABLE -> "🚫 That video is unavailable (private, deleted or region-locked).";
            case TIMEOUT -> "⌛ The download took too long, gave up.";
            case UNKNOWN -> "😕 Couldn't download that one.";
        };
    }

    static String uploadFailed() {
        return "😕 Downloaded the video but Telegram didn't accept it.";
    }

    static String formatDuration(int seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        int minutes = seconds / 60;
        int rest = seconds % 60;
        return rest == 0 ? minutes + " min" : minutes + " min " + rest + " s";
    }
}
