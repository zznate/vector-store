package io.github.zznate.vectorstore.api.dto;

/**
 * Response body for a successful {@code vectors:put}. Returned with HTTP
 * 202 — the request has been accepted into the write buffer but is not
 * yet visible to queries; a subsequent {@code :commit} is required.
 */
public record PutVectorsResponse(long accepted, int bufferSize) {}
