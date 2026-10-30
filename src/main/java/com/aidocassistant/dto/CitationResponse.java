package com.aidocassistant.dto;

import com.aidocassistant.model.MessageSource;

import java.time.Instant;
import java.util.UUID;

/**
 * Citation metadata representing evidence retrieved from documents supporting an assistant answer.
 */
public record CitationResponse(
        UUID id,
        UUID documentId,
        String documentName,
        int pageNumber,
        int chunkIndex,
        float relevanceScore,
        String excerptText,
        Instant createdAt
) {
    public static CitationResponse fromEntity(MessageSource source) {
        return new CitationResponse(
                source.getId(),
                source.getDocument().getId(),
                source.getDocument().getOriginalFileName(),
                source.getPageNumber(),
                source.getChunkIndex(),
                source.getRelevanceScore(),
                source.getExcerptText(),
                source.getCreatedAt()
        );
    }
}
