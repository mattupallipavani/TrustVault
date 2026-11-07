package com.trustvault.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Captures who/where from the current request for audit records.
 *
 * Reads the container-resolved remote address only. The X-Forwarded-For header
 * is deliberately ignored: it is attacker controlled unless a trusted proxy is
 * configured, and forging it would let someone poison the audit trail. When the
 * app runs behind a proxy, configure
 * {@code server.forward-headers-strategy=native} so the container sets the
 * address from a trusted hop instead.
 */
public final class RequestInfo {

    private static final int MAX_USER_AGENT = 255;
    private static final int MAX_IP = 64;

    private RequestInfo() {
    }

    public static String clientIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "unknown";
        }
        return truncate(request.getRemoteAddr(), MAX_IP);
    }

    public static String userAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        return truncate(request.getHeader("User-Agent"), MAX_USER_AGENT);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes()
                instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
