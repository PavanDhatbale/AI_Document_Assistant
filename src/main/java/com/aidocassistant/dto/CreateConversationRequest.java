package com.aidocassistant.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request payload for creating a new conversation.
 */
public record CreateConversationRequest(
        @Size(max = 255, message = "Conversation title cannot exceed 255 characters")
        String title,

        List<UUID> documentIds
) {
}
