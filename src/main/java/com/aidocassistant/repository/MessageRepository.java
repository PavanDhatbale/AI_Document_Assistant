package com.aidocassistant.repository;

import com.aidocassistant.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for Message entity operations.
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    Optional<Message> findByIdAndConversationId(UUID id, UUID conversationId);

    long countByConversationId(UUID conversationId);

    long countByConversationUserId(UUID userId);
}
