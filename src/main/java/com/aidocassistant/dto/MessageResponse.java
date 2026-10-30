package com.aidocassistant.dto;

import com.aidocassistant.model.Message;
import com.aidocassistant.model.MessageRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public response DTO for a chat message including associated grounding citations.
 */
public record MessageResponse(
        UUID id,
        MessageRole role,
        String content,
        int tokenCount,
        Instant createdAt,
        List<CitationResponse> citations
) {
    public static MessageResponse fromEntity(Message message, List<CitationResponse> citations) {
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getTokenCount(),
                message.getCreatedAt(),
                citations != null ? citations : List.of()
        );
    }
}
