package com.aidocassistant.repository;

import com.aidocassistant.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for DocumentChunk persistence and retrieval.
 */
@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(UUID documentId);

    List<DocumentChunk> findByDocumentIdAndPageNumberOrderByChunkIndexAsc(UUID documentId, int pageNumber);

    long countByDocumentId(UUID documentId);

    @Modifying
    @Query("DELETE FROM DocumentChunk dc WHERE dc.document.id = :documentId")
    void deleteByDocumentId(@Param("documentId") UUID documentId);
}
