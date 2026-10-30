package com.aidocassistant.dto;

import java.util.UUID;

/**
 * Data Transfer Object representing a document chunk retrieved from vector search with similarity score.
 */
public record RetrievedChunk(
        UUID documentId,
        UUID chunkId,
        int chunkIndex,
        int pageNumber,
        String text,
        double score
) {
}
