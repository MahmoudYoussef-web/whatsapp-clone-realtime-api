package com.alibou.whatsappclone.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dependency-free fixed-window rate limiter for mutating endpoints
 * (send/edit/delete/forward/upload/react/status/call-adjacent posts).
 * 60 requests/minute per authenticated user (IP fallback when anonymous).
 * Responses are RFC 7807 with HTTP 429; counters reset every window.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final int MAX_REQUESTS_PER_MINUTE = 60;
    static final long WINDOW_MILLIS = 60_000L;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String method = request.getMethod();
        if (!("POST".equals(method) || "PATCH".equals(method)
                || "PUT".equals(method) || "DELETE".equals(method))) {
            return true;
        }
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/conversations")
                && !path.startsWith("/api/v1/status")
                && !path.startsWith("/api/v1/users/me/avatar");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String key = rateLimitKey(request);
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(now));
        boolean allowed;
        synchronized (window) {
            if (now - window.start >= WINDOW_MILLIS) {
                window.start = now;
                window.count.set(0);
            }
            allowed = window.count.incrementAndGet() <= MAX_REQUESTS_PER_MINUTE;
        }
        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Too Many Requests","status":429,\
                    "detail":"Rate limit exceeded: max 60 mutating requests per minute"}""");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String rateLimitKey(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static final class Window {
        long start;
        final AtomicLong count = new AtomicLong();

        Window(long start) {
            this.start = start;
        }
    }
}
