package com.aidocassistant.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Request payload for bulk document deletion.
 */
public record BulkDeleteRequest(
        @NotEmpty(message = "Document IDs list cannot be empty")
        List<UUID> documentIds
) {}
