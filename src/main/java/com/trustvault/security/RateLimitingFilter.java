package com.trustvault.security;

import com.trustvault.service.AuditLogService;
import com.trustvault.util.AuditEvent;
import com.trustvault.util.RequestInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Filter that enforces rate limits on authentication endpoints to prevent
 * brute-force, credential stuffing, and user enumeration attacks.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final int MAX_LOGIN_ATTEMPTS = 15;
    private static final int MAX_FORGOT_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter rateLimiter;
    private final AuditLogService auditLogService;

    public RateLimitingFilter(RateLimiter rateLimiter, AuditLogService auditLogService) {
        this.rateLimiter = rateLimiter;
        this.auditLogService = auditLogService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method)) {
            String clientIp = RequestInfo.clientIp();

            if ("/api/auth/login".equals(path)) {
                String key = "rate:login:" + clientIp;
                if (!rateLimiter.tryAcquire(key, MAX_LOGIN_ATTEMPTS, WINDOW)) {
                    rejectRateLimited(response, clientIp, "LOGIN", "Rate limit exceeded on login endpoint");
                    return;
                }
            } else if ("/api/auth/forgot-password".equals(path)) {
                String key = "rate:forgot:" + clientIp;
                if (!rateLimiter.tryAcquire(key, MAX_FORGOT_ATTEMPTS, WINDOW)) {
                    rejectRateLimited(response, clientIp, "PASSWORD_RESET_REQUESTED", "Rate limit exceeded on password reset endpoint");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void rejectRateLimited(HttpServletResponse response, String clientIp, String action, String message) throws IOException {
        auditLogService.denied(AuditEvent.byEmail(action, null, "IP: " + clientIp).details(message));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.getWriter().write("""
                {"timestamp":"%s","status":429,"error":"Too Many Requests","message":"Rate limit exceeded. Please try again later."}
                """.formatted(java.time.Instant.now()));
    }
}

