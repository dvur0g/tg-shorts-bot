package dev.shortsbot.telegram;

import okhttp3.OkHttpClient;

import java.time.Duration;

/** HTTP client settings for calls to the Telegram Bot API. */
public final class TelegramHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * OkHttp's 10 s default is too short for video uploads: Telegram answers only after it has received and
     * processed the whole file, so a few MB on a slow uplink easily takes longer.
     */
    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(120);

    private TelegramHttp() {
    }

    /** Client for sending messages and uploading videos. */
    public static OkHttpClient newClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .writeTimeout(TRANSFER_TIMEOUT)
                .readTimeout(TRANSFER_TIMEOUT)
                .build();
    }
}
