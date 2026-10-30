package com.aidocassistant.controller;

import com.aidocassistant.dto.BulkDeleteRequest;
import com.aidocassistant.dto.BulkDeleteResponse;
import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.exception.FileStorageException;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.DocumentRepository;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.JwtTokenProvider;
import com.aidocassistant.service.DocumentChunker;
import com.aidocassistant.service.DocumentService;
import com.aidocassistant.service.EmbeddingService;
import com.aidocassistant.service.FileStorageService;
import com.aidocassistant.service.PdfProcessingService;
import com.aidocassistant.service.VectorStoreService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private FileStorageService fileStorageService;

    private User user1;
    private String token1;

    private User user2;
    private String token2;

    @BeforeEach
    void setUp() throws IOException {
        documentRepository.deleteAll();
        userRepository.deleteAll();

        // Clean up test storage directory
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
    }

    private byte[] createSamplePdf(String content) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText(content);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("1. Successful PDF upload stores file, creates record in PENDING status, and returns 201 Created")
    void testSuccessfulPdfUpload() throws Exception {
        byte[] pdfBytes = createSamplePdf("Architecture Specification content for testing.");
        MockMultipartFile file = new MockMultipartFile("file", "spec.pdf", "application/pdf", pdfBytes);

        MvcResult result = mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.originalFileName").value("spec.pdf"))
                .andExpect(jsonPath("$.fileSizeBytes").value(pdfBytes.length))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.totalChunks").isEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();

        DocumentResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), DocumentResponse.class);
        Document savedDoc = documentRepository.findById(response.id()).orElseThrow();
        assertThat(savedDoc.getUser().getId()).isEqualTo(user1.getId());
        assertThat(savedDoc.getStatus()).isEqualTo(DocumentStatus.PENDING);

        // Verify physical file exists on disk with UUID name
        Path physicalFile = fileStorageService.getStorageDirectory().resolve(savedDoc.getStorageFileName());
        assertThat(Files.exists(physicalFile)).isTrue();
        assertThat(Files.size(physicalFile)).isEqualTo(pdfBytes.length);
    }

    @Test
    @DisplayName("2. Invalid file type rejection (non-PDF rejected with 400 Bad Request)")
    void testInvalidFileTypeRejection() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "document.txt", "text/plain", "Simple text content".getBytes());

        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Only PDF files are supported"));
    }

    @Test
    @DisplayName("3. Empty or malformed PDF rejection (0-byte and corrupt files rejected with 400 Bad Request)")
    void testEmptyOrMalformedPdfRejection() throws Exception {
        // 0-byte file
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        mockMvc.perform(multipart("/api/documents")
                        .file(emptyFile)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Uploaded file is empty or missing"));

        // File missing %PDF- header signature
        MockMultipartFile corruptHeaderFile = new MockMultipartFile("file", "corrupt.pdf", "application/pdf", "Not a real PDF file".getBytes());
        mockMvc.perform(multipart("/api/documents")
                        .file(corruptHeaderFile)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid PDF file: missing %PDF- header signature"));

        // File starting with %PDF- but having corrupt body
        byte[] fakePdfBytes = "%PDF-1.4\nCorrupt bytes that fail PDFBox parsing".getBytes();
        MockMultipartFile corruptBodyFile = new MockMultipartFile("file", "broken.pdf", "application/pdf", fakePdfBytes);
        mockMvc.perform(multipart("/api/documents")
                        .file(corruptBodyFile)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Malformed or unreadable PDF")));
    }

    @Test
    @DisplayName("4. Oversized file rejection (file exceeding max permitted bytes rejected)")
    void testOversizedFileRejection() {
        // Configure a small storage service instance with 100 bytes limit to verify rejection logic
        FileStorageService limitedStorage = new FileStorageService(fileStorageService.getStorageDirectory(), 100);
        byte[] largeBytes = new byte[200];
        MockMultipartFile oversizedFile = new MockMultipartFile("file", "large.pdf", "application/pdf", largeBytes);

        assertThatThrownBy(() -> limitedStorage.validateFile(oversizedFile, largeBytes))
                .isInstanceOf(com.aidocassistant.exception.InvalidFileException.class)
                .hasMessageContaining("File size exceeds maximum permitted limit");
    }

    @Test
    @DisplayName("5. Duplicate upload handling (same user rejected with 409, different user allowed)")
    void testDuplicateUploadHandling() throws Exception {
        byte[] pdfBytes = createSamplePdf("Duplicate detection test content");
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes);

        // 1. User 1 uploads -> 201 Created
        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        // 2. User 1 uploads identical file again -> 409 Conflict
        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("identical content has already been uploaded by this user")));

        // 3. User 2 uploads the identical file -> 201 Created (isolated per-user ownership)
        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isCreated());

        assertThat(documentRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("6. Listing returns only the authenticated user's documents")
    void testListingReturnsOnlyAuthenticatedUserDocuments() throws Exception {
        byte[] pdf1 = createSamplePdf("Document for Alice");
        byte[] pdf2 = createSamplePdf("Document for Bob");

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "alice.pdf", "application/pdf", pdf1))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "bob.pdf", "application/pdf", pdf2))
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isCreated());

        // Alice lists her documents
        MvcResult aliceResult = mockMvc.perform(get("/api/documents")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andReturn();

        List<DocumentResponse> aliceDocs = objectMapper.readValue(
                aliceResult.getResponse().getContentAsString(),
                new TypeReference<>() {}
        );
        assertThat(aliceDocs).hasSize(1);
        assertThat(aliceDocs.get(0).originalFileName()).isEqualTo("alice.pdf");

        // Bob lists his documents
        MvcResult bobResult = mockMvc.perform(get("/api/documents")
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isOk())
                .andReturn();

        List<DocumentResponse> bobDocs = objectMapper.readValue(
                bobResult.getResponse().getContentAsString(),
                new TypeReference<>() {}
        );
        assertThat(bobDocs).hasSize(1);
        assertThat(bobDocs.get(0).originalFileName()).isEqualTo("bob.pdf");
    }

    @Test
    @DisplayName("7. Metadata lookup for another user's document is denied without leaking its existence (404 Not Found)")
    void testMetadataLookupForAnotherUserDocumentDeniedWithoutLeaking() throws Exception {
        byte[] pdf2 = createSamplePdf("Bob Private Document");
        MvcResult bobUpload = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "bob_private.pdf", "application/pdf", pdf2))
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse bobDoc = objectMapper.readValue(bobUpload.getResponse().getContentAsString(), DocumentResponse.class);

        // Alice attempts to access Bob's document metadata
        mockMvc.perform(get("/api/documents/" + bobDoc.id())
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Document not found with ID")));
    }

    @Test
    @DisplayName("8. Anonymous requests to document endpoints are rejected with 401 Unauthorized")
    void testAnonymousRequestsRejected() throws Exception {
        UUID docId = UUID.randomUUID();

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[10])))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/documents/" + docId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/documents/" + docId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("9. Successful deletion removes the owned file on disk and database metadata")
    void testSuccessfulDeletionRemovesFileAndMetadata() throws Exception {
        byte[] pdf = createSamplePdf("Document to be deleted");
        MvcResult uploadResult = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "to_delete.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse doc = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), DocumentResponse.class);
        Document savedDoc = documentRepository.findById(doc.id()).orElseThrow();
        Path physicalFile = fileStorageService.getStorageDirectory().resolve(savedDoc.getStorageFileName());
        assertThat(Files.exists(physicalFile)).isTrue();

        // Alice deletes her document
        mockMvc.perform(delete("/api/documents/" + doc.id())
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Document deleted successfully"))
                .andExpect(jsonPath("$.id").value(doc.id().toString()));

        // Verify relational record is removed
        assertThat(documentRepository.findById(doc.id())).isEmpty();

        // Verify physical file on disk is removed
        assertThat(Files.exists(physicalFile)).isFalse();
    }

    @Test
    @DisplayName("10. A user cannot delete another user's document (returns 404 and preserves file and metadata)")
    void testUserCannotDeleteAnotherUserDocument() throws Exception {
        byte[] pdf = createSamplePdf("Bob's document that Alice wants to delete");
        MvcResult uploadResult = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "bobs_file.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isCreated())
                .andReturn();

        DocumentResponse bobDoc = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), DocumentResponse.class);
        Document savedDoc = documentRepository.findById(bobDoc.id()).orElseThrow();
        Path physicalFile = fileStorageService.getStorageDirectory().resolve(savedDoc.getStorageFileName());
        assertThat(Files.exists(physicalFile)).isTrue();

        // Alice attempts to delete Bob's document
        mockMvc.perform(delete("/api/documents/" + bobDoc.id())
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // Verify Bob's document in DB and physical file on disk remain intact
        assertThat(documentRepository.findById(bobDoc.id())).isPresent();
        assertThat(Files.exists(physicalFile)).isTrue();
    }

    @Test
    @DisplayName("11. Filesystem failure handling does not report false success and preserves database record")
    void testFilesystemFailureHandlingDoesNotReportFalseSuccess() {
        DocumentRepository mockDocRepo = mock(DocumentRepository.class);
        UserRepository mockUserRepo = mock(UserRepository.class);
        FileStorageService mockStorage = mock(FileStorageService.class);
        com.aidocassistant.repository.DocumentChunkRepository mockChunkRepo = mock(com.aidocassistant.repository.DocumentChunkRepository.class);
        PdfProcessingService mockPdfProc = mock(PdfProcessingService.class);
        DocumentChunker mockChunker = mock(DocumentChunker.class);
        EmbeddingService mockEmbedding = mock(EmbeddingService.class);
        VectorStoreService mockVectorStore = mock(VectorStoreService.class);

        DocumentService service = new DocumentService(mockDocRepo, mockUserRepo, mockStorage, mockChunkRepo, mockPdfProc, mockChunker, mockEmbedding, mockVectorStore);

        UUID docId = UUID.randomUUID();
        UUID userId = user1.getId();
        Document doc = new Document(docId, user1, "test.pdf", "storage-uuid.pdf", "hash123", 1024);

        when(mockDocRepo.findByIdAndUserId(docId, userId)).thenReturn(java.util.Optional.of(doc));
        when(mockDocRepo.saveAndFlush(any(Document.class))).thenAnswer(i -> i.getArgument(0));
        doThrow(new FileStorageException("Disk I/O error")).when(mockStorage).deleteFile("storage-uuid.pdf");

        assertThatThrownBy(() -> service.deleteDocument(userId, docId))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("Disk I/O error");

        // Verify database delete was NEVER called
        org.mockito.Mockito.verify(mockDocRepo, org.mockito.Mockito.never()).delete(any(Document.class));
    }

    @Test
    @DisplayName("12. Storage cleanup after persistence failure (physical file cleaned up if DB save fails)")
    void testStorageCleanupAfterPersistenceFailure() {
        DocumentRepository mockDocRepo = mock(DocumentRepository.class);
        UserRepository mockUserRepo = mock(UserRepository.class);
        FileStorageService mockStorage = mock(FileStorageService.class);
        com.aidocassistant.repository.DocumentChunkRepository mockChunkRepo = mock(com.aidocassistant.repository.DocumentChunkRepository.class);
        PdfProcessingService mockPdfProc = mock(PdfProcessingService.class);
        DocumentChunker mockChunker = mock(DocumentChunker.class);
        EmbeddingService mockEmbedding = mock(EmbeddingService.class);
        VectorStoreService mockVectorStore = mock(VectorStoreService.class);

        DocumentService service = new DocumentService(mockDocRepo, mockUserRepo, mockStorage, mockChunkRepo, mockPdfProc, mockChunker, mockEmbedding, mockVectorStore);

        UUID userId = user1.getId();
        when(mockUserRepo.findById(userId)).thenReturn(java.util.Optional.of(user1));
        when(mockStorage.extractPageCount(any())).thenReturn(1);
        when(mockStorage.calculateSha256(any())).thenReturn("abc123hash");
        when(mockDocRepo.findByUserIdAndContentHash(userId, "abc123hash")).thenReturn(java.util.Optional.empty());
        when(mockStorage.storeFile(any())).thenReturn("stored-uuid-file.pdf");

        // Simulate database failure during document save
        when(mockDocRepo.save(any(Document.class))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("Simulated DB error"));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "%PDF-1.4 sample".getBytes());

        assertThatThrownBy(() -> service.uploadDocument(userId, file))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // Verify that cleanupFileQuietly was invoked with the stored file name
        org.mockito.Mockito.verify(mockStorage).cleanupFileQuietly("stored-uuid-file.pdf");
    }

    @Test
    @DisplayName("13. POST /api/documents/{id}/retry-delete retries deletion on document in DELETION_FAILED status")
    void testRetryDeleteEndpointSuccessfullyRetriesFailedDeletion() throws Exception {
        byte[] pdf = createSamplePdf("Content for retry-delete test");
        MvcResult uploadResult = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "retry_test.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse uploaded = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), DocumentResponse.class);

        Document doc = documentRepository.findById(uploaded.id()).orElseThrow();
        doc.setStatus(DocumentStatus.DELETION_FAILED);
        doc.setErrorMessage("Prior network interruption");
        documentRepository.save(doc);

        mockMvc.perform(post("/api/documents/" + uploaded.id() + "/retry-delete")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Document deletion retried successfully"))
                .andExpect(jsonPath("$.id").value(uploaded.id().toString()));

        // Document should be removed from database
        assertThat(documentRepository.findById(uploaded.id())).isEmpty();
    }

    @Test
    @DisplayName("14. POST /api/documents/{id}/retry-delete rejects access for non-owning user (returns 404)")
    void testRetryDeleteEndpointRejectsNonOwner() throws Exception {
        byte[] pdf = createSamplePdf("Content owned by user1");
        MvcResult uploadResult = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "user1_doc.pdf", "application/pdf", pdf))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse uploaded = objectMapper.readValue(uploadResult.getResponse().getContentAsString(), DocumentResponse.class);

        // User2 attempts to retry delete User1's document
        mockMvc.perform(post("/api/documents/" + uploaded.id() + "/retry-delete")
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        // Document should still exist
        assertThat(documentRepository.findById(uploaded.id())).isPresent();
    }

    @Test
    @DisplayName("15. GET /api/documents with search query filters by original filename case-insensitively")
    void testListDocumentsWithSearchFilter() throws Exception {
        byte[] pdf1 = createSamplePdf("Alpha document content");
        byte[] pdf2 = createSamplePdf("Beta document content");
        byte[] pdf3 = createSamplePdf("Alpha secondary content");

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "alpha_notes.pdf", "application/pdf", pdf1))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "beta_report.pdf", "application/pdf", pdf2))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "alpha_summary.pdf", "application/pdf", pdf3))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(get("/api/documents?search=ALPHA")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andReturn();

        List<DocumentResponse> docs = objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<List<DocumentResponse>>() {});
        assertThat(docs).hasSize(2);
        assertThat(docs).allMatch(d -> d.originalFileName().toLowerCase().contains("alpha"));
    }

    @Test
    @DisplayName("16. GET /api/documents with status filter returns only matching documents")
    void testListDocumentsWithStatusFilter() throws Exception {
        byte[] pdf1 = createSamplePdf("Ready doc");
        byte[] pdf2 = createSamplePdf("Failed doc");

        MvcResult upload1 = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "ready.pdf", "application/pdf", pdf1))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse doc1 = objectMapper.readValue(upload1.getResponse().getContentAsString(), DocumentResponse.class);

        MvcResult upload2 = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "failed.pdf", "application/pdf", pdf2))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse doc2 = objectMapper.readValue(upload2.getResponse().getContentAsString(), DocumentResponse.class);

        Document d1 = documentRepository.findById(doc1.id()).orElseThrow();
        d1.setStatus(DocumentStatus.READY);
        documentRepository.save(d1);

        Document d2 = documentRepository.findById(doc2.id()).orElseThrow();
        d2.setStatus(DocumentStatus.FAILED);
        documentRepository.save(d2);

        MvcResult result = mockMvc.perform(get("/api/documents?status=READY")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andReturn();

        List<DocumentResponse> docs = objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<List<DocumentResponse>>() {});
        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().originalFileName()).isEqualTo("ready.pdf");
    }

    @Test
    @DisplayName("17. GET /api/documents supports sorting and server-side pagination headers")
    void testListDocumentsWithSortingAndPagination() throws Exception {
        byte[] pdf1 = createSamplePdf("Doc A");
        byte[] pdf2 = createSamplePdf("Doc B");
        byte[] pdf3 = createSamplePdf("Doc C");

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "c_doc.pdf", "application/pdf", pdf1))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "a_doc.pdf", "application/pdf", pdf2))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "b_doc.pdf", "application/pdf", pdf3))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated());

        // Sort by originalFileName ASC, page 0, size 2
        MvcResult pageResult = mockMvc.perform(get("/api/documents?sortBy=originalFileName&sortDirection=asc&page=0&size=2")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(header().string("X-Total-Pages", "2"))
                .andExpect(header().string("X-Current-Page", "0"))
                .andExpect(header().string("X-Page-Size", "2"))
                .andReturn();

        List<DocumentResponse> pagedDocs = objectMapper.readValue(pageResult.getResponse().getContentAsString(), new TypeReference<List<DocumentResponse>>() {});
        assertThat(pagedDocs).hasSize(2);
        assertThat(pagedDocs.get(0).originalFileName()).isEqualTo("a_doc.pdf");
        assertThat(pagedDocs.get(1).originalFileName()).isEqualTo("b_doc.pdf");
    }

    @Test
    @DisplayName("18. POST /api/documents/bulk-delete deletes multiple owned documents successfully")
    void testBulkDeleteSuccess() throws Exception {
        byte[] pdf1 = createSamplePdf("Doc 1 content");
        byte[] pdf2 = createSamplePdf("Doc 2 content");

        MvcResult upload1 = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "doc1.pdf", "application/pdf", pdf1))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse doc1 = objectMapper.readValue(upload1.getResponse().getContentAsString(), DocumentResponse.class);

        MvcResult upload2 = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "doc2.pdf", "application/pdf", pdf2))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse doc2 = objectMapper.readValue(upload2.getResponse().getContentAsString(), DocumentResponse.class);

        BulkDeleteRequest request = new BulkDeleteRequest(List.of(doc1.id(), doc2.id()));

        mockMvc.perform(post("/api/documents/bulk-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2))
                .andExpect(jsonPath("$.deletedIds.length()").value(2))
                .andExpect(jsonPath("$.failedIds.length()").value(0));

        assertThat(documentRepository.findById(doc1.id())).isEmpty();
        assertThat(documentRepository.findById(doc2.id())).isEmpty();
    }

    @Test
    @DisplayName("19. POST /api/documents/bulk-delete enforces ownership and skips documents owned by other users")
    void testBulkDeleteEnforcesOwnership() throws Exception {
        byte[] pdf1 = createSamplePdf("Alice content");
        byte[] pdf2 = createSamplePdf("Bob content");

        MvcResult upload1 = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "alice.pdf", "application/pdf", pdf1))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse aliceDoc = objectMapper.readValue(upload1.getResponse().getContentAsString(), DocumentResponse.class);

        MvcResult upload2 = mockMvc.perform(multipart("/api/documents")
                        .file(new MockMultipartFile("file", "bob.pdf", "application/pdf", pdf2))
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isCreated())
                .andReturn();
        DocumentResponse bobDoc = objectMapper.readValue(upload2.getResponse().getContentAsString(), DocumentResponse.class);

        // Alice attempts to bulk delete both her doc and Bob's doc
        BulkDeleteRequest request = new BulkDeleteRequest(List.of(aliceDoc.id(), bobDoc.id()));

        mockMvc.perform(post("/api/documents/bulk-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(1))
                .andExpect(jsonPath("$.deletedIds[0]").value(aliceDoc.id().toString()))
                .andExpect(jsonPath("$.failedIds[0]").value(bobDoc.id().toString()));

        // Alice's doc is deleted
        assertThat(documentRepository.findById(aliceDoc.id())).isEmpty();
        // Bob's doc remains untouched!
        assertThat(documentRepository.findById(bobDoc.id())).isPresent();
    }
}
