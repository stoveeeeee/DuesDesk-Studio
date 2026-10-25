package com.fraternity.dues;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RequestRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;

    private final ConcurrentHashMap<String, RequestWindow> windows = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    @Value("${app.rate-limit.requests-per-minute:120}")
    private int requestsPerMinute;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("/health".equals(uri) || uri.startsWith("/vendor/") || isAllowed(request.getRemoteAddr())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("<!doctype html><title>Too many requests</title>"
                + "<main><h1>Request limit reached</h1><p>Please wait a minute and try again.</p>"
                + "<a href=\"/\">Return to DuesDesk</a></main>");
    }

    boolean isAllowed(String clientKey) {
        long now = clock.millis();
        if (windows.size() > 1024) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().windowStart >= WINDOW_MILLIS);
        }
        RequestWindow window = windows.compute(clientKey == null ? "unknown" : clientKey, (key, current) -> {
            if (current == null || now - current.windowStart >= WINDOW_MILLIS) {
                return new RequestWindow(now, 1);
            }
            current.count++;
            return current;
        });
        return window.count <= Math.max(1, requestsPerMinute);
    }

    private static final class RequestWindow {
        private final long windowStart;
        private int count;

        private RequestWindow(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
