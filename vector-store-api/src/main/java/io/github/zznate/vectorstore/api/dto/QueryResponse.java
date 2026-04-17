package io.github.zznate.vectorstore.api.dto;

import java.util.List;

public record QueryResponse(List<QueryHit> hits) {}
