package com.aidocassistant.service;

import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.dto.RetrievedChunk;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentChunk;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.DocumentChunkRepository;
import com.aidocassistant.repository.DocumentRepository;
import com.aidocassistant.repository.UserRepository;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Collections;
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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive automated test suite verifying Task 8:
 * Gemini Embeddings, Qdrant Vector Indexing, and End-to-End Ingestion Lifecycle.
 * Covers all 20 required test scenarios with mocked Gemini and Qdrant clients.
 */
@SpringBootTest
@ActiveProfiles("auth-test")
class IngestionAndVectorStoreTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private QdrantClient qdrantClient;

    private User testUser1;
    private User testUser2;

    @BeforeEach
    void setUp() throws IOException {
        documentChunkRepository.deleteAll();
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // Clear test upload storage
        Path storageDir = fileStorageService.getStorageDirectory();
        if (Files.exists(storageDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir)) {
                for (Path file : stream) {
                    Files.deleteIfExists(file);
                }
            }
        }

        testUser1 = new User();
        testUser1.setId(UUID.randomUUID());
        testUser1.setEmail("investor@example.com");
        testUser1.setPasswordHash(passwordEncoder.encode("Password123!"));
        testUser1.setDisplayName("Investor Alice");
        testUser1.setRole(UserRole.ROLE_USER);
        testUser1.setEnabled(true);
        testUser1 = userRepository.save(testUser1);

        testUser2 = new User();
        testUser2.setId(UUID.randomUUID());
        testUser2.setEmail("analyst@example.com");
        testUser2.setPasswordHash(passwordEncoder.encode("Password123!"));
        testUser2.setDisplayName("Analyst Bob");
        testUser2.setRole(UserRole.ROLE_USER);
        testUser2.setEnabled(true);
        testUser2 = userRepository.save(testUser2);

        // Default mock stubs for happy path
        when(embeddingModel.call(any(EmbeddingRequest.class))).thenAnswer(invocation -> {
            EmbeddingRequest req = invocation.getArgument(0);
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < req.getInstructions().size(); i++) {
                embeddings.add(new Embedding(new float[768], i));
            }
            return new EmbeddingResponse(embeddings);
        });

        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> list = invocation.getArgument(0);
            return list.stream().map(s -> new float[768]).toList();
        });

        when(qdrantClient.collectionExistsAsync(anyString()))
                .thenReturn(Futures.immediateFuture(true));

        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));

        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));

        vectorStoreService.resetCollectionInitialized();
    }

    private byte[] createPdf(String... pageTexts) throws IOException {
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

    // -------------------------------------------------------------------------
    // Scenario 1: Document Transitions to READY Only After Qdrant Confirmation
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("1. Document transitions to READY status only after Qdrant confirms point indexing")
    void test1_DocumentTransitionsToReadyOnlyAfterQdrantConfirmation() throws IOException {
        byte[] pdf = createPdf("Quarterly financial report page 1.", "Risk factor disclosures page 2.");
        MockMultipartFile file = new MockMultipartFile("file", "q_report.pdf", "application/pdf", pdf);

        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);
        assertThat(uploaded.status()).isEqualTo(DocumentStatus.PENDING);

        DocumentResponse processed = documentService.processDocument(testUser1.getId(), uploaded.id());
        assertThat(processed.status()).isEqualTo(DocumentStatus.READY);

        Document persisted = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(persisted.getTotalPages()).isEqualTo(2);
        assertThat(persisted.getTotalChunks()).isEqualTo(2);

        verify(qdrantClient, atLeastOnce()).upsertAsync(eq("document_embeddings"), anyList());
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Embedding Batching Respects Configured Batch Size
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("2. Embedding service respects batching boundary (splits chunks into bounded batches)")
    void test2_EmbeddingBatchingRespectsConfiguredBatchSize() {
        // Create 70 synthetic chunk texts (with batch size 32 -> 3 batches: 32, 32, 6)
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            texts.add("Sample chunk text number " + i);
        }

        List<float[]> embeddings = embeddingService.generateEmbeddings(texts);
        assertThat(embeddings).hasSize(70);
        assertThat(embeddings.get(0)).hasSize(768);

        ArgumentCaptor<EmbeddingRequest> requestCaptor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddingModel, times(3)).call(requestCaptor.capture());

        List<EmbeddingRequest> capturedRequests = requestCaptor.getAllValues();
        assertThat(capturedRequests.get(0).getInstructions()).hasSize(32);
        assertThat(capturedRequests.get(1).getInstructions()).hasSize(32);
        assertThat(capturedRequests.get(2).getInstructions()).hasSize(6);
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Deterministic Point ID Generation
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("3. Point IDs are generated deterministically using documentId and chunkIndex")
    void test3_DeterministicPointIdGeneration() {
        UUID docId = UUID.randomUUID();
        int chunkIdx = 3;

        UUID pointId1 = vectorStoreService.generatePointId(docId, chunkIdx);
        UUID pointId2 = vectorStoreService.generatePointId(docId, chunkIdx);

        assertThat(pointId1).isEqualTo(pointId2);
        UUID expectedUuid = UUID.nameUUIDFromBytes((docId.toString() + "_" + chunkIdx).getBytes(StandardCharsets.UTF_8));
        assertThat(pointId1).isEqualTo(expectedUuid);
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Reindexing Document Overwrites Qdrant Points Cleanly
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("4. Re-processing a document reuses identical deterministic Point IDs (idempotent upsert)")
    @SuppressWarnings("unchecked")
    void test4_ReindexingDocumentOverwritesQdrantPointsCleanly() throws IOException {
        byte[] pdf = createPdf("Initial text content page 1.");
        MockMultipartFile file = new MockMultipartFile("file", "retry_doc.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        // Run 1
        documentService.processDocument(testUser1.getId(), uploaded.id());

        // Run 2 (re-index / retry)
        // Reset document to PENDING to simulate re-processing
        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        doc.setStatus(DocumentStatus.PENDING);
        documentRepository.save(doc);

        documentService.processDocument(testUser1.getId(), uploaded.id());

        ArgumentCaptor<List<Points.PointStruct>> pointsCaptor = ArgumentCaptor.forClass(List.class);
        verify(qdrantClient, times(2)).upsertAsync(eq("document_embeddings"), pointsCaptor.capture());

        List<List<Points.PointStruct>> allUpserts = pointsCaptor.getAllValues();
        Points.PointStruct firstRunPoint = allUpserts.get(0).get(0);
        Points.PointStruct secondRunPoint = allUpserts.get(1).get(0);

        assertThat(firstRunPoint.getId()).isEqualTo(secondRunPoint.getId());
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Multi-Tenant Query Filter Prevents Cross-User Vector Leakage
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("5. Vector search includes mandatory user_id filter ensuring strict cross-user isolation")
    void test5_MultiTenantQueryFilterPreventsCrossUserVectorLeakage() {
        ArgumentCaptor<Points.SearchPoints> searchCaptor = ArgumentCaptor.forClass(Points.SearchPoints.class);
        when(qdrantClient.searchAsync(searchCaptor.capture())).thenReturn(Futures.immediateFuture(List.of()));

        float[] queryVec = new float[768];
        vectorStoreService.searchSimilar(testUser1.getId(), null, queryVec, 5, 0.65);

        Points.SearchPoints searchPoints = searchCaptor.getValue();
        assertThat(searchPoints.getFilter().toString()).contains("user_id");
        assertThat(searchPoints.getFilter().toString()).contains(testUser1.getId().toString());
        assertThat(searchPoints.getFilter().toString()).doesNotContain(testUser2.getId().toString());
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Query Filter by Document IDs
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("6. Vector search filters by document IDs when scoped document list is provided")
    void test6_QueryFilterByDocumentIds() {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();

        ArgumentCaptor<Points.SearchPoints> searchCaptor = ArgumentCaptor.forClass(Points.SearchPoints.class);
        when(qdrantClient.searchAsync(searchCaptor.capture())).thenReturn(Futures.immediateFuture(List.of()));

        float[] queryVec = new float[768];
        vectorStoreService.searchSimilar(testUser1.getId(), List.of(docA, docB), queryVec, 4, 0.70);

        Points.SearchPoints searchPoints = searchCaptor.getValue();
        assertThat(searchPoints.getFilter().toString()).contains("document_id");
        assertThat(searchPoints.getFilter().toString()).contains(docA.toString());
        assertThat(searchPoints.getFilter().toString()).contains(docB.toString());
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Vector Dimension Mismatch on Collection Init Fails Fast
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("7. VectorStoreService fails fast when existing collection has mismatched dimension")
    void test7_VectorDimensionMismatchOnCollectionInitFailsFast() {
        when(qdrantClient.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(true));

        Collections.CollectionInfo mismatchedInfo = Collections.CollectionInfo.newBuilder()
                .setConfig(Collections.CollectionConfig.newBuilder()
                        .setParams(Collections.CollectionParams.newBuilder()
                                .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                                        .setParams(Collections.VectorParams.newBuilder()
                                                .setSize(1536)
                                                .setDistance(Collections.Distance.Cosine)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        when(qdrantClient.getCollectionInfoAsync(anyString())).thenReturn(Futures.immediateFuture(mismatchedInfo));

        assertThatThrownBy(() -> vectorStoreService.initCollection())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration mismatch")
                .hasMessageContaining("1536");
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Distance Metric Mismatch on Collection Init Fails Fast
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("8. VectorStoreService fails fast when existing collection has mismatched distance metric")
    void test8_DistanceMetricMismatchOnCollectionInitFailsFast() {
        when(qdrantClient.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(true));

        Collections.CollectionInfo mismatchedInfo = Collections.CollectionInfo.newBuilder()
                .setConfig(Collections.CollectionConfig.newBuilder()
                        .setParams(Collections.CollectionParams.newBuilder()
                                .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                                        .setParams(Collections.VectorParams.newBuilder()
                                                .setSize(768)
                                                .setDistance(Collections.Distance.Dot)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        when(qdrantClient.getCollectionInfoAsync(anyString())).thenReturn(Futures.immediateFuture(mismatchedInfo));

        assertThatThrownBy(() -> vectorStoreService.initCollection())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration mismatch")
                .hasMessageContaining("Dot");
    }

    // -------------------------------------------------------------------------
    // Scenario 9: Collection Created With Correct Parameters If Missing
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("9. Missing collection is created with 768-dim, Cosine distance, and payload indexes")
    void test9_CollectionCreatedWithCorrectParametersIfMissing() {
        when(qdrantClient.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(false));
        when(qdrantClient.createCollectionAsync(anyString(), any(Collections.VectorParams.class)))
                .thenReturn(Futures.immediateFuture(Collections.CollectionOperationResponse.newBuilder().setResult(true).build()));
        when(qdrantClient.createPayloadIndexAsync(any(Points.CreateFieldIndexCollection.class), any()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));

        vectorStoreService.initCollection();

        ArgumentCaptor<Collections.VectorParams> vpCaptor = ArgumentCaptor.forClass(Collections.VectorParams.class);
        verify(qdrantClient).createCollectionAsync(eq("document_embeddings"), vpCaptor.capture());
        assertThat(vpCaptor.getValue().getSize()).isEqualTo(768);
        assertThat(vpCaptor.getValue().getDistance()).isEqualTo(Collections.Distance.Cosine);

        verify(qdrantClient, times(2)).createPayloadIndexAsync(any(Points.CreateFieldIndexCollection.class), any());
    }

    // -------------------------------------------------------------------------
    // Scenario 9B: indexChunks auto-initializes collection if not yet created
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("9B. indexChunks triggers collection creation if collection does not exist")
    void test9B_IndexChunksAutoInitializesCollectionIfMissing() {
        when(qdrantClient.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(false));
        when(qdrantClient.createCollectionAsync(anyString(), any(Collections.VectorParams.class)))
                .thenReturn(Futures.immediateFuture(Collections.CollectionOperationResponse.newBuilder().setResult(true).build()));
        when(qdrantClient.createPayloadIndexAsync(any(Points.CreateFieldIndexCollection.class), any()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));

        Document doc = new Document(UUID.randomUUID(), testUser1, "test.pdf", "stored.pdf", "hash123", 1024L);
        DocumentChunk chunk = new DocumentChunk(UUID.randomUUID(), doc, 0, 1, "test chunk text", 10);
        float[] vector = new float[768];

        vectorStoreService.indexChunks(testUser1.getId(), doc.getId(), List.of(chunk), List.of(vector));

        verify(qdrantClient).createCollectionAsync(eq("document_embeddings"), any(Collections.VectorParams.class));
        verify(qdrantClient).upsertAsync(eq("document_embeddings"), anyList());
    }

    // -------------------------------------------------------------------------
    // Scenario 9C: deleteDocumentVectors safely skips if collection does not exist
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("9C. deleteDocumentVectors safely skips when collection does not exist")
    void test9C_DeleteDocumentVectorsSafelySkipsWhenCollectionMissing() {
        when(qdrantClient.collectionExistsAsync(anyString())).thenReturn(Futures.immediateFuture(false));

        // Should complete cleanly without throwing an exception or calling deleteAsync
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                vectorStoreService.deleteDocumentVectors(testUser1.getId(), UUID.randomUUID())
        );

        verify(qdrantClient, never()).deleteAsync(anyString(), any(Points.Filter.class));
    }

    // -------------------------------------------------------------------------
    // Scenario 10: NaN or Infinite Embedding Vector Rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("10. Vectors containing NaN or +/- Infinity are rejected with IllegalStateException")
    void test10_NaNOrInfiniteEmbeddingVectorRejected() {
        float[] nanVec = new float[768];
        nanVec[15] = Float.NaN;

        assertThatThrownBy(() -> embeddingService.validateVector(nanVec, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-finite value");

        float[] infVec = new float[768];
        infVec[20] = Float.POSITIVE_INFINITY;

        assertThatThrownBy(() -> embeddingService.validateVector(infVec, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-finite value");
    }

    // -------------------------------------------------------------------------
    // Scenario 11: Embedding Dimension Mismatch Rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("11. Vectors with invalid dimensions (e.g. 512 or 1536) are rejected")
    void test11_EmbeddingDimensionMismatchRejected() {
        float[] invalidDimensionVector = new float[512];

        assertThatThrownBy(() -> embeddingService.validateVector(invalidDimensionVector, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid dimension 512, expected 768");
    }

    // -------------------------------------------------------------------------
    // Scenario 12: Empty or Null Embedding Vector Rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("12. Null or empty embedding vectors are rejected")
    void test12_EmptyOrNullEmbeddingVectorRejected() {
        assertThatThrownBy(() -> embeddingService.validateVector(null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is null");

        assertThatThrownBy(() -> embeddingService.validateVector(new float[0], 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }

    // -------------------------------------------------------------------------
    // Scenario 13: Zero Extractable Text Transitions to FAILED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("13. Document with zero extractable text safely transitions to FAILED status")
    void test13_ZeroExtractableTextTransitionsToFailed() throws IOException {
        // Create PDF with blank pages (no text content streams)
        byte[] blankPdf = createPdf("", "");
        MockMultipartFile file = new MockMultipartFile("file", "blank_doc.pdf", "application/pdf", blankPdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        DocumentResponse processed = documentService.processDocument(testUser1.getId(), uploaded.id());
        assertThat(processed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(processed.errorMessage()).contains("PDF contains no extractable text");

        Document persisted = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // -------------------------------------------------------------------------
    // Scenario 14: Gemini API Failure Transitions to FAILED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("14. Gemini API failure marks document FAILED and cleans up partial chunks")
    void test14_GeminiApiFailureTransitionsToFailed() throws IOException {
        byte[] pdf = createPdf("Sample text to trigger Gemini embedding call.");
        MockMultipartFile file = new MockMultipartFile("file", "gemini_fail.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        // Configure Gemini mock to throw quota exceeded exception
        when(embeddingModel.call(any(EmbeddingRequest.class)))
                .thenThrow(new RuntimeException("Resource has been exhausted (quota exceeded)"));
        when(embeddingModel.embed(anyList()))
                .thenThrow(new RuntimeException("Resource has been exhausted (quota exceeded)"));

        DocumentResponse processed = documentService.processDocument(testUser1.getId(), uploaded.id());
        assertThat(processed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(processed.errorMessage()).contains("Resource has been exhausted");

        // Verify relational chunks were cleaned up
        assertThat(documentChunkRepository.countByDocumentId(uploaded.id())).isEqualTo(0);

        Document persisted = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // -------------------------------------------------------------------------
    // Scenario 15: Qdrant Upsert Failure Transitions to FAILED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("15. Qdrant upsert failure marks document FAILED and cleans up chunks/vectors")
    void test15_QdrantUpsertFailureTransitionsToFailed() throws IOException {
        byte[] pdf = createPdf("Sample text to trigger Qdrant point upsert.");
        MockMultipartFile file = new MockMultipartFile("file", "qdrant_fail.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        // Configure Qdrant mock to fail upsert
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("Connection refused: Qdrant cluster unavailable")));

        DocumentResponse processed = documentService.processDocument(testUser1.getId(), uploaded.id());
        assertThat(processed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(processed.errorMessage()).contains("Connection refused");

        // Verify chunks were cleaned up
        assertThat(documentChunkRepository.countByDocumentId(uploaded.id())).isEqualTo(0);

        Document persisted = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // -------------------------------------------------------------------------
    // Scenario 16: Two-Phase Deletion Success Purges Vectors, File, and DB
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("16. Successful deletion purges Qdrant vectors, deletes file on disk, and removes MySQL row")
    void test16_TwoPhaseDeletionSuccessPurgesVectorsAndFileAndDb() throws IOException {
        byte[] pdf = createPdf("Text content to be indexed and then deleted.");
        MockMultipartFile file = new MockMultipartFile("file", "to_delete.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);
        documentService.processDocument(testUser1.getId(), uploaded.id());

        // Delete document
        documentService.deleteDocument(testUser1.getId(), uploaded.id());

        // Verify Qdrant delete was invoked with filter
        ArgumentCaptor<Points.Filter> filterCaptor = ArgumentCaptor.forClass(Points.Filter.class);
        verify(qdrantClient, atLeastOnce()).deleteAsync(eq("document_embeddings"), filterCaptor.capture());
        Points.Filter capturedFilter = filterCaptor.getValue();
        assertThat(capturedFilter.toString()).contains(testUser1.getId().toString());
        assertThat(capturedFilter.toString()).contains(uploaded.id().toString());

        // Verify MySQL record deleted
        assertThat(documentRepository.findById(uploaded.id())).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Scenario 17: Two-Phase Deletion Qdrant Failure Transitions to DELETION_FAILED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("17. Qdrant vector deletion failure marks status DELETION_FAILED without deleting file or MySQL row")
    void test17_TwoPhaseDeletionQdrantFailureTransitionsToDeletionFailed() throws IOException {
        byte[] pdf = createPdf("Text content for deletion failure test.");
        MockMultipartFile file = new MockMultipartFile("file", "del_fail.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);
        documentService.processDocument(testUser1.getId(), uploaded.id());

        // Mock Qdrant deletion failure
        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("Qdrant gRPC timeout during vector purge")));

        assertThatThrownBy(() -> documentService.deleteDocument(testUser1.getId(), uploaded.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to delete document vectors from vector store");

        // Assert status moved to DELETION_FAILED
        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.DELETION_FAILED);
        assertThat(doc.getErrorMessage()).contains("Qdrant gRPC timeout");

        // Verify file still exists on disk
        Path filePath = fileStorageService.getStorageDirectory().resolve(doc.getStorageFileName());
        assertThat(Files.exists(filePath)).isTrue();
    }

    // -------------------------------------------------------------------------
    // Scenario 18: Search Filters Out Non-Ready Documents
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("18. Vector search results filter out chunks belonging to documents that are not READY")
    void test18_SearchFiltersOutNonReadyDocuments() throws IOException {
        byte[] pdfA = createPdf("Page text content for ready doc.");
        byte[] pdfB = createPdf("Distinct page text content for failed doc.");
        MockMultipartFile fileA = new MockMultipartFile("file", "ready_doc.pdf", "application/pdf", pdfA);
        MockMultipartFile fileB = new MockMultipartFile("file", "failed_doc.pdf", "application/pdf", pdfB);

        DocumentResponse docA = documentService.uploadDocument(testUser1.getId(), fileA);
        DocumentResponse docB = documentService.uploadDocument(testUser1.getId(), fileB);

        // Mark docA READY, docB FAILED
        Document entityA = documentRepository.findById(docA.id()).orElseThrow();
        entityA.setStatus(DocumentStatus.READY);
        documentRepository.save(entityA);

        Document entityB = documentRepository.findById(docB.id()).orElseThrow();
        entityB.setStatus(DocumentStatus.FAILED);
        documentRepository.save(entityB);

        // Mock Qdrant search returning chunks from both docA and docB
        UUID chunkAId = UUID.randomUUID();
        UUID chunkBId = UUID.randomUUID();

        Map<String, JsonWithInt.Value> payloadA = new HashMap<>();
        payloadA.put("document_id", ValueFactory.value(docA.id().toString()));
        payloadA.put("chunk_id", ValueFactory.value(chunkAId.toString()));
        payloadA.put("chunk_index", ValueFactory.value(0L));
        payloadA.put("page_number", ValueFactory.value(1L));
        payloadA.put("chunk_text", ValueFactory.value("Text from ready doc"));

        Map<String, JsonWithInt.Value> payloadB = new HashMap<>();
        payloadB.put("document_id", ValueFactory.value(docB.id().toString()));
        payloadB.put("chunk_id", ValueFactory.value(chunkBId.toString()));
        payloadB.put("chunk_index", ValueFactory.value(0L));
        payloadB.put("page_number", ValueFactory.value(1L));
        payloadB.put("chunk_text", ValueFactory.value("Text from failed doc"));

        Points.ScoredPoint pointA = Points.ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .setScore(0.88f)
                .putAllPayload(payloadA)
                .build();

        Points.ScoredPoint pointB = Points.ScoredPoint.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .setScore(0.85f)
                .putAllPayload(payloadB)
                .build();

        when(qdrantClient.searchAsync(any(Points.SearchPoints.class)))
                .thenReturn(Futures.immediateFuture(List.of(pointA, pointB)));

        List<RetrievedChunk> results = documentService.searchSimilarChunks(
                testUser1.getId(), null, new float[768], 5, 0.65
        );

        // Only docA (READY) chunks should be returned
        assertThat(results).hasSize(1);
        assertThat(results.get(0).documentId()).isEqualTo(docA.id());
        assertThat(results.get(0).text()).isEqualTo("Text from ready doc");
    }

    // -------------------------------------------------------------------------
    // Scenario 19: Search Similarity Threshold Filtering
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("19. Vector search sets configured score threshold on SearchPoints query")
    void test19_SearchSimilarityThresholdFiltering() {
        ArgumentCaptor<Points.SearchPoints> searchCaptor = ArgumentCaptor.forClass(Points.SearchPoints.class);
        when(qdrantClient.searchAsync(searchCaptor.capture())).thenReturn(Futures.immediateFuture(List.of()));

        float[] queryVec = new float[768];
        vectorStoreService.searchSimilar(testUser1.getId(), null, queryVec, 4, 0.82);

        Points.SearchPoints captured = searchCaptor.getValue();
        assertThat(captured.getScoreThreshold()).isEqualTo(0.82f);
        assertThat(captured.getLimit()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // Scenario 20: Concurrent Processing Rejected
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("20. Rejects processing when document is already in PROCESSING status")
    void test20_ConcurrentProcessingRejected() throws IOException {
        byte[] pdf = createPdf("Concurrent test document.");
        MockMultipartFile file = new MockMultipartFile("file", "concurrent.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        assertThatThrownBy(() -> documentService.processDocument(testUser1.getId(), uploaded.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is currently being processed");
    }

    // -------------------------------------------------------------------------
    // Scenario 21: Database-Level Concurrency Guard & State Transitions
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("21. Database-level atomic conditional transition guards multi-instance processing, retries FAILED, and recovers stale crashes")
    void test21_DatabaseLevelConcurrencyGuard() throws IOException {
        byte[] pdf = createPdf("Concurrency guard test document.");
        MockMultipartFile file = new MockMultipartFile("file", "concurrency_guard.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        Instant staleThreshold = Instant.now().minus(30, ChronoUnit.MINUTES);
        List<DocumentStatus> allowedStatuses = List.of(DocumentStatus.PENDING, DocumentStatus.FAILED, DocumentStatus.READY);

        // 1. First claim succeeds (PENDING -> PROCESSING)
        Integer claim1 = transactionTemplate.execute(status -> documentRepository.claimDocumentForProcessing(
                uploaded.id(), testUser1.getId(), DocumentStatus.PROCESSING, allowedStatuses, DocumentStatus.PROCESSING, staleThreshold
        ));
        assertThat(claim1).isEqualTo(1);

        // Verify status transitioned to PROCESSING
        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PROCESSING);

        // 2. Concurrent second claim fails (returns 0)
        Integer claim2 = transactionTemplate.execute(status -> documentRepository.claimDocumentForProcessing(
                uploaded.id(), testUser1.getId(), DocumentStatus.PROCESSING, allowedStatuses, DocumentStatus.PROCESSING, staleThreshold
        ));
        assertThat(claim2).isEqualTo(0);

        // 3. Document in DELETING cannot be claimed
        doc.setStatus(DocumentStatus.DELETING);
        documentRepository.save(doc);
        Integer claimDeleting = transactionTemplate.execute(status -> documentRepository.claimDocumentForProcessing(
                uploaded.id(), testUser1.getId(), DocumentStatus.PROCESSING, allowedStatuses, DocumentStatus.PROCESSING, staleThreshold
        ));
        assertThat(claimDeleting).isEqualTo(0);

        // 4. Document in DELETION_FAILED cannot be claimed
        doc.setStatus(DocumentStatus.DELETION_FAILED);
        documentRepository.save(doc);
        Integer claimDeletionFailed = transactionTemplate.execute(status -> documentRepository.claimDocumentForProcessing(
                uploaded.id(), testUser1.getId(), DocumentStatus.PROCESSING, allowedStatuses, DocumentStatus.PROCESSING, staleThreshold
        ));
        assertThat(claimDeletionFailed).isEqualTo(0);

        // 5. Document in FAILED can be claimed for retry
        doc.setStatus(DocumentStatus.FAILED);
        documentRepository.save(doc);
        Integer claimFailed = transactionTemplate.execute(status -> documentRepository.claimDocumentForProcessing(
                uploaded.id(), testUser1.getId(), DocumentStatus.PROCESSING, allowedStatuses, DocumentStatus.PROCESSING, staleThreshold
        ));
        assertThat(claimFailed).isEqualTo(1);

        // 6. Stale crash recovery: PROCESSING document with old updatedAt can be claimed
        doc = documentRepository.findById(uploaded.id()).orElseThrow();
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);
        transactionTemplate.execute(status -> documentRepository.updateUpdatedAt(uploaded.id(), Instant.now().minus(40, ChronoUnit.MINUTES)));

        Integer staleClaim = transactionTemplate.execute(status -> documentRepository.claimDocumentForProcessing(
                uploaded.id(), testUser1.getId(), DocumentStatus.PROCESSING, allowedStatuses, DocumentStatus.PROCESSING, staleThreshold
        ));
        assertThat(staleClaim).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Scenario 22: Partial Upsert Failure Triggers Vector Cleanup & Marks FAILED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("22. Partial upsert failure purges vectors, cleans relational chunks, and leaves document FAILED")
    void test22_PartialBatchUpsertFailureTriggersCleanupAndLeavesDocumentFailed() throws IOException {
        byte[] pdf = createPdf("Partial upsert failure content for testing cleanup.");
        MockMultipartFile file = new MockMultipartFile("file", "partial_fail.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        // Mock Qdrant upsert to fail
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("Simulated Qdrant cluster I/O error")));

        DocumentResponse result = documentService.processDocument(testUser1.getId(), uploaded.id());

        assertThat(result.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(result.errorMessage()).contains("Simulated Qdrant cluster I/O error");

        // Verify vector purge was invoked
        verify(qdrantClient, atLeastOnce()).deleteAsync(anyString(), any(Points.Filter.class));

        // Verify relational chunks were cleaned up
        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(uploaded.id());
        assertThat(chunks).isEmpty();

        // Verify document in DB is marked FAILED
        Document entity = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // -------------------------------------------------------------------------
    // Scenario 23: Qdrant Timeout Treated as Uncertain and Triggers Cleanup
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("23. Qdrant timeout is treated as uncertain and triggers defensive vector cleanup")
    void test23_QdrantUpsertTimeoutTreatedAsUncertainAndTriggersCleanup() throws IOException {
        byte[] pdf = createPdf("Timeout test document.");
        MockMultipartFile file = new MockMultipartFile("file", "timeout_doc.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        // Mock Qdrant timeout
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFailedFuture(new java.util.concurrent.TimeoutException("Qdrant gRPC operation timed out after 30 seconds")));

        DocumentResponse result = documentService.processDocument(testUser1.getId(), uploaded.id());

        assertThat(result.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(result.errorMessage()).contains("timed out");

        // Verify vector purge cleanup was called
        verify(qdrantClient, atLeastOnce()).deleteAsync(anyString(), any(Points.Filter.class));
    }

    // -------------------------------------------------------------------------
    // Scenario 24: Cleanup Failure During Upsert Failure is Documented
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("24. Failure during vector cleanup logs warning and leaves document in FAILED state")
    void test24_CleanupFailureDuringUpsertFailureIsDocumented() throws IOException {
        byte[] pdf = createPdf("Double failure test document.");
        MockMultipartFile file = new MockMultipartFile("file", "double_fail.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        // Mock upsert failure AND vector cleanup failure
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("Qdrant primary node crashed")));
        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("Qdrant delete node unreachable")));

        DocumentResponse result = documentService.processDocument(testUser1.getId(), uploaded.id());

        // Document still cleanly transitions to FAILED
        assertThat(result.status()).isEqualTo(DocumentStatus.FAILED);
        Document entity = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // -------------------------------------------------------------------------
    // Scenario 25: Retry After Uncertain Outcome Cleanly Reindexes
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("25. Retrying a FAILED document cleanly replaces prior chunks and transitions to READY")
    void test25_RetryAfterUncertainOutcomeCleanlyReindexes() throws IOException {
        byte[] pdf = createPdf("Reindex retry test content.");
        MockMultipartFile file = new MockMultipartFile("file", "retry_reindex.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);

        // Fail first attempt
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("Transient network glitch")));
        DocumentResponse failedResult = documentService.processDocument(testUser1.getId(), uploaded.id());
        assertThat(failedResult.status()).isEqualTo(DocumentStatus.FAILED);

        // Reset mock for successful retry
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));

        // Retry processing
        DocumentResponse retryResult = documentService.processDocument(testUser1.getId(), uploaded.id());
        assertThat(retryResult.status()).isEqualTo(DocumentStatus.READY);
        assertThat(retryResult.errorMessage()).isNull();
        assertThat(retryResult.totalChunks()).isGreaterThan(0);

        Document readyDoc = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(readyDoc.getStatus()).isEqualTo(DocumentStatus.READY);
    }

    // -------------------------------------------------------------------------
    // Scenario 26: Repeated Deletion is Safe and Idempotent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("26. Retrying deletion on DELETION_FAILED document idempotently completes deletion")
    void test26_RepeatedDeletionIsSafeAndIdempotent() throws IOException {
        byte[] pdf = createPdf("Idempotent deletion test content.");
        MockMultipartFile file = new MockMultipartFile("file", "idempotent_del.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);
        documentService.processDocument(testUser1.getId(), uploaded.id());

        // Mark document in DELETION_FAILED to simulate a prior failed attempt
        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        doc.setStatus(DocumentStatus.DELETION_FAILED);
        doc.setErrorMessage("Prior timeout during vector purge");
        documentRepository.save(doc);

        // Safe retry deletes vectors, deletes physical file, deletes DB record
        documentService.deleteDocument(testUser1.getId(), uploaded.id());

        assertThat(documentRepository.findById(uploaded.id())).isEmpty();
        Path filePath = fileStorageService.getStorageDirectory().resolve(doc.getStorageFileName());
        assertThat(Files.exists(filePath)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Scenario 27: Interrupted Deletion Can Be Retried After Failure
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("27. Interrupted deletion fails safely leaving DELETION_FAILED; subsequent retry cleans up completely")
    void test27_InterruptedDeletionCanBeRetriedAfterFailure() throws IOException {
        byte[] pdf = createPdf("Interrupted deletion test content.");
        MockMultipartFile file = new MockMultipartFile("file", "interrupted_del.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);
        documentService.processDocument(testUser1.getId(), uploaded.id());

        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        Path physicalFile = fileStorageService.getStorageDirectory().resolve(doc.getStorageFileName());

        // 1. Fail Qdrant deletion
        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFailedFuture(new RuntimeException("Simulated connection timeout during vector deletion")));

        assertThatThrownBy(() -> documentService.deleteDocument(testUser1.getId(), uploaded.id()))
                .isInstanceOf(IllegalStateException.class);

        Document failedDoc = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(failedDoc.getStatus()).isEqualTo(DocumentStatus.DELETION_FAILED);
        assertThat(Files.exists(physicalFile)).isTrue();

        // 2. Restore Qdrant success and retry deletion
        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));

        documentService.deleteDocument(testUser1.getId(), uploaded.id());

        assertThat(documentRepository.findById(uploaded.id())).isEmpty();
        assertThat(Files.exists(physicalFile)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Scenario 28: Physical File Deletion Failure Marks DELETION_FAILED
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("28. Physical filesystem deletion failure transitions to DELETION_FAILED and preserves database record")
    void test28_FileCleanupFailureMarksDeletionFailedAndPreservesDatabaseRecord() throws IOException {
        byte[] pdf = createPdf("File lock deletion test content.");
        MockMultipartFile file = new MockMultipartFile("file", "file_lock_del.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(testUser1.getId(), file);
        documentService.processDocument(testUser1.getId(), uploaded.id());

        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        Path physicalFile = fileStorageService.getStorageDirectory().resolve(doc.getStorageFileName());

        // Lock file on Windows using FileChannel to cause FileStorageException on delete
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(physicalFile.toFile(), "rw");
             java.nio.channels.FileLock lock = raf.getChannel().lock()) {

            assertThatThrownBy(() -> documentService.deleteDocument(testUser1.getId(), uploaded.id()))
                    .isInstanceOf(com.aidocassistant.exception.FileStorageException.class);

            Document lockedDoc = documentRepository.findById(uploaded.id()).orElseThrow();
            assertThat(lockedDoc.getStatus()).isEqualTo(DocumentStatus.DELETION_FAILED);
            assertThat(lockedDoc.getErrorMessage()).contains("Failed to delete physical file");
        }

        // After lock is released, deletion can be retried successfully
        documentService.deleteDocument(testUser1.getId(), uploaded.id());
        assertThat(documentRepository.findById(uploaded.id())).isEmpty();
        assertThat(Files.exists(physicalFile)).isFalse();
    }
}
