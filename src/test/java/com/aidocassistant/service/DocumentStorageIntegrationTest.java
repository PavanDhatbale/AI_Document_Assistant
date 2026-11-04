package com.aidocassistant.service;

import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.exception.ResourceNotFoundException;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.User;
import com.aidocassistant.repository.DocumentChunkRepository;
import com.aidocassistant.repository.DocumentRepository;
import com.aidocassistant.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("auth-test")
class DocumentStorageIntegrationTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @MockitoBean
    private StorageService mockStorageService;

    @MockitoBean
    private EmbeddingService mockEmbeddingService;

    @MockitoBean
    private VectorStoreService mockVectorStoreService;

    @Autowired
    private DocumentService documentService;

    private User testUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(new User(UUID.randomUUID(), "storage-test-" + UUID.randomUUID() + "@example.com", "hash", "Storage User"));
        otherUser = userRepository.save(new User(UUID.randomUUID(), "other-" + UUID.randomUUID() + "@example.com", "hash", "Other User"));
    }

    private byte[] createTestPdfWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("Document upload delegates to StorageService and persists user-scoped storage key")
    void uploadDocument_usesStorageService() throws Exception {
        byte[] pdfBytes = createTestPdfWithText("Storage Abstraction Test Content");
        String assignedKey = testUser.getId() + "/" + UUID.randomUUID() + ".pdf";

        when(mockStorageService.extractPageCount(any())).thenReturn(1);
        when(mockStorageService.calculateSha256(any())).thenReturn("unique-hash-" + UUID.randomUUID());
        when(mockStorageService.storeFile(eq(testUser.getId()), any())).thenReturn(assignedKey);

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes);
        DocumentResponse response = documentService.uploadDocument(testUser.getId(), file);

        assertThat(response.id()).isNotNull();
        assertThat(response.originalFileName()).isEqualTo("doc.pdf");
        assertThat(response.status()).isEqualTo(DocumentStatus.PENDING);

        Document savedDoc = documentRepository.findById(response.id()).orElseThrow();
        assertThat(savedDoc.getStorageFileName()).isEqualTo(assignedKey);

        verify(mockStorageService).storeFile(eq(testUser.getId()), any());
    }

    @Test
    @DisplayName("Process/retry-process document loads bytes via StorageService and transitions to READY")
    void processDocument_loadsViaStorageService() throws Exception {
        byte[] pdfBytes = createTestPdfWithText("Grounded content for vector processing");
        String assignedKey = testUser.getId() + "/" + UUID.randomUUID() + ".pdf";

        Document document = new Document(UUID.randomUUID(), testUser, "process-test.pdf", assignedKey, "hash-" + UUID.randomUUID(), pdfBytes.length);
        document.setStatus(DocumentStatus.FAILED);
        document = documentRepository.save(document);

        when(mockStorageService.loadFile(assignedKey)).thenReturn(pdfBytes);
        when(mockEmbeddingService.generateEmbeddings(anyList())).thenReturn(List.of(new float[768]));

        DocumentResponse processed = documentService.processDocument(testUser.getId(), document.getId());

        assertThat(processed.status()).isEqualTo(DocumentStatus.READY);
        assertThat(processed.totalChunks()).isGreaterThan(0);
        verify(mockStorageService).loadFile(assignedKey);
    }

    @Test
    @DisplayName("Document deletion calls StorageService deleteFile and removes DB entity")
    void deleteDocument_callsStorageServiceDelete() {
        String assignedKey = testUser.getId() + "/" + UUID.randomUUID() + ".pdf";
        Document document = new Document(UUID.randomUUID(), testUser, "to-delete.pdf", assignedKey, "hash-" + UUID.randomUUID(), 1024);
        document = documentRepository.save(document);

        documentService.deleteDocument(testUser.getId(), document.getId());

        verify(mockStorageService).deleteFile(assignedKey);
        assertThat(documentRepository.findById(document.getId())).isEmpty();
    }

    @Test
    @DisplayName("Document deletion handles legacy flat UUID storage keys safely and purges vectors and DB record")
    void deleteDocument_legacyFlatKey_deletesVectorsAndRecord() {
        String legacyKey = UUID.randomUUID() + ".pdf";
        Document document = new Document(UUID.randomUUID(), testUser, "legacy.pdf", legacyKey, "hash-" + UUID.randomUUID(), 1024);
        document.setStatus(DocumentStatus.DELETION_FAILED); // Safe retry from DELETION_FAILED
        document = documentRepository.save(document);

        documentService.deleteDocument(testUser.getId(), document.getId());

        verify(mockVectorStoreService).deleteDocumentVectors(testUser.getId(), document.getId());
        verify(mockStorageService).deleteFile(legacyKey);
        assertThat(documentRepository.findById(document.getId())).isEmpty();
    }

    @Test
    @DisplayName("Cross-user access is prohibited during processing and deletion")
    void crossUserAccess_isPrevented() {
        String assignedKey = testUser.getId() + "/" + UUID.randomUUID() + ".pdf";
        Document document = new Document(UUID.randomUUID(), testUser, "protected.pdf", assignedKey, "hash-" + UUID.randomUUID(), 1024);
        document = documentRepository.save(document);

        final UUID docId = document.getId();
        final UUID unauthorizedUserId = otherUser.getId();

        assertThatThrownBy(() -> documentService.processDocument(unauthorizedUserId, docId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> documentService.deleteDocument(unauthorizedUserId, docId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
