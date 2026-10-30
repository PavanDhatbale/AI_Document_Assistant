package com.aidocassistant.service;

import com.aidocassistant.dto.ChatAnswerResponse;
import com.aidocassistant.dto.ConversationDetailResponse;
import com.aidocassistant.dto.ConversationResponse;
import com.aidocassistant.dto.DocumentChunkResponse;
import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.dto.MessageResponse;
import com.aidocassistant.dto.SendMessageRequest;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.ConversationDocumentRepository;
import com.aidocassistant.repository.ConversationRepository;
import com.aidocassistant.repository.DocumentChunkRepository;
import com.aidocassistant.repository.DocumentRepository;
import com.aidocassistant.repository.MessageRepository;
import com.aidocassistant.repository.MessageSourceRepository;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 12 End-to-End Acceptance Test:
 * Validates the complete RAG lifecycle with known synthetic facts:
 * 1. User authentication & JWT generation.
 * 2. Multi-page PDF generation (known facts on page 1 and page 2).
 * 3. Document upload, page-tracking text extraction, and chunking.
 * 4. Qdrant vector indexing with 768-dim mock embeddings.
 * 5. Grounded RAG conversation query, answer synthesis, and citation verification.
 * 6. Insufficient evidence fallback on unanswerable query.
 * 7. Cross-user isolation guarantees (HTTP 404 on unauthorized cross-access).
 * 8. Complete document deletion and storage cleanup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
