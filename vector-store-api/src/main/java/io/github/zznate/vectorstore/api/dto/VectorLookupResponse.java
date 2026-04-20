package io.github.zznate.vectorstore.api.dto;

import java.util.Map;

/**
 * Response body for {@code GET /v1/indexes/{.../}/vectors/{id}}. When the
 * ID is present in the active manifest and not tombstoned, {@code found}
 * is {@code true} and {@code attributes} carries the stored sidecar
 * attributes for that vector. When absent (never indexed or tombstoned),
 * {@code found} is {@code false} and {@code attributes} is an empty map.
 */
public record VectorLookupResponse(String id, boolean found, Map<String, String> attributes) {

  public VectorLookupResponse {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
