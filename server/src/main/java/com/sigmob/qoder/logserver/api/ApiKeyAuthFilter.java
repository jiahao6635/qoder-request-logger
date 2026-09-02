package com.sigmob.qoder.logserver.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sigmob.qoder.logserver.auth.ApiKeyRegistry;
import com.sigmob.qoder.logserver.config.ShutdownCoordinator;
import com.sigmob.qoder.logserver.ingest.DiskMonitor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authentication + backpressure gate for the two ingest endpoints. Only
 * {@code /api/logs} and {@code /api/logs/batch} are intercepted; everything
 * else (notably {@code /api/health}) passes through untouched.
 *
 * <p>The deployment uses a single shared API key: it only authenticates the
 * request (proves the sender installed the plugin), records are attributed by
 * their own top-level {@code email} (see {@link com.sigmob.qoder.logserver.ingest.RecordNormalizer
 * RecordNormalizer}). Order of checks: shutdown (503) -> disk watermark (503)
 * -> API key (401) -> per-client-IP rate limit (429).</p>
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final ApiKeyRegistry registry;
    private final RateLimiter rateLimiter;
    private final DiskMonitor diskMonitor;
    private final ShutdownCoordinator shutdown;

    public ApiKeyAuthFilter(ApiKeyRegistry registry, RateLimiter rateLimiter,
                            DiskMonitor diskMonitor, ShutdownCoordinator shutdown) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.diskMonitor = diskMonitor;
        this.shutdown = shutdown;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/api/logs".equals(path) || "/api/logs/batch".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        diskMonitor.warnIfNeeded();
        if (shutdown.isStopping()) {
            writeError(response, 503, "shutting_down", 5);
            return;
        }
        if (diskMonitor.isOverloaded()) {
            writeError(response, 503, "spool_overloaded", 30);
            return;
        }
        Optional<ApiKeyRegistry.KeyEntry> entry = registry.lookup(request.getHeader("X-API-Key"));
        if (entry.isEmpty() || !entry.get().enabled()) {
            writeError(response, 401, "invalid_api_key", null);
            return;
        }
        // Shared-key deployment: the key no longer identifies a person, so the
        // fixed-window limiter runs per client IP (direct connections on the
        // internal network; behind a reverse proxy, the proxy addresses).
        if (!rateLimiter.tryAcquire(request.getRemoteAddr())) {
            writeError(response, 429, "rate_limited", 1);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, int status, String error,
                                   Integer retryAfterSeconds) throws IOException {
        if (retryAfterSeconds != null) {
            response.setIntHeader(RETRY_AFTER_HEADER, retryAfterSeconds);
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
