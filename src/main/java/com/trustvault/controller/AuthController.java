package com.trustvault.controller;

import com.trustvault.dto.ForgotPasswordRequest;
import com.trustvault.dto.RegisterRequest;
import com.trustvault.entity.User;
import com.trustvault.service.UserService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * One message for every reset request. Anything that differed - a 404, a
     * different sentence, a longer delay - would turn this form into an account
     * discovery tool.
     */
    private static final String RESET_CONFIRMATION =
            "If that address belongs to an account, the request has been "
            + "recorded. An administrator will give you a temporary password.";

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Issues a CSRF token and, as a side effect, writes the XSRF-TOKEN cookie.
     * The SPA calls this before its first state-changing request.
     *
     * This is public on purpose: you need a token before you can sign in.
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName()
        );
    }

    /**
     * Public registration. The requested role is not part of the body: a
     * client that chose its own role could enrol as an administrator.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(
            @Valid @RequestBody RegisterRequest request) {

        User created = userService.registerUser(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "User registered successfully",
                        "email", created.getEmail(),
                        "role", created.getRole().name()
                ));
    }

    /**
     * Public, because the whole point is that the caller cannot sign in.
     *
     * Returns 202 whether or not the address exists. The request is audited
     * against the account when there is one, so an administrator sees who
     * needs a reset; the response never says.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {

        userService.requestPasswordReset(request.getEmail());

        return ResponseEntity.accepted()
                .body(Map.of("message", RESET_CONFIRMATION));
    }
}
