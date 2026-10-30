package com.aidocassistant.repository;

import com.aidocassistant.model.MessageSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for MessageSource citations.
 */
@Repository
public interface MessageSourceRepository extends JpaRepository<MessageSource, UUID> {

    List<MessageSource> findByMessageIdOrderByRelevanceScoreDesc(UUID messageId);

    List<MessageSource> findByMessageIdInOrderByRelevanceScoreDesc(Collection<UUID> messageIds);

    List<MessageSource> findByDocumentId(UUID documentId);
}
