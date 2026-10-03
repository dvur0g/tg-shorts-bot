package dev.shortsbot.telegram;

import dev.shortsbot.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void allowsUpToTheLimitPerWindowAndKey() {
        var limiter = new RateLimiter(2, Duration.ofMinutes(1), clock);

        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
        assertThat(limiter.tryAcquire(2)).as("other chats have their own budget").isTrue();
    }

    @Test
    void freesBudgetAsTheWindowSlides() {
        var limiter = new RateLimiter(2, Duration.ofMinutes(1), clock);
        limiter.tryAcquire(1);
        clock.advance(Duration.ofSeconds(30));
        limiter.tryAcquire(1);

        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire(1)).as("first event left the window").isTrue();
        assertThat(limiter.tryAcquire(1)).isFalse();
    }

    @Test
    void deniedAttemptsDoNotUseBudget() {
        var limiter = new RateLimiter(1, Duration.ofMinutes(1), clock);
        limiter.tryAcquire(1);
        clock.advance(Duration.ofSeconds(59));
        limiter.tryAcquire(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire(1)).isTrue();
    }

    @Test
    void zeroDisablesTheLimit() {
        var limiter = new RateLimiter(0, Duration.ofMinutes(1), clock);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire(1)).isTrue();
        }
    }
}
