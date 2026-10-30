package com.aidocassistant.dto;

/**
 * Composite response returned after posting a message, including both the persisted user prompt
 * and the grounded assistant response with citations.
 */
public record ChatAnswerResponse(
        MessageResponse userMessage,
        MessageResponse assistantMessage
) {
}
