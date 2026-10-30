package com.aidocassistant.dto;

import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public document metadata response DTO.
 * Excludes sensitive server storage filenames and physical filesystem paths.
 */
public record DocumentResponse(
        UUID id,
        String originalFileName,
        long fileSizeBytes,
        Integer totalPages,
        Integer totalChunks,
        DocumentStatus status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {

    public static DocumentResponse fromEntity(Document doc) {
        return new DocumentResponse(
                doc.getId(),
                doc.getOriginalFileName(),
                doc.getFileSizeBytes(),
                doc.getTotalPages(),
                doc.getTotalChunks(),
                doc.getStatus(),
                doc.getErrorMessage(),
                doc.getCreatedAt(),
                doc.getUpdatedAt()
        );
    }
}
