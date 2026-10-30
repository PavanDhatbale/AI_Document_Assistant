package com.aidocassistant.dto;

import com.aidocassistant.model.DocumentChunk;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO exposing chunk metadata, page number, and extracted text for citations and inspection.
 */
public record DocumentChunkResponse(
        UUID id,
        int chunkIndex,
        int pageNumber,
        String chunkText,
        int tokenCount,
        Instant createdAt
) {
    public static DocumentChunkResponse fromEntity(DocumentChunk chunk) {
        return new DocumentChunkResponse(
                chunk.getId(),
                chunk.getChunkIndex(),
                chunk.getPageNumber(),
                chunk.getChunkText(),
                chunk.getTokenCount(),
                chunk.getCreatedAt()
        );
    }
}
