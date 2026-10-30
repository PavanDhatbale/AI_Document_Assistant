package com.aidocassistant.service;

import com.aidocassistant.dto.AddDocumentsRequest;
import com.aidocassistant.dto.ChatAnswerResponse;
import com.aidocassistant.dto.CitationResponse;
import com.aidocassistant.dto.ConversationDetailResponse;
import com.aidocassistant.dto.ConversationResponse;
import com.aidocassistant.dto.CreateConversationRequest;
import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.dto.MessageResponse;
import com.aidocassistant.dto.RetrievedChunk;
import com.aidocassistant.dto.SendMessageRequest;
import com.aidocassistant.model.Conversation;
import com.aidocassistant.model.ConversationDocument;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.Message;
import com.aidocassistant.model.MessageRole;
import com.aidocassistant.model.MessageSource;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.ConversationDocumentRepository;
import com.aidocassistant.repository.ConversationRepository;
import com.aidocassistant.repository.DocumentRepository;
import com.aidocassistant.repository.MessageRepository;
import com.aidocassistant.repository.MessageSourceRepository;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.JwtTokenProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive automated test suite verifying Task 9:
 * Complete RAG Chat, Document Scoping, Grounding, and Citation Pipeline.
 * Covers all 24 required scenarios with mocked Gemini and Qdrant clients.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class RagChatPipelineTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RagChatService ragChatService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationDocumentRepository conversationDocumentRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageSourceRepository messageSourceRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockBean
    private ChatModel chatModel;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private QdrantClient qdrantClient;

    @MockBean
    private VectorStoreService vectorStoreService;

    private User alice;
    private String aliceToken;

    private User bob;
    private String bobToken;

    private Document aliceDoc1;
    private Document aliceDoc2;
    private Document bobDoc;

    @BeforeEach
    void setUp() {
        messageSourceRepository.deleteAll();
        messageRepository.deleteAll();
        conversationDocumentRepository.deleteAll();
        conversationRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Create Alice
        alice = new User();
        alice.setId(UUID.randomUUID());
        alice.setEmail("alice.rag@example.com");
        alice.setPasswordHash(passwordEncoder.encode("Password123!"));
        alice.setDisplayName("Alice Analyst");
        alice.setRole(UserRole.ROLE_USER);
        alice.setEnabled(true);
        alice = userRepository.save(alice);
        aliceToken = tokenProvider.generateToken(alice.getId(), alice.getEmail(), alice.getRole().name());

        // 2. Create Bob
        bob = new User();
        bob.setId(UUID.randomUUID());
        bob.setEmail("bob.rag@example.com");
        bob.setPasswordHash(passwordEncoder.encode("Password123!"));
        bob.setDisplayName("Bob Auditor");
        bob.setRole(UserRole.ROLE_USER);
        bob.setEnabled(true);
        bob = userRepository.save(bob);
        bobToken = tokenProvider.generateToken(bob.getId(), bob.getEmail(), bob.getRole().name());

        // 3. Create Alice's READY documents
        aliceDoc1 = new Document(UUID.randomUUID(), alice, "Q3_Financials.pdf", "q3-financials.pdf", "hash_q3", 1024);
        aliceDoc1.setStatus(DocumentStatus.READY);
        aliceDoc1.setTotalPages(5);
        aliceDoc1.setTotalChunks(10);
        aliceDoc1 = documentRepository.save(aliceDoc1);

        aliceDoc2 = new Document(UUID.randomUUID(), alice, "Risk_Policy.pdf", "risk-policy.pdf", "hash_risk", 2048);
        aliceDoc2.setStatus(DocumentStatus.READY);
        aliceDoc2.setTotalPages(12);
        aliceDoc2.setTotalChunks(25);
        aliceDoc2 = documentRepository.save(aliceDoc2);

        // 4. Create Bob's READY document
        bobDoc = new Document(UUID.randomUUID(), bob, "Bob_Secret.pdf", "bob-secret.pdf", "hash_bob", 4096);
        bobDoc.setStatus(DocumentStatus.READY);
        bobDoc.setTotalPages(3);
        bobDoc.setTotalChunks(6);
        bobDoc = documentRepository.save(bobDoc);

        // Default Embedding mock
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest req = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < req.getInstructions().size(); i++) {
                embeddings.add(new Embedding(new float[768], i));
            }
            return new EmbeddingResponse(embeddings);
        });

        // Default ChatModel mock
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            AssistantMessage msg = new AssistantMessage("According to [Source 1], the operating margin for Q3 reached 24.5%.");
            return new ChatResponse(List.of(new Generation(msg)));
        });
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Conversation creation for an authenticated user
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("1. Conversation creation for an authenticated user persists record and returns 201 Created")
    void test1_ConversationCreation() throws Exception {
        CreateConversationRequest request = new CreateConversationRequest("Financial Analysis", List.of(aliceDoc1.getId()));

        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("Financial Analysis"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andReturn();

        ConversationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), ConversationResponse.class);
        assertThat(conversationRepository.findById(response.id())).isPresent();
        assertThat(conversationDocumentRepository.findByConversationId(response.id())).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Listing only the current user's conversations
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("2. Listing conversations returns only the authenticated user's conversations ordered by updatedAt DESC")
    void test2_ListOnlyCurrentUserConversations() throws Exception {
        // Alice creates 2 conversations with distinct timestamps to ensure deterministic DESC order
        ragChatService.createConversation(alice.getId(), new CreateConversationRequest("Alice Chat 1", null));
        Thread.sleep(20);
        ragChatService.createConversation(alice.getId(), new CreateConversationRequest("Alice Chat 2", null));

        // Bob creates 1 conversation
        ragChatService.createConversation(bob.getId(), new CreateConversationRequest("Bob Chat", null));

        MvcResult result = mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn();

        List<ConversationResponse> aliceList = objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {});
        assertThat(aliceList).hasSize(2);
        assertThat(aliceList.stream().map(ConversationResponse::title)).containsExactly("Alice Chat 2", "Alice Chat 1");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Retrieving a conversation owned by the current user
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("3. Retrieving conversation details returns metadata, linked documents, and messages")
    void test3_RetrieveConversationDetails() throws Exception {
        ConversationResponse created = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Quarterly Review", List.of(aliceDoc1.getId()))
        );

        mockMvc.perform(get("/api/conversations/" + created.id())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id().toString()))
                .andExpect(jsonPath("$.title").value("Quarterly Review"))
                .andExpect(jsonPath("$.documents[0].originalFileName").value("Q3_Financials.pdf"))
                .andExpect(jsonPath("$.messages").isArray());
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Cross-user conversation access returns 404
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("4. Cross-user conversation access returns 404 without leaking existence")
    void test4_CrossUserAccessReturns404() throws Exception {
        ConversationResponse aliceConv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Alice Private", null)
        );

        // Bob attempts to read Alice's conversation
        mockMvc.perform(get("/api/conversations/" + aliceConv.id())
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // Bob attempts to delete Alice's conversation
        mockMvc.perform(delete("/api/conversations/" + aliceConv.id())
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        // Bob attempts to post a message to Alice's conversation
        SendMessageRequest msg = new SendMessageRequest("Attempted breach");
        mockMvc.perform(post("/api/conversations/" + aliceConv.id() + "/messages")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Blank messages are rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("5. Blank or whitespace-only messages are rejected with 400 Bad Request")
    void test5_BlankMessageRejected() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        SendMessageRequest emptyReq = new SendMessageRequest("");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyReq)))
                .andExpect(status().isBadRequest());

        SendMessageRequest whitespaceReq = new SendMessageRequest("    \n\t  ");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(whitespaceReq)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Oversized messages are rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("6. Messages exceeding maximum permitted length are rejected with 400 Bad Request")
    void test6_OversizedMessageRejected() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        String hugeText = "A".repeat(4001);
        SendMessageRequest oversizedReq = new SendMessageRequest(hugeText);

        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oversizedReq)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Scenario 7: User message persistence
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("7. Valid user message is persisted with USER role and correct token count")
    void test7_UserMessagePersistence() {
        Conversation conv = conversationRepository.save(new Conversation(UUID.randomUUID(), alice, "Test Chat"));
        Message msg = ragChatService.persistUserMessage(alice.getId(), conv.getId(), "What were the total sales?");

        assertThat(msg.getId()).isNotNull();
        assertThat(msg.getRole()).isEqualTo(MessageRole.USER);
        assertThat(msg.getContent()).isEqualTo("What were the total sales?");
        assertThat(msg.getTokenCount()).isGreaterThan(0);

        Message inDb = messageRepository.findById(msg.getId()).orElseThrow();
        assertThat(inDb.getConversation().getId()).isEqualTo(conv.getId());
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Assistant response persistence
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("8. Assistant response is persisted with ASSISTANT role and linked to conversation")
    void test8_AssistantResponsePersistence() {
        Conversation conv = conversationRepository.save(new Conversation(UUID.randomUUID(), alice, "Test Chat"));
        Message assistantMsg = ragChatService.persistAssistantMessage(conv.getId(), "Total sales were $15M.", List.of());

        assertThat(assistantMsg.getId()).isNotNull();
        assertThat(assistantMsg.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistantMsg.getContent()).isEqualTo("Total sales were $15M.");

        Message inDb = messageRepository.findById(assistantMsg.getId()).orElseThrow();
        assertThat(inDb.getRole()).isEqualTo(MessageRole.ASSISTANT);
    }

    // -------------------------------------------------------------------------
    // Scenario 9: Query embeddings are requested correctly
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("9. Pipeline generates query embedding using configured embedding model")
    void test9_QueryEmbeddingRequestedCorrectly() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Scope Test", List.of(aliceDoc1.getId()))
        );

        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        SendMessageRequest msg = new SendMessageRequest("What is our revenue growth rate?");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk());

        verify(embeddingModel, atLeastOnce()).call(any(EmbeddingRequest.class));
    }

    // -------------------------------------------------------------------------
    // Scenario 10: Retrieval always uses the authenticated user's filter
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("10. Vector retrieval strictly passes authenticated userId preventing cross-tenant leakage")
    void test10_RetrievalUsesAuthenticatedUserFilter() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Filter Test", List.of(aliceDoc1.getId()))
        );

        when(vectorStoreService.searchSimilar(eq(alice.getId()), anyList(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        SendMessageRequest msg = new SendMessageRequest("Verify user isolation");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk());

        verify(vectorStoreService).searchSimilar(eq(alice.getId()), eq(List.of(aliceDoc1.getId())), any(), eq(4), eq(0.65));
    }

    // -------------------------------------------------------------------------
    // Scenario 11: Only READY documents contribute evidence
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("11. Relational defense-in-depth filters out candidate chunks if document is not READY")
    void test11_OnlyReadyDocumentsContributeEvidence() throws Exception {
        // Create an un-ready document
        Document failedDoc = new Document(UUID.randomUUID(), alice, "Failed.pdf", "failed.pdf", "hash_fail", 500);
        failedDoc.setStatus(DocumentStatus.FAILED);
        failedDoc = documentRepository.save(failedDoc);

        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        // Vector store returns chunks from both aliceDoc1 (READY) and failedDoc (FAILED)
        RetrievedChunk readyChunk = new RetrievedChunk(aliceDoc1.getId(), UUID.randomUUID(), 0, 1, "Ready chunk text", 0.90);
        RetrievedChunk failedChunk = new RetrievedChunk(failedDoc.getId(), UUID.randomUUID(), 0, 1, "Failed chunk text", 0.88);

        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(readyChunk, failedChunk));

        SendMessageRequest msg = new SendMessageRequest("What is in the document?");
        MvcResult result = mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk())
                .andReturn();

        ChatAnswerResponse answer = objectMapper.readValue(result.getResponse().getContentAsString(), ChatAnswerResponse.class);
        // Only aliceDoc1 chunk should be cited
        assertThat(answer.assistantMessage().citations()).hasSize(1);
        assertThat(answer.assistantMessage().citations().get(0).documentId()).isEqualTo(aliceDoc1.getId());
    }

    // -------------------------------------------------------------------------
    // Scenario 12: Conversation-selected documents are respected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("12. Conversation explicitly linked to specific documents scopes vector retrieval to those IDs")
    void test12_ConversationSelectedDocumentsAreRespected() throws Exception {
        // Link only aliceDoc2 to conversation
        ConversationResponse conv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Policy Focus", List.of(aliceDoc2.getId()))
        );

        when(vectorStoreService.searchSimilar(any(), eq(List.of(aliceDoc2.getId())), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        SendMessageRequest msg = new SendMessageRequest("What is the risk policy?");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk());

        verify(vectorStoreService).searchSimilar(eq(alice.getId()), eq(List.of(aliceDoc2.getId())), any(), anyInt(), anyDouble());
    }

    // -------------------------------------------------------------------------
    // Scenario 13: No matching chunks produce an insufficient-evidence response
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("13. No matching chunks produce an honest insufficient-evidence response without hallucination")
    void test13_NoMatchingChunksProduceInsufficientEvidenceResponse() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Empty Search", List.of(aliceDoc1.getId()))
        );

        // Vector store returns 0 chunks
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        SendMessageRequest msg = new SendMessageRequest("What is the rocket propulsion specification?");
        MvcResult result = mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk())
                .andReturn();

        ChatAnswerResponse answer = objectMapper.readValue(result.getResponse().getContentAsString(), ChatAnswerResponse.class);
        assertThat(answer.assistantMessage().content()).contains("could not find sufficient information");
        assertThat(answer.assistantMessage().citations()).isEmpty();

        // Gemini ChatModel should NOT be called to avoid hallucination
        verify(chatModel, never()).call(any(Prompt.class));
    }

    // -------------------------------------------------------------------------
    // Scenario 14: Relevant chunks produce a grounded answer
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("14. Relevant retrieved chunks produce grounded answer with citation metadata")
    void test14_RelevantChunksProduceGroundedAnswer() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Grounded Answer Test", List.of(aliceDoc1.getId()))
        );

        RetrievedChunk chunk = new RetrievedChunk(
                aliceDoc1.getId(), UUID.randomUUID(), 0, 3, "Operating margin was 24.5% in Q3.", 0.89
        );
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(chunk));

        SendMessageRequest msg = new SendMessageRequest("What was the operating margin?");
        MvcResult result = mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk())
                .andReturn();

        ChatAnswerResponse answer = objectMapper.readValue(result.getResponse().getContentAsString(), ChatAnswerResponse.class);
        assertThat(answer.assistantMessage().content()).contains("24.5%");
        assertThat(answer.assistantMessage().citations()).hasSize(1);
        assertThat(answer.assistantMessage().citations().get(0).pageNumber()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // Scenario 15: Retrieved page metadata is preserved
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("15. Citation accurately preserves document name, page number, chunk index, and relevance score")
    void test15_RetrievedPageMetadataPreserved() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        RetrievedChunk chunk = new RetrievedChunk(
                aliceDoc1.getId(), UUID.randomUUID(), 2, 7, "Compliance section paragraph.", 0.84
        );
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(chunk));

        SendMessageRequest msg = new SendMessageRequest("Check compliance details");
        MvcResult result = mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk())
                .andReturn();

        ChatAnswerResponse answer = objectMapper.readValue(result.getResponse().getContentAsString(), ChatAnswerResponse.class);
        CitationResponse citation = answer.assistantMessage().citations().get(0);
        assertThat(citation.documentName()).isEqualTo("Q3_Financials.pdf");
        assertThat(citation.pageNumber()).isEqualTo(7);
        assertThat(citation.chunkIndex()).isEqualTo(2);
        assertThat(citation.relevanceScore()).isEqualTo(0.84f);
    }

    // -------------------------------------------------------------------------
    // Scenario 16: Citations are limited to actual retrieved sources
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("16. Citations saved in database are strictly limited to verified retrieved chunks")
    void test16_CitationsLimitedToActualRetrievedSources() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        RetrievedChunk chunk = new RetrievedChunk(
                aliceDoc1.getId(), UUID.randomUUID(), 0, 1, "Only real source in context.", 0.92
        );
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(chunk));

        SendMessageRequest msg = new SendMessageRequest("Summarize facts");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk());

        List<MessageSource> sources = messageSourceRepository.findAll();
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getDocument().getId()).isEqualTo(aliceDoc1.getId());
    }

    // -------------------------------------------------------------------------
    // Scenario 17: Invalid model-produced citation references are rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("17. Hallucinated or out-of-bounds source tags from LLM are discarded by server-side validation")
    void test17_InvalidModelProducedCitationReferencesAreRejected() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        RetrievedChunk chunk1 = new RetrievedChunk(
                aliceDoc1.getId(), UUID.randomUUID(), 0, 1, "Actual evidence.", 0.90
        );
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(chunk1));

        // Model hallucinates [Source 1] and [Source 99]
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            AssistantMessage msg = new AssistantMessage("According to [Source 1] and invented [Source 99], the facts align.");
            return new ChatResponse(List.of(new Generation(msg)));
        });

        SendMessageRequest msg = new SendMessageRequest("Test hallucination rejection");
        MvcResult result = mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk())
                .andReturn();

        ChatAnswerResponse answer = objectMapper.readValue(result.getResponse().getContentAsString(), ChatAnswerResponse.class);
        // Only [Source 1] should be recognized; [Source 99] discarded
        assertThat(answer.assistantMessage().citations()).hasSize(1);
        assertThat(answer.assistantMessage().citations().get(0).pageNumber()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Scenario 18: Gemini failure does not create a fabricated assistant answer
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("18. Gemini API failure preserves user message and does not store fabricated assistant answer")
    void test18_GeminiFailureDoesNotCreateFabricatedAnswer() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        RetrievedChunk chunk = new RetrievedChunk(aliceDoc1.getId(), UUID.randomUUID(), 0, 1, "Evidence", 0.90);
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of(chunk));

        // Mock Gemini failure
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("Gemini quota exceeded (429)"));

        SendMessageRequest msg = new SendMessageRequest("Will fail on LLM call");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isBadRequest());

        // User message is preserved in DB
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.id());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getRole()).isEqualTo(MessageRole.USER);

        // No ASSISTANT message is created
        assertThat(messages.stream().noneMatch(m -> m.getRole() == MessageRole.ASSISTANT)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Scenario 19: Retrieval failure is handled safely
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("19. Vector retrieval failure is caught safely without corrupting conversation history")
    void test19_RetrievalFailureHandledSafely() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        // Mock Qdrant retrieval exception
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenThrow(new IllegalStateException("Qdrant cluster unavailable"));

        SendMessageRequest msg = new SendMessageRequest("Will fail on retrieval");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isBadRequest());

        // User message is preserved
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.id());
        assertThat(messages).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Scenario 20: Another user's document cannot be associated with a conversation
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("20. Cannot associate another user's document with an owned conversation (returns 404)")
    void test20_CannotAssociateAnotherUserDocument() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        // Alice attempts to associate Bob's document
        AddDocumentsRequest req = new AddDocumentsRequest(List.of(bobDoc.getId()));
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/documents")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());

        assertThat(conversationDocumentRepository.findByConversationId(conv.id())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Scenario 21: Duplicate conversation-document associations are prevented
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("21. Duplicate conversation-document associations are prevented idempotently")
    void test21_DuplicateConversationDocumentAssociationsPrevented() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        // Add aliceDoc1 once
        AddDocumentsRequest req1 = new AddDocumentsRequest(List.of(aliceDoc1.getId()));
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/documents")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // Add aliceDoc1 again
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/documents")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk());

        // Only 1 association should exist
        List<ConversationDocument> docs = conversationDocumentRepository.findByConversationId(conv.id());
        assertThat(docs).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Scenario 22: Message source persistence is consistent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("22. Message sources are persisted with valid foreign keys and queryable by message ID")
    void test22_MessageSourcePersistenceIsConsistent() {
        Conversation conv = conversationRepository.save(new Conversation(UUID.randomUUID(), alice, "Citation Chat"));
        RagChatService.ValidatedCitation vc = new RagChatService.ValidatedCitation(
                aliceDoc1, 4, 1, 0.88f, "Verified excerpt text"
        );

        Message assistantMsg = ragChatService.persistAssistantMessage(conv.getId(), "Answer with citation", List.of(vc));

        List<MessageSource> sources = messageSourceRepository.findByMessageIdOrderByRelevanceScoreDesc(assistantMsg.getId());
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getPageNumber()).isEqualTo(4);
        assertThat(sources.get(0).getChunkIndex()).isEqualTo(1);
        assertThat(sources.get(0).getExcerptText()).isEqualTo("Verified excerpt text");
    }

    // -------------------------------------------------------------------------
    // Scenario 23: Conversation deletion handles dependent records safely
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("23. Deleting a conversation cascades and cleanly deletes dependent messages, citations, and links")
    void test23_ConversationDeletionCascadesSafely() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("To Be Deleted", List.of(aliceDoc1.getId()))
        );

        RetrievedChunk chunk = new RetrievedChunk(aliceDoc1.getId(), UUID.randomUUID(), 0, 1, "Context text", 0.90);
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of(chunk));

        // Post a message which generates an assistant message and citation
        SendMessageRequest msg = new SendMessageRequest("Generate some history");
        mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk());

        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.id())).hasSize(2);
        assertThat(messageSourceRepository.findAll()).isNotEmpty();
        assertThat(conversationDocumentRepository.findByConversationId(conv.id())).hasSize(1);

        // Delete conversation via API
        mockMvc.perform(delete("/api/conversations/" + conv.id())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        // Verify conversation and all dependent records are deleted
        assertThat(conversationRepository.findById(conv.id())).isEmpty();
        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.id())).isEmpty();
        assertThat(conversationDocumentRepository.findByConversationId(conv.id())).isEmpty();
        assertThat(messageSourceRepository.findAll()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Scenario 24: Concurrent messages do not silently corrupt conversation ordering
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("24. Concurrent messages to the same conversation are synchronized preventing order corruption")
    void test24_ConcurrentMessagesDoNotCorruptOrdering() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(alice.getId(), null);

        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of());

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ragChatService.sendMessage(alice.getId(), conv.id(), "Concurrent prompt #" + idx);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);

        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.id());
        // Each prompt should produce exactly 1 USER and 1 ASSISTANT message = 8 total messages
        assertThat(messages).hasSize(threadCount * 2);

        // Verify alternating USER -> ASSISTANT pattern without interleaved corruption
        for (int i = 0; i < messages.size(); i += 2) {
            assertThat(messages.get(i).getRole()).isEqualTo(MessageRole.USER);
            assertThat(messages.get(i + 1).getRole()).isEqualTo(MessageRole.ASSISTANT);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 25: Safe Error Reporting - HTTP 404 Model Unavailable
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("25. parseGenAiError formats HTTP 404 model unavailable with actionable recommendation")
    void test25_ParseGenAiErrorSurfacesHttp404ModelUnavailable() {
        com.google.genai.errors.ApiException apiEx =
                new com.google.genai.errors.ApiException(404, "NOT_FOUND", "models/gemini-2.5-flash is no longer available to new users");
        RuntimeException wrapper = new RuntimeException("Failed to generate content", apiEx);

        String diagnostic = RagChatService.parseGenAiError(wrapper, "gemini-2.5-flash");

        assertThat(diagnostic).contains("404");
        assertThat(diagnostic).contains("gemini-2.5-flash");
        assertThat(diagnostic).contains("gemini-3.5-flash-lite");
        assertThat(diagnostic).doesNotContain("AIzaSy"); // No secrets
    }

    // -------------------------------------------------------------------------
    // Scenario 26: Safe Error Reporting - HTTP 503 Model High Demand
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("26. parseGenAiError formats HTTP 503 service unavailable with high-demand guidance")
    void test26_ParseGenAiErrorSurfacesHttp503HighDemand() {
        com.google.genai.errors.ApiException apiEx =
                new com.google.genai.errors.ApiException(503, "UNAVAILABLE", "This model is currently experiencing high demand");
        RuntimeException wrapper = new RuntimeException("Failed to generate content", apiEx);

        String diagnostic = RagChatService.parseGenAiError(wrapper, "gemini-3.8-flash");

        assertThat(diagnostic).contains("503");
        assertThat(diagnostic).contains("high demand");
        assertThat(diagnostic).contains("gemini-3.8-flash");
        assertThat(diagnostic).contains("gemini-3.5-flash-lite");
    }

    // -------------------------------------------------------------------------
    // Scenario 27: Safe Error Reporting - HTTP 401/403 Authentication Failure
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("27. parseGenAiError formats HTTP 401/403 authentication error with GEMINI_API_KEY guidance")
    void test27_ParseGenAiErrorSurfacesHttp401Authentication() {
        com.google.genai.errors.ApiException apiEx =
                new com.google.genai.errors.ApiException(401, "UNAUTHENTICATED", "API key not valid");
        RuntimeException wrapper = new RuntimeException("Failed to generate content", apiEx);

        String diagnostic = RagChatService.parseGenAiError(wrapper, "gemini-3.8-flash");

        assertThat(diagnostic).contains("401");
        assertThat(diagnostic).contains("authentication failed");
        assertThat(diagnostic).contains("GEMINI_API_KEY");
    }

    // -------------------------------------------------------------------------
    // Scenario 28: Safe Error Reporting - HTTP 429 Quota Exceeded
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("28. parseGenAiError formats HTTP 429 quota error safely")
    void test28_ParseGenAiErrorSurfacesHttp429QuotaExceeded() {
        com.google.genai.errors.ApiException apiEx =
                new com.google.genai.errors.ApiException(429, "RESOURCE_EXHAUSTED", "Quota exceeded for quota metric");
        RuntimeException wrapper = new RuntimeException("Failed to generate content", apiEx);

        String diagnostic = RagChatService.parseGenAiError(wrapper, "gemini-3.8-flash");

        assertThat(diagnostic).contains("429");
        assertThat(diagnostic).contains("rate limit or quota exceeded");
    }

    // -------------------------------------------------------------------------
    // Scenario 29: Chat Model Precedence & Name Retrieval
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("29. RagChatService reports configured chat model name")
    void test29_ChatModelNameReporting() {
        assertThat(ragChatService.getChatModelName()).isNotNull().isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Scenario 30: System Prompt Prohibits Trailing Citation List & Requires Inline
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("30. RAG System Prompt requires inline source tags and prohibits trailing citation list")
    void test30_RagSystemPromptProhibitsTrailingCitationListAndEnforcesInlineTags() {
        String prompt = RagChatService.RAG_SYSTEM_PROMPT;
        assertThat(prompt).contains("[Source 1]");
        assertThat(prompt).contains("Do NOT append a trailing");
        assertThat(prompt).contains("Sources");
        assertThat(prompt).contains("citation cards");
    }

    // -------------------------------------------------------------------------
    // Scenario 31: Insufficient-Evidence Fallback Detection
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("31. isInsufficientEvidenceResponse correctly detects fallback phrasings without false positives")
    void test31_IsInsufficientEvidenceResponseDetection() {
        // True positives: Prompt rule 3 and retrieval fallbacks
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "I cannot find sufficient information in the provided documents to answer your question."
        )).isTrue();
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "I could not find sufficient information in the available documents to answer your question. Please ensure relevant documents are indexed or try rephrasing."
        )).isTrue();
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "Based on the provided excerpts, I cannot find sufficient information to answer your question."
        )).isTrue();
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "I can't find sufficient information."
        )).isTrue();
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "I could not find sufficient information."
        )).isTrue();

        // False positives: Normal responses mentioning information or sufficiency
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "According to [Source 1], the document contains sufficient information to explain the database architecture."
        )).isFalse();
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "The database provides sufficient information storage capabilities [Source 2]."
        )).isFalse();
        assertThat(RagChatService.isInsufficientEvidenceResponse(
                "Operating margin was 24.5% in Q3 [Source 1]."
        )).isFalse();
        assertThat(RagChatService.isInsufficientEvidenceResponse(null)).isFalse();
        assertThat(RagChatService.isInsufficientEvidenceResponse("   ")).isFalse();
    }

    // -------------------------------------------------------------------------
    // Scenario 32: extractAndValidateCitations Suppresses Citations for Fallback
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("32. extractAndValidateCitations returns empty list for insufficient evidence and valid list for normal answers")
    void test32_ExtractAndValidateCitationsSuppressionAndRetention() {
        RetrievedChunk chunk1 = new RetrievedChunk(
                aliceDoc1.getId(), UUID.randomUUID(), 0, 1, "Database introduction text", 0.85
        );
        RetrievedChunk chunk2 = new RetrievedChunk(
                aliceDoc1.getId(), UUID.randomUUID(), 1, 2, "SQL syntax text", 0.82
        );
        List<RetrievedChunk> chunks = List.of(chunk1, chunk2);

        // Insufficient evidence response -> MUST return empty list
        List<RetrievedChunk> citationsForFallback = ragChatService.extractAndValidateCitations(
                "I cannot find sufficient information in the provided documents to answer your question.", chunks
        );
        assertThat(citationsForFallback).isEmpty();

        // Normal response with explicit tag [Source 2] -> MUST return only chunk2
        List<RetrievedChunk> citationsWithTag = ragChatService.extractAndValidateCitations(
                "SQL syntax is defined here [Source 2].", chunks
        );
        assertThat(citationsWithTag).hasSize(1);
        assertThat(citationsWithTag.get(0).pageNumber()).isEqualTo(2);

        // Normal response without explicit tag -> defaults to context chunks
        List<RetrievedChunk> citationsWithoutTag = ragChatService.extractAndValidateCitations(
                "SQL is a structured query language.", chunks
        );
        assertThat(citationsWithoutTag).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Scenario 33: End-to-End ChatModel Insufficient Evidence Yields Zero Citations
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("33. When ChatModel responds with insufficient evidence, endpoint returns zero citations and persists none")
    void test33_EndToEndChatModelInsufficientEvidenceReturnsZeroCitations() throws Exception {
        ConversationResponse conv = ragChatService.createConversation(
                alice.getId(), new CreateConversationRequest("Fallback Citation Test", List.of(aliceDoc1.getId()))
        );

        RetrievedChunk chunk = new RetrievedChunk(
                aliceDoc1.getId(), UUID.randomUUID(), 0, 1, "General SQL overview excerpt", 0.72
        );
        when(vectorStoreService.searchSimilar(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(chunk));

        // Model decides the chunks are insufficient to answer the question
        Generation fallbackGen = new Generation(
                new AssistantMessage("I cannot find sufficient information in the provided documents to answer your question.")
        );
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(fallbackGen)));

        SendMessageRequest msg = new SendMessageRequest("What is quantum entanglement in SQL?");
        MvcResult result = mockMvc.perform(post("/api/conversations/" + conv.id() + "/messages")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msg)))
                .andExpect(status().isOk())
                .andReturn();

        ChatAnswerResponse answer = objectMapper.readValue(result.getResponse().getContentAsString(), ChatAnswerResponse.class);
        assertThat(answer.assistantMessage().content()).isEqualTo("I cannot find sufficient information in the provided documents to answer your question.");
        assertThat(answer.assistantMessage().citations()).isEmpty();

        // Verify in DB that no MessageSource records were created for this message
        List<MessageSource> persistedSources = messageSourceRepository.findByMessageIdInOrderByRelevanceScoreDesc(
                List.of(answer.assistantMessage().id())
        );
        assertThat(persistedSources).isEmpty();
    }
}
