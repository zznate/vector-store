package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DeleteVectorsRequest(
    @NotNull @Size(min = 1, max = 10_000) List<String> ids) {}
