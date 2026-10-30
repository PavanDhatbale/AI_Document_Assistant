package com.aidocassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for sending a chat message to a conversation.
 */
public record SendMessageRequest(
        @NotBlank(message = "Message content must not be blank")
        @Size(max = 4000, message = "Message content cannot exceed 4000 characters")
        String content
) {
}
