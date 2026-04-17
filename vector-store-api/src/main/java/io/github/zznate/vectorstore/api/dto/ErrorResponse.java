package io.github.zznate.vectorstore.api.dto;

/**
 * Structured error envelope returned for every non-2xx response.
 *
 * <p>{@code error} is a stable, snake_case code the caller can switch on;
 * {@code message} is a human-readable description.
 */
public record ErrorResponse(String error, String message) {}
