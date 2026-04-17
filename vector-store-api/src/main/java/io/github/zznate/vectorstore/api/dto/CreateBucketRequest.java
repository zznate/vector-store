package io.github.zznate.vectorstore.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBucketRequest(
    @NotBlank
        @Size(min = 1, max = 128)
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,126}[a-z0-9]|[a-z0-9]")
        String bucketId,
    @NotBlank @Size(min = 1, max = 255) String displayName) {}