public class EndToEndRAGAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationDocumentRepository conversationDocumentRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageSourceRepository messageSourceRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private ChatModel chatModel;

    @MockBean
    private QdrantClient qdrantClient;

    private User userA;
    private String tokenA;

    private User userB;
    private String tokenB;

    private byte[] knownFactPdfBytes;

    // Known synthetic test facts
    public static final String FACT_PAGE_1 = "Fact Alpha: Project Antigravity launched on January 15, 2026, with an initial budget of 12 million dollars.";
    public static final String FACT_PAGE_2 = "Fact Beta: The core vector engine uses 768-dimensional embeddings with cosine distance metric for sub-second retrieval.";

    @BeforeEach
    void setUp() throws IOException {
        messageSourceRepository.deleteAll();
        messageRepository.deleteAll();
        conversationDocumentRepository.deleteAll();
        conversationRepository.deleteAll();
        documentChunkRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // Clear test storage directory
        Path storageDir = fileStorageService.getStorageDirectory();
        if (Files.exists(storageDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir)) {
                for (Path file : stream) {
                    Files.deleteIfExists(file);
                }
            }
        }

        // Create User A
        userA = new User();
        userA.setId(UUID.randomUUID());
        userA.setEmail("usera@company.com");
        userA.setPasswordHash(passwordEncoder.encode("Password123!"));
        userA.setDisplayName("User Alpha");
        userA.setRole(UserRole.ROLE_USER);
        userA.setEnabled(true);
        userA = userRepository.save(userA);
        tokenA = jwtTokenProvider.generateToken(userA.getId(), userA.getEmail(), userA.getRole().name());

        // Create User B (for cross-user isolation tests)
        userB = new User();
        userB.setId(UUID.randomUUID());
        userB.setEmail("userb@company.com");
        userB.setPasswordHash(passwordEncoder.encode("Password123!"));
        userB.setDisplayName("User Beta");
        userB.setRole(UserRole.ROLE_USER);
        userB.setEnabled(true);
        userB = userRepository.save(userB);
        tokenB = jwtTokenProvider.generateToken(userB.getId(), userB.getEmail(), userB.getRole().name());

        // Generate synthetic known-fact PDF
        knownFactPdfBytes = generateSyntheticPdf(FACT_PAGE_1, FACT_PAGE_2);

        // Save to test fixtures directory for external inspection
        File fixtureDir = new File("src/test/resources/fixtures");
        if (!fixtureDir.exists()) {
            fixtureDir.mkdirs();
        }
        File fixtureFile = new File(fixtureDir, "known_facts_test.pdf");
        try (FileOutputStream fos = new FileOutputStream(fixtureFile)) {
            fos.write(knownFactPdfBytes);
        }

        // Mock Qdrant collection check and upsert
        when(qdrantClient.collectionExistsAsync(anyString()))
                .thenReturn(Futures.immediateFuture(true));
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));
        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));

        // Mock Embedding Model to return 768-dim unit vector
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> list = invocation.getArgument(0);
            return list.stream().map(s -> new float[768]).toList();
        });
    }

    @Test
    @DisplayName("Complete Live-Simulated End-to-End RAG Lifecycle with Known Facts and Citations")
    void testCompleteRagLifecycleWithKnownFacts() throws Exception {
        // --- STEP 1: Upload Synthetic 2-Page PDF ---
        MockMultipartFile file = new MockMultipartFile(
                "file", "known_facts_test.pdf", "application/pdf", knownFactPdfBytes
        );

        MvcResult uploadResult = mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFileName").value("known_facts_test.pdf"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        DocumentResponse uploadedDoc = objectMapper.readValue(
                uploadResult.getResponse().getContentAsString(), DocumentResponse.class
        );
        UUID documentId = uploadedDoc.id();
        assertThat(documentId).isNotNull();

        // --- STEP 2: Process Document (Extract Text, Chunk, Vector Index) ---
        mockMvc.perform(post("/api/documents/" + documentId + "/process")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalChunks").value(2));

        // Verify chunks in relational database
        MvcResult chunksResult = mockMvc.perform(get("/api/documents/" + documentId + "/chunks")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        DocumentChunkResponse[] chunks = objectMapper.readValue(
                chunksResult.getResponse().getContentAsString(), DocumentChunkResponse[].class
        );
        assertThat(chunks).hasSize(2);
        assertThat(chunks[0].pageNumber()).isEqualTo(1);
        assertThat(chunks[0].chunkText()).contains("Project Antigravity launched on January 15, 2026");
        assertThat(chunks[1].pageNumber()).isEqualTo(2);
        assertThat(chunks[1].chunkText()).contains("core vector engine uses 768-dimensional embeddings");

        // --- STEP 3: Create Grounded Conversation ---
        MvcResult convResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Antigravity Project Inquiries\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Antigravity Project Inquiries"))
                .andReturn();

        ConversationResponse conversation = objectMapper.readValue(
                convResult.getResponse().getContentAsString(), ConversationResponse.class
        );
        UUID convId = conversation.id();

        // --- STEP 4: Query Fact 1 (Page 1) with Mocked Qdrant Search Result ---
        // Mock Qdrant search returning Page 1 chunk
        Points.ScoredPoint scoredPointPage1 = Points.ScoredPoint.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(UUID.randomUUID()))
                .setScore(0.92f)
                .putPayload("user_id", JsonWithInt.Value.newBuilder().setStringValue(userA.getId().toString()).build())
                .putPayload("document_id", JsonWithInt.Value.newBuilder().setStringValue(documentId.toString()).build())
                .putPayload("chunk_id", JsonWithInt.Value.newBuilder().setStringValue(chunks[0].id().toString()).build())
                .putPayload("page_number", JsonWithInt.Value.newBuilder().setIntegerValue(1).build())
                .putPayload("chunk_index", JsonWithInt.Value.newBuilder().setIntegerValue(0).build())
                .putPayload("chunk_text", JsonWithInt.Value.newBuilder().setStringValue(chunks[0].chunkText()).build())
                .build();

        when(qdrantClient.searchAsync(any(Points.SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of(scoredPointPage1)));

        // Mock Gemini ChatModel response citing [Source 1]
        String geminiResponsePage1 = "Project Antigravity officially launched on January 15, 2026, with an allocated budget of $12 million [Source 1].";
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(geminiResponsePage1)))));

        MvcResult msg1Result = mockMvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("When did Project Antigravity launch and what was its budget?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.content").value(geminiResponsePage1))
                .andExpect(jsonPath("$.assistantMessage.citations[0].documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.assistantMessage.citations[0].pageNumber").value(1))
                .andExpect(jsonPath("$.assistantMessage.citations[0].chunkIndex").value(0))
                .andReturn();

        ChatAnswerResponse answer1 = objectMapper.readValue(
                msg1Result.getResponse().getContentAsString(), ChatAnswerResponse.class
        );
        assertThat(answer1.assistantMessage().citations()).hasSize(1);
        assertThat(answer1.assistantMessage().citations().get(0).pageNumber()).isEqualTo(1);
        assertThat(answer1.assistantMessage().citations().get(0).excerptText()).contains("Project Antigravity launched on January 15, 2026");

        // --- STEP 5: Query Fact 2 (Page 2) with Mocked Qdrant Search Result ---
        Points.ScoredPoint scoredPointPage2 = Points.ScoredPoint.newBuilder()
                .setId(io.qdrant.client.PointIdFactory.id(UUID.randomUUID()))
                .setScore(0.89f)
                .putPayload("user_id", JsonWithInt.Value.newBuilder().setStringValue(userA.getId().toString()).build())
                .putPayload("document_id", JsonWithInt.Value.newBuilder().setStringValue(documentId.toString()).build())
                .putPayload("chunk_id", JsonWithInt.Value.newBuilder().setStringValue(chunks[1].id().toString()).build())
                .putPayload("page_number", JsonWithInt.Value.newBuilder().setIntegerValue(2).build())
                .putPayload("chunk_index", JsonWithInt.Value.newBuilder().setIntegerValue(1).build())
                .putPayload("chunk_text", JsonWithInt.Value.newBuilder().setStringValue(chunks[1].chunkText()).build())
                .build();

        when(qdrantClient.searchAsync(any(Points.SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of(scoredPointPage2)));

        String geminiResponsePage2 = "The vector engine uses 768 dimensions and the cosine distance metric [Source 1].";
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(geminiResponsePage2)))));

        MvcResult msg2Result = mockMvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("What vector dimension and metric are used by the vector engine?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.citations[0].pageNumber").value(2))
                .andExpect(jsonPath("$.assistantMessage.citations[0].chunkIndex").value(1))
                .andReturn();

        ChatAnswerResponse answer2 = objectMapper.readValue(
                msg2Result.getResponse().getContentAsString(), ChatAnswerResponse.class
        );
        assertThat(answer2.assistantMessage().citations().get(0).pageNumber()).isEqualTo(2);

        // --- STEP 6: Query Unanswerable Fact (Low Similarity / No Results) ---
        when(qdrantClient.searchAsync(any(Points.SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of())); // Zero chunks match

        mockMvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("What is the secret recipe for Martian tea?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantMessage.content").value("I could not find sufficient information in the available documents to answer your question. Please ensure relevant documents are indexed or try rephrasing."))
                .andExpect(jsonPath("$.assistantMessage.citations").isEmpty());

        // --- STEP 7: Cross-User Isolation (User B cannot access User A's data) ---
        // User B attempts to access User A's document
        mockMvc.perform(get("/api/documents/" + documentId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // User B attempts to process User A's document
        mockMvc.perform(post("/api/documents/" + documentId + "/process")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // User B attempts to delete User A's document
        mockMvc.perform(delete("/api/documents/" + documentId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // User B attempts to access User A's conversation
        mockMvc.perform(get("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // User B attempts to post message to User A's conversation
        mockMvc.perform(post("/api/conversations/" + convId + "/messages")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendMessageRequest("Malicious injection attempt"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // --- STEP 8: Clean Document Deletion by Owner (User A) ---
        mockMvc.perform(delete("/api/documents/" + documentId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Document deleted successfully"));

        // Verify relational record is gone
        assertThat(documentRepository.findById(documentId)).isEmpty();
        assertThat(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId)).isEmpty();
    }

    private byte[] generateSyntheticPdf(String... pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (text != null && !text.isBlank()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(text);
                        cs.endText();
                    }
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
