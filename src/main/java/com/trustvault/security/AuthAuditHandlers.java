package com.trustvault.security;

import com.trustvault.entity.User;
import com.trustvault.repository.UserRepository;
import com.trustvault.service.AuditLogService;
import com.trustvault.util.AuditEvent;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * JSON authentication endpoints plus audit hooks for every outcome.
 *
 * Sign-in failures are recorded with the attempted address, including
 * addresses that do not exist, so a password spraying run is visible in the
 * admin view. The response to the client stays generic either way, so the
 * caller cannot use the login form to discover which accounts exist.
 */
@Component
public class AuthAuditHandlers {

    private static final Logger log =
            LoggerFactory.getLogger(AuthAuditHandlers.class);

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AuthAuditHandlers(UserRepository userRepository,
                             AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    public AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> {

            String email = authentication.getName();
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);

            safeAudit(() -> auditLogService.standalone(
                    AuditEvent.byEmail("LOGIN",
                                    user == null ? null : user.getId(), email)
                            .details("Sign-in succeeded"),
                    "SUCCESS"));

            String role = user == null ? "" : user.getRole().name();

            writeJson(response, HttpServletResponse.SC_OK,
                    "{\"message\":\"Signed in\","
                    + "\"email\":\"" + escape(email) + "\","
                    + "\"role\":\"" + escape(role) + "\"}");
        };
    }

    public AuthenticationFailureHandler failureHandler() {
        return (request, response, exception) -> {

            String attempted = request.getParameter("username");

            safeAudit(() -> auditLogService.standalone(
                    AuditEvent.byEmail("LOGIN", null, attempted)
                            .details("Sign-in failed: " + exception.getClass().getSimpleName()),
                    "DENIED"));

            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "{\"message\":\"Invalid email or password\"}");
        };
    }

    public LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) -> {

            if (authentication != null) {
                String email = authentication.getName();
                User user = userRepository.findByEmailIgnoreCase(email).orElse(null);

                safeAudit(() -> auditLogService.standalone(
                        AuditEvent.byEmail("LOGOUT",
                                        user == null ? null : user.getId(), email)
                                .details("Signed out"),
                        "SUCCESS"));
            }

            writeJson(response, HttpServletResponse.SC_OK,
                    "{\"message\":\"Signed out\"}");
        };
    }

    /**
     * Reached for role failures and for CSRF rejections. The refused request
     * is recorded with its method and path so the trail shows what was
     * attempted, not merely that something was refused.
     */
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {

            Authentication authentication =
                    SecurityContextHolder.getContext().getAuthentication();

            String email = authentication != null
                    && authentication.isAuthenticated()
                    && !"anonymousUser".equals(authentication.getPrincipal())
                    ? authentication.getName()
                    : null;

            safeAudit(() -> auditLogService.denied(
                    AuditEvent.byEmail("ACCESS_REFUSED", null, email)
                            .details(describe(request)
                                    + ":" + accessDeniedException.getClass().getSimpleName())));

            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    "{\"message\":\"You are not allowed to perform this action\"}");
        };
    }

    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    private String describe(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    /**
     * Audit failures must never break the response path. If the audit table is
     * unavailable the request still completes, and the failure is logged
     * loudly so the operator knows the trail has a hole.
     */
    private void safeAudit(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.error("Failed to write audit record", exception);
        }
    }

    private void writeJson(HttpServletResponse response, int status, String body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(body);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
