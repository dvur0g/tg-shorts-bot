package dev.shortsbot.health;

import dev.shortsbot.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.longpolling.interfaces.BackOff;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatBackOffTest {

    @TempDir
    Path tmp;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final CountingBackOff delegate = new CountingBackOff();

    @Test
    void writesHeartbeatOnResetAtMostEveryTenSeconds() {
        Path file = tmp.resolve("heartbeat");
        var backOff = new HeartbeatBackOff(delegate, file, clock);

        backOff.reset();
        assertThat(file).hasContent("2026-01-01T00:00:00Z");

        clock.advance(Duration.ofSeconds(5));
        backOff.reset();
        assertThat(file).hasContent("2026-01-01T00:00:00Z");

        clock.advance(Duration.ofSeconds(5));
        backOff.reset();
        assertThat(file).hasContent("2026-01-01T00:00:10Z");
        assertThat(delegate.resets).isEqualTo(3);
    }

    @Test
    void delegatesBackOffAndSurvivesUnwritableFile() {
        var backOff = new HeartbeatBackOff(delegate, tmp.resolve("missing-dir").resolve("heartbeat"), clock);

        backOff.reset();

        assertThat(backOff.nextBackOffMillis()).isEqualTo(500);
        assertThat(delegate.resets).isEqualTo(1);
    }

    private static class CountingBackOff implements BackOff {
        int resets;

        @Override
        public void reset() {
            resets++;
        }

        @Override
        public long nextBackOffMillis() {
            return 500;
        }
    }
}
