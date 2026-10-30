package com.aidocassistant.service;

import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.exception.InvalidFileException;
import com.aidocassistant.exception.ResourceNotFoundException;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentChunk;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.DocumentChunkRepository;
import com.aidocassistant.repository.DocumentRepository;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.JwtTokenProvider;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class PdfProcessingAndChunkingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PdfProcessingService pdfProcessingService;

    @Autowired
    private DocumentChunker documentChunker;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private FileStorageService fileStorageService;

    @MockBean
    private EmbeddingModel embeddingModel;

    @MockBean
    private QdrantClient qdrantClient;

    private User user1;
    private String token1;

    private User user2;
    private String token2;

    @BeforeEach
    void setUp() throws IOException {
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

        user1 = new User();
        user1.setId(UUID.randomUUID());
        user1.setEmail("alice@example.com");
        user1.setPasswordHash(passwordEncoder.encode("Password123!"));
        user1.setDisplayName("Alice User");
        user1.setRole(UserRole.ROLE_USER);
        user1.setEnabled(true);
        user1 = userRepository.save(user1);
        token1 = tokenProvider.generateToken(user1.getId(), user1.getEmail(), user1.getRole().name());

        user2 = new User();
        user2.setId(UUID.randomUUID());
        user2.setEmail("bob@example.com");
        user2.setPasswordHash(passwordEncoder.encode("Password123!"));
        user2.setDisplayName("Bob User");
        user2.setRole(UserRole.ROLE_USER);
        user2.setEnabled(true);
        user2 = userRepository.save(user2);
        token2 = tokenProvider.generateToken(user2.getId(), user2.getEmail(), user2.getRole().name());

        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> list = invocation.getArgument(0);
            return list.stream().map(s -> new float[768]).toList();
        });
        when(qdrantClient.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));
        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.newBuilder().setStatus(Points.UpdateStatus.Completed).build()));
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

    private byte[] createEncryptedPdf(String content, String password) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(content);
                cs.endText();
            }
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy(password, password, ap);
            doc.protect(spp);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("1. Extraction of text from a valid single-page PDF")
    void testSinglePageTextExtraction() throws IOException {
        byte[] pdf = createPdf("Hello World from Single Page PDF.");
        List<PdfProcessingService.PageText> pages = pdfProcessingService.extractPagesFromBytes(pdf);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(0).text()).contains("Hello World from Single Page PDF.");
    }

    @Test
    @DisplayName("2. Extraction of text from a multi-page PDF with correct 1-based page numbers")
    void testMultiPageTextExtraction() throws IOException {
        byte[] pdf = createPdf("First Page Content", "Second Page Content", "Third Page Content");
        List<PdfProcessingService.PageText> pages = pdfProcessingService.extractPagesFromBytes(pdf);

        assertThat(pages).hasSize(3);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(0).text()).contains("First Page Content");

        assertThat(pages.get(1).pageNumber()).isEqualTo(2);
        assertThat(pages.get(1).text()).contains("Second Page Content");

        assertThat(pages.get(2).pageNumber()).isEqualTo(3);
        assertThat(pages.get(2).text()).contains("Third Page Content");
    }

    @Test
    @DisplayName("3. Handling of a blank page within a document")
    void testBlankPageHandling() throws IOException {
        byte[] pdf = createPdf("Page 1 Text", "", "Page 3 Text");
        List<PdfProcessingService.PageText> pages = pdfProcessingService.extractPagesFromBytes(pdf);

        assertThat(pages).hasSize(3);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(0).hasText()).isTrue();

        assertThat(pages.get(1).pageNumber()).isEqualTo(2);
        assertThat(pages.get(1).hasText()).isFalse();
        assertThat(pages.get(1).text()).isEmpty();

        assertThat(pages.get(2).pageNumber()).isEqualTo(3);
        assertThat(pages.get(2).hasText()).isTrue();
    }

    @Test
    @DisplayName("4. Rejection or safe failure of a corrupted PDF")
    void testCorruptedPdfSafeFailure() {
        byte[] corruptBytes = "%PDF-1.4\nCorrupted content completely broken".getBytes();
        assertThatThrownBy(() -> pdfProcessingService.extractPagesFromBytes(corruptBytes))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Malformed or unreadable PDF");
    }

    @Test
    @DisplayName("5. Handling of an encrypted PDF according to documented security policy")
    void testEncryptedPdfRejection() throws IOException {
        byte[] encryptedPdf = createEncryptedPdf("Secret confidential text", "MySecretPassword");
        assertThatThrownBy(() -> pdfProcessingService.extractPagesFromBytes(encryptedPdf))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Encrypted or password-protected PDF files cannot be processed");
    }

    @Test
    @DisplayName("6. Chunking of a short text without creating empty chunks")
    void testShortTextChunking() {
        PdfProcessingService.PageText page = new PdfProcessingService.PageText(1, "A brief summary of quarterly earnings.");
        List<DocumentChunker.RawChunk> chunks = documentChunker.chunkPages(List.of(page));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
        assertThat(chunks.get(0).text()).isEqualTo("A brief summary of quarterly earnings.");
        assertThat(chunks.get(0).tokenCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("7. Chunking of long text with sliding-window overlap")
    void testLongTextChunkingWithOverlap() {
        // DocumentChunker configured with small chunk size and overlap for testing
        DocumentChunker smallChunker = new DocumentChunker(30, 10);

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("Sentence ").append(i).append(" presents important corporate data. ");
        }

        PdfProcessingService.PageText page = new PdfProcessingService.PageText(1, sb.toString());
        List<DocumentChunker.RawChunk> chunks = smallChunker.chunkPages(List.of(page));

        assertThat(chunks.size()).isGreaterThan(1);
        // Verify no empty chunks
        for (DocumentChunker.RawChunk chunk : chunks) {
            assertThat(chunk.text()).isNotBlank();
            assertThat(chunk.pageNumber()).isEqualTo(1);
        }
        // Verify overlap: end of chunk 0 overlaps with start of chunk 1
        assertThat(chunks.get(0).text()).contains("Sentence 1");
    }

    @Test
    @DisplayName("8. Stable chunk indexes and page metadata across multi-page input")
    void testStableChunkIndexesAcrossPages() {
        List<PdfProcessingService.PageText> pages = List.of(
                new PdfProcessingService.PageText(1, "Page 1 Content."),
                new PdfProcessingService.PageText(2, ""), // Blank page
                new PdfProcessingService.PageText(3, "Page 3 Content.")
        );

        List<DocumentChunker.RawChunk> chunks = documentChunker.chunkPages(pages);
        assertThat(chunks).hasSize(2);

        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
        assertThat(chunks.get(0).text()).isEqualTo("Page 1 Content.");

        assertThat(chunks.get(1).chunkIndex()).isEqualTo(1);
        assertThat(chunks.get(1).pageNumber()).isEqualTo(3);
        assertThat(chunks.get(1).text()).isEqualTo("Page 3 Content.");
    }

    @Test
    @DisplayName("9. Persistence of chunks and correct ordered retrieval from MySQL")
    void testPersistenceOfChunksAndOrdering() throws Exception {
        byte[] pdf = createPdf("Executive summary paragraph on page 1.", "Detailed analysis on page 2.");
        MockMultipartFile file = new MockMultipartFile("file", "annual_report.pdf", "application/pdf", pdf);

        DocumentResponse uploaded = documentService.uploadDocument(user1.getId(), file);

        // Process document
        DocumentResponse processed = documentService.processDocument(user1.getId(), uploaded.id());
        assertThat(processed.status()).isIn(DocumentStatus.READY, DocumentStatus.PROCESSING);

        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(uploaded.id());
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getChunkIndex()).isEqualTo(0);
        assertThat(chunks.get(0).getPageNumber()).isEqualTo(1);
        assertThat(chunks.get(0).getChunkText()).contains("Executive summary");

        assertThat(chunks.get(1).getChunkIndex()).isEqualTo(1);
        assertThat(chunks.get(1).getPageNumber()).isEqualTo(2);
        assertThat(chunks.get(1).getChunkText()).contains("Detailed analysis");
    }

    @Test
    @DisplayName("10. Updating total page and chunk counts on Document entity")
    void testUpdatingTotalPageAndChunkCounts() throws Exception {
        byte[] pdf = createPdf("Page 1", "Page 2", "Page 3");
        MockMultipartFile file = new MockMultipartFile("file", "three_pages.pdf", "application/pdf", pdf);

        DocumentResponse uploaded = documentService.uploadDocument(user1.getId(), file);
        DocumentResponse processed = documentService.processDocument(user1.getId(), uploaded.id());

        assertThat(processed.totalPages()).isEqualTo(3);
        assertThat(processed.totalChunks()).isEqualTo(3);

        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(doc.getTotalPages()).isEqualTo(3);
        assertThat(doc.getTotalChunks()).isEqualTo(3);
    }

    @Test
    @DisplayName("11. Failed processing does not leave misleading partial chunks")
    void testFailedProcessingRollsBackPartialChunks() throws Exception {
        byte[] pdf = createPdf("Valid page 1 text.");
        MockMultipartFile file = new MockMultipartFile("file", "fail_test.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(user1.getId(), file);

        // Corrupt physical file on disk to trigger failure during processing
        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        Path physicalPath = fileStorageService.getStorageDirectory().resolve(doc.getStorageFileName());
        Files.write(physicalPath, "corrupted non-pdf bytes".getBytes());

        // Processing safely captures failure, sets status to FAILED, and records error message
        DocumentResponse result = documentService.processDocument(user1.getId(), uploaded.id());
        assertThat(result.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(result.errorMessage()).contains("Malformed or unreadable PDF");

        Document failedDoc = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(failedDoc.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failedDoc.getErrorMessage()).isNotNull();

        // Verify no orphaned chunks exist
        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(uploaded.id());
        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("12. Reprocessing a document replaces chunks cleanly without creating duplicates")
    void testReprocessingDoesNotCreateDuplicateChunks() throws Exception {
        byte[] pdf = createPdf("Original page 1 text.", "Original page 2 text.");
        MockMultipartFile file = new MockMultipartFile("file", "retry_doc.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(user1.getId(), file);

        // First processing run
        documentService.processDocument(user1.getId(), uploaded.id());
        assertThat(documentChunkRepository.countByDocumentId(uploaded.id())).isEqualTo(2);

        // Second processing run (retry)
        documentService.processDocument(user1.getId(), uploaded.id());
        assertThat(documentChunkRepository.countByDocumentId(uploaded.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("13. A user cannot process another user's document (returns 404 without leaking existence)")
    void testUserCannotProcessAnotherUserDocument() throws Exception {
        byte[] pdf = createPdf("Bob's secret document.");
        MockMultipartFile file = new MockMultipartFile("file", "bob_doc.pdf", "application/pdf", pdf);
        DocumentResponse bobsDoc = documentService.uploadDocument(user2.getId(), file);

        // Alice attempts to trigger processing on Bob's document
        mockMvc.perform(post("/api/documents/" + bobsDoc.id() + "/process")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // Alice attempts to inspect Bob's chunks
        mockMvc.perform(get("/api/documents/" + bobsDoc.id() + "/chunks")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("14. A document in a deletion state cannot be processed")
    void testDocumentInDeletionStateCannotBeProcessed() throws Exception {
        byte[] pdf = createPdf("Document marked for deletion.");
        MockMultipartFile file = new MockMultipartFile("file", "deleting.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(user1.getId(), file);

        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        doc.setStatus(DocumentStatus.DELETING);
        documentRepository.save(doc);

        assertThatThrownBy(() -> documentService.processDocument(user1.getId(), uploaded.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot process document in deletion state");
    }

    @Test
    @DisplayName("15. Successful processing and indexing sets status to READY")
    void testSuccessfulExtractionLeavesStatusProcessing() throws Exception {
        byte[] pdf = createPdf("Sample text for RAG indexing.");
        MockMultipartFile file = new MockMultipartFile("file", "rag_doc.pdf", "application/pdf", pdf);
        DocumentResponse uploaded = documentService.uploadDocument(user1.getId(), file);

        mockMvc.perform(post("/api/documents/" + uploaded.id() + "/process")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.totalChunks").value(1));

        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.READY);
    }
}
