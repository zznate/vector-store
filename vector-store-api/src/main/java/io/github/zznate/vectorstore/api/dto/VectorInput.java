package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record VectorInput(
    @NotBlank @Size(min = 1, max = 512) String id,
    @NotNull @Size(min = 1) float[] vector,
    Map<String, String> attributes) {}
