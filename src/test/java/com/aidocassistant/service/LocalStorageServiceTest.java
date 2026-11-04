package com.aidocassistant.service;

import com.aidocassistant.exception.FileStorageException;
import com.aidocassistant.exception.InvalidFileException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageServiceTest {

    @TempDir
    Path tempStorageDir;

    private LocalStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LocalStorageService(tempStorageDir, 10 * 1024 * 1024); // 10MB
        storageService.init();
    }

    private byte[] createMinimalPdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @Test
    @DisplayName("Stores and loads valid PDF bytes successfully")
    void storeAndLoadFile_succeeds() throws Exception {
        byte[] pdfBytes = createMinimalPdf(2);
        UUID userId = UUID.randomUUID();

        String storageKey = storageService.storeFile(userId, pdfBytes);

        assertThat(storageKey).endsWith(".pdf");
        assertThat(Files.exists(tempStorageDir.resolve(storageKey))).isTrue();

        byte[] loadedBytes = storageService.loadFile(storageKey);
        assertThat(loadedBytes).isEqualTo(pdfBytes);
    }

    @Test
    @DisplayName("Extracts page count and calculates SHA-256 correctly")
    void pageCountAndSha256_succeed() throws Exception {
        byte[] pdfBytes = createMinimalPdf(3);

        int pageCount = storageService.extractPageCount(pdfBytes);
        assertThat(pageCount).isEqualTo(3);

        String hash = storageService.calculateSha256(pdfBytes);
        assertThat(hash).isNotBlank();
        assertThat(hash).hasSize(64);
    }

    @Test
    @DisplayName("Deletes file from disk and handles quiet cleanup")
    void deleteFileAndCleanup_succeeds() throws Exception {
        byte[] pdfBytes = createMinimalPdf(1);
        String storageKey = storageService.storeFile(pdfBytes);
        Path filePath = tempStorageDir.resolve(storageKey);

        assertThat(Files.exists(filePath)).isTrue();

        storageService.deleteFile(storageKey);
        assertThat(Files.exists(filePath)).isFalse();

        // Quiet cleanup of already deleted file should not throw
        storageService.cleanupFileQuietly(storageKey);
        storageService.cleanupFileQuietly(null);
    }

    @Test
    @DisplayName("Rejects path traversal attempts in filename")
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> storageService.loadFile("../secret.txt"))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> storageService.deleteFile("../secret.txt"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Validates PDF content and rejects non-PDF files")
    void validateFile_rejectsNonPdf() {
        MockMultipartFile textFile = new MockMultipartFile(
                "file", "test.txt", "text/plain", "Hello world".getBytes()
        );

        assertThatThrownBy(() -> storageService.validateFile(textFile, textFile.getBytes()))
                .isInstanceOf(InvalidFileException.class);
    }

    @Test
    @DisplayName("Loading non-existent file throws FileStorageException")
    void loadNonExistentFile_throwsException() {
        assertThatThrownBy(() -> storageService.loadFile(UUID.randomUUID() + ".pdf"))
                .isInstanceOf(FileStorageException.class);
    }
}
