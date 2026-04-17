package io.github.zznate.vectorstore.api.dto;

import java.util.Map;

public record QueryHit(String id, float score, Map<String, String> attributes) {}
