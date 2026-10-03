package dev.shortsbot.download;

/** A video could not be downloaded; {@link #reason()} says why in a way the bot can explain to users. */
public class DownloadException extends Exception {

    public enum Reason {
        TOO_LONG,
        TOO_LARGE,
        NOT_A_VIDEO,
        LOGIN_REQUIRED,
        UNAVAILABLE,
        TIMEOUT,
        UNKNOWN;

        /** Whether trying again has a realistic chance of succeeding. */
        public boolean isRetryable() {
            return this == TIMEOUT || this == UNKNOWN;
        }
    }

    private final Reason reason;

    public DownloadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DownloadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
