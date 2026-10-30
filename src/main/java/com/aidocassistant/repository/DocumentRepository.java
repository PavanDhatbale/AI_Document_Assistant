package com.aidocassistant.repository;

import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for Document entity operations.
 * Enforces user ownership in all derived queries.
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Document> findByIdAndUserId(UUID id, UUID userId);

    List<Document> findAllByIdInAndUserId(Collection<UUID> ids, UUID userId);

    List<Document> findAllByIdInAndUserIdAndStatus(Collection<UUID> ids, UUID userId, DocumentStatus status);

    long countByUserId(UUID userId);

    long countByUserIdAndStatus(UUID userId, DocumentStatus status);

    Optional<Document> findByUserIdAndContentHash(UUID userId, String contentHash);

    /**
     * Atomically claims a document for processing directly in the database.
     * Prevents race conditions across multiple application instances without distributed locks.
     * Permits claiming documents in allowedStatuses (PENDING, FAILED, READY)
     * OR documents stuck in PROCESSING status whose updatedAt is older than the staleThreshold (crash recovery).
     *
     * @return 1 if successfully claimed, 0 if already claimed by another instance or invalid state
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        UPDATE Document d
        SET d.status = :newStatus, d.errorMessage = NULL
        WHERE d.id = :id
          AND d.user.id = :userId
          AND (d.status IN :allowedStatuses OR (d.status = :processingStatus AND d.updatedAt < :staleThreshold))
    """)
    int claimDocumentForProcessing(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("newStatus") DocumentStatus newStatus,
            @org.springframework.data.repository.query.Param("allowedStatuses") Collection<DocumentStatus> allowedStatuses,
            @org.springframework.data.repository.query.Param("processingStatus") DocumentStatus processingStatus,
            @org.springframework.data.repository.query.Param("staleThreshold") java.time.Instant staleThreshold
    );

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE Document d SET d.updatedAt = :staleTime WHERE d.id = :id")
    int updateUpdatedAt(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("staleTime") java.time.Instant staleTime
    );
}
