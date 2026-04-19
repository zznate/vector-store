package io.github.zznate.vectorstore.api.dto;

/**
 * Response body for {@code GET /v1/indexes/{.../}/vectors/{id}}. Phase 2
 * only returns whether the ID is visible in the current active manifest
 * (i.e. present in at least one segment's ordinal map and not
 * tombstoned). Attribute retrieval lands with the metadata module in
 * phase 4; vector value retrieval remains out of scope for the service.
 */
public record VectorLookupResponse(String id, boolean found) {}
