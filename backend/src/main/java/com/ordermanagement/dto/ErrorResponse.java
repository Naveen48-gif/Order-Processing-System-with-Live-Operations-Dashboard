package com.ordermanagement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The single error shape every failing endpoint returns (frozen in AGENT.md §6), so a client never has
 * to guess how a failure is reported.
 *
 * <p>{@code error} is a stable machine-readable code - dashboards and tests branch on it - while
 * {@code message} is for humans. {@code details} is present only for validation failures.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details
) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse validation(int status, String message, String path, List<String> details) {
        return new ErrorResponse(Instant.now(), status, "VALIDATION_ERROR", message, path, details);
    }
}
