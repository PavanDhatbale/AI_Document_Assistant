package com.aidocassistant.repository;

import com.aidocassistant.model.ConversationDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for ConversationDocument association.
 */
@Repository
public interface ConversationDocumentRepository extends JpaRepository<ConversationDocument, UUID> {

    List<ConversationDocument> findByConversationId(UUID conversationId);

    Optional<ConversationDocument> findByConversationIdAndDocumentId(UUID conversationId, UUID documentId);

    boolean existsByConversationIdAndDocumentId(UUID conversationId, UUID documentId);

    void deleteByConversationIdAndDocumentId(UUID conversationId, UUID documentId);

    void deleteByConversationId(UUID conversationId);

    long countByConversationId(UUID conversationId);
}
