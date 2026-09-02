package com.sigmob.qoder.logserver.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** Fixed-window limiter logic, driven by a mutable clock. */
class RateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void overLimitRequestsAreRefusedWithinSameSecond() {
        MutableClock clock = new MutableClock(T0);
        RateLimiter limiter = new RateLimiter(3, clock);
        assertThat(limiter.tryAcquire("user-a")).isTrue();
        assertThat(limiter.tryAcquire("user-a")).isTrue();
        assertThat(limiter.tryAcquire("user-a")).isTrue();
        assertThat(limiter.tryAcquire("user-a")).isFalse();
        assertThat(limiter.tryAcquire("user-a")).isFalse();
    }

    @Test
    void windowResetsOnNextSecond() {
        MutableClock clock = new MutableClock(T0);
        RateLimiter limiter = new RateLimiter(2, clock);
        assertThat(limiter.tryAcquire("user-a")).isTrue();
        assertThat(limiter.tryAcquire("user-a")).isTrue();
        assertThat(limiter.tryAcquire("user-a")).isFalse();

        clock.advance(Duration.ofMillis(1000));
        assertThat(limiter.tryAcquire("user-a")).isTrue();
    }

    @Test
    void limitsArePerKey() {
        MutableClock clock = new MutableClock(T0);
        RateLimiter limiter = new RateLimiter(1, clock);
        assertThat(limiter.tryAcquire("user-a")).isTrue();
        assertThat(limiter.tryAcquire("user-a")).isFalse();
        assertThat(limiter.tryAcquire("user-b")).isTrue(); // independent window
    }
}
