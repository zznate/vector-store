package io.github.zznate.vectorstore.api.dto;

import java.util.Map;

public record VectorResponse(String id, float[] vector, Map<String, String> attributes) {}
