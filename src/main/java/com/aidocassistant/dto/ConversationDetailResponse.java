package com.aidocassistant.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detailed conversation DTO with linked document summaries and full chronological message history.
 */
public record ConversationDetailResponse(
        UUID id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<DocumentResponse> documents,
        List<MessageResponse> messages
) {
}
