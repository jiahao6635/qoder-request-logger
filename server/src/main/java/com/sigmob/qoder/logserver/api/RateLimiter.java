package com.sigmob.qoder.logserver.api;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sigmob.qoder.logserver.config.ServerProperties;

/**
 * Fixed-window rate limiter, applied per client IP. With the shared API key
 * the key no longer identifies a person, so the window bucket is the remote
 * address of the request.
 *
 * <p>Simple one-second windows: within the current second each address may
 * issue at most {@code limitPerSecond} requests; the window resets on the next
 * second boundary. Well within the fire-and-forget tolerance of the hook
 * client, whose outbox absorbs the occasional 429.</p>
 */
@Component
public class RateLimiter {

    private final int limitPerSecond;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        final long second;
        final AtomicInteger used = new AtomicInteger();

        Window(long second) {
            this.second = second;
        }
    }

    @Autowired
    public RateLimiter(ServerProperties properties) {
        this(properties.getRateLimitPerIp(), Clock.systemUTC());
    }

    public RateLimiter(int limitPerSecond, Clock clock) {
        if (limitPerSecond <= 0) {
            throw new IllegalArgumentException("limitPerSecond must be positive");
        }
        this.limitPerSecond = limitPerSecond;
        this.clock = clock;
    }

    /** Attempts to admit one request for the given address; {@code false} means over-limit. */
    public boolean tryAcquire(String key) {
        long second = clock.instant().getEpochSecond();
        Window window = windows.compute(key, (k, existing) ->
                existing != null && existing.second == second ? existing : new Window(second));
        return window.used.incrementAndGet() <= limitPerSecond;
    }

    /** Number of tracked addresses; exposed for tests/diagnostics. */
    public int trackedKeys() {
        return windows.size();
    }
}
