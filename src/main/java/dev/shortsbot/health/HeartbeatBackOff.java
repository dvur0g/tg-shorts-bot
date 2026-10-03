package dev.shortsbot.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.interfaces.BackOff;
import org.telegram.telegrambots.longpolling.util.ExponentialBackOff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The long-polling session resets its back-off after every successful getUpdates call, which makes that reset a
 * reliable "Telegram is reachable and polling works" signal. This back-off writes a heartbeat file at that moment;
 * the Docker HEALTHCHECK checks that the file is recent. Idle polls return every 50 s, so a healthy bot touches it
 * at least that often.
 */
public class HeartbeatBackOff implements BackOff {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatBackOff.class);
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(10);

    private final BackOff delegate;
    private final Path file;
    private final Clock clock;
    private Instant lastWrite = Instant.MIN;
    private boolean warned;

    public HeartbeatBackOff(Path file) {
        this(new ExponentialBackOff(), file, Clock.systemUTC());
    }

    HeartbeatBackOff(BackOff delegate, Path file, Clock clock) {
        this.delegate = delegate;
        this.file = file;
        this.clock = clock;
    }

    @Override
    public synchronized void reset() {
        delegate.reset();
        Instant now = clock.instant();
        if (Duration.between(lastWrite, now).compareTo(MIN_INTERVAL) < 0) {
            return;
        }
        try {
            Files.writeString(file, now.toString());
            lastWrite = now;
        } catch (IOException e) {
            if (!warned) {
                log.warn("Can't write heartbeat file {}; the Docker health check will report unhealthy: {}", file, e.getMessage());
                warned = true;
            }
        }
    }

    @Override
    public long nextBackOffMillis() {
        return delegate.nextBackOffMillis();
    }
}
