package com.trustvault.dto;

import java.time.LocalDateTime;

/**
 * Search filters supplied as query parameters. Every field is optional.
 */
public record DocumentQuery(
        String keyword,
        String category,
        String owner,
        LocalDateTime from,
        LocalDateTime to,
        String scope
) {
}
