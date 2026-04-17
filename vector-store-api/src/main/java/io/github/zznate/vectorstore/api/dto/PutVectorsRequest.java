package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PutVectorsRequest(
    @NotNull @Size(min = 1, max = 10_000) @Valid List<VectorInput> vectors) {}
