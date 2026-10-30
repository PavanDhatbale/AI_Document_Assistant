package com.aidocassistant.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Request payload for associating documents with a conversation.
 */
public record AddDocumentsRequest(
        @NotEmpty(message = "Document IDs list cannot be empty")
        List<UUID> documentIds
) {
}
