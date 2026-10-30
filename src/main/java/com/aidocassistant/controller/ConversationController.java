package com.aidocassistant.controller;

import com.aidocassistant.dto.AddDocumentsRequest;
import com.aidocassistant.dto.ChatAnswerResponse;
import com.aidocassistant.dto.ConversationDetailResponse;
import com.aidocassistant.dto.ConversationResponse;
import com.aidocassistant.dto.CreateConversationRequest;
import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.dto.MessageResponse;
import com.aidocassistant.dto.SendMessageRequest;
import com.aidocassistant.security.SecurityUserPrincipal;
import com.aidocassistant.service.RagChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller managing conversations, document scopes, and grounded RAG chat interactions.
 * All endpoints enforce user ownership derived strictly from the server-side SecurityUserPrincipal.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final RagChatService ragChatService;

    public ConversationController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    /**
     * Creates a new conversation for the authenticated user and optionally links READY documents.
     */
    @PostMapping
    public ResponseEntity<ConversationResponse> createConversation(
            @Valid @RequestBody(required = false) CreateConversationRequest request,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        ConversationResponse response = ragChatService.createConversation(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists all conversations owned by the authenticated user, ordered by updatedAt DESC.
     */
    @GetMapping
    public ResponseEntity<List<ConversationResponse>> listConversations(
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        List<ConversationResponse> conversations = ragChatService.listConversations(principal.getId());
        return ResponseEntity.ok(conversations);
    }

    /**
     * Retrieves conversation metadata, linked document summaries, and full message history with citations.
     * Returns 404 if not found or owned by another user.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ConversationDetailResponse> getConversation(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        ConversationDetailResponse detail = ragChatService.getConversation(principal.getId(), id);
        return ResponseEntity.ok(detail);
    }

    /**
     * Deletes a conversation owned by the authenticated user.
     * Cascades delete messages, citations, and conversation-document associations.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteConversation(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        ragChatService.deleteConversation(principal.getId(), id);
        return ResponseEntity.ok(Map.of(
                "message", "Conversation deleted successfully",
                "id", id.toString()
        ));
    }

    /**
     * Sends a message to a conversation and receives a grounded AI response with citations.
     */
    @PostMapping("/{id}/messages")
    public ResponseEntity<ChatAnswerResponse> sendMessage(
            @PathVariable("id") UUID id,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        ChatAnswerResponse answer = ragChatService.sendMessage(principal.getId(), id, request.content());
        return ResponseEntity.ok(answer);
    }

    /**
     * Retrieves all messages for an owned conversation.
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> getConversationMessages(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        List<MessageResponse> messages = ragChatService.getConversationMessages(principal.getId(), id);
        return ResponseEntity.ok(messages);
    }

    /**
     * Associates one or more READY documents with an owned conversation.
     */
    @PostMapping("/{id}/documents")
    public ResponseEntity<List<DocumentResponse>> addDocumentsToConversation(
            @PathVariable("id") UUID id,
            @Valid @RequestBody AddDocumentsRequest request,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        List<DocumentResponse> documents = ragChatService.addDocumentsToConversation(
                principal.getId(), id, request.documentIds()
        );
        return ResponseEntity.ok(documents);
    }

    /**
     * Removes an associated document from a conversation scope.
     */
    @DeleteMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Map<String, String>> removeDocumentFromConversation(
            @PathVariable("id") UUID id,
            @PathVariable("documentId") UUID documentId,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        ragChatService.removeDocumentFromConversation(principal.getId(), id, documentId);
        return ResponseEntity.ok(Map.of(
                "message", "Document removed from conversation",
                "documentId", documentId.toString()
        ));
    }

    /**
     * Retrieves all documents currently linked to an owned conversation.
     */
    @GetMapping("/{id}/documents")
    public ResponseEntity<List<DocumentResponse>> getConversationDocuments(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        List<DocumentResponse> documents = ragChatService.getConversationDocuments(principal.getId(), id);
        return ResponseEntity.ok(documents);
    }
}
