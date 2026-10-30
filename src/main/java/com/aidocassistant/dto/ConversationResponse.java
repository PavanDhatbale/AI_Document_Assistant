package com.aidocassistant.dto;

import com.aidocassistant.model.Conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Summary DTO for conversation listing.
 */
public record ConversationResponse(
        UUID id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        int documentCount,
        int messageCount,
        List<UUID> documentIds
) {
    public static ConversationResponse fromEntity(Conversation conv, int docCount, int msgCount, List<UUID> docIds) {
        return new ConversationResponse(
                conv.getId(),
                conv.getTitle(),
                conv.getCreatedAt(),
                conv.getUpdatedAt(),
                docCount,
                msgCount,
                docIds != null ? docIds : List.of()
        );
    }
}
