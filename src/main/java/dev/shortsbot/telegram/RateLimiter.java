package dev.shortsbot.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Sliding-window limit of events per key (here: links per chat). A limit of 0 disables it. */
public class RateLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> events = new HashMap<>();

    public RateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    /** Records an event for {@code key} and returns {@code true}, or returns {@code false} if the limit is reached. */
    public synchronized boolean tryAcquire(long key) {
        if (limit <= 0) {
            return true;
        }
        Instant now = clock.instant();
        Instant windowStart = now.minus(window);
        Deque<Instant> recent = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(windowStart)) {
            recent.pollFirst();
        }
        if (recent.size() >= limit) {
            return false;
        }
        recent.addLast(now);
        return true;
    }
}
