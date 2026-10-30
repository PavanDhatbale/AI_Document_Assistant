package com.aidocassistant.service;

import com.aidocassistant.exception.FileStorageException;
import com.aidocassistant.exception.InvalidFileException;
import jakarta.annotation.PostConstruct;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Service for validating, hashing, storing, and safely deleting uploaded PDF files
 * in a private storage directory outside the public web root.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final byte[] PDF_MAGIC_BYTES = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // "%PDF-"

    private final Path storageDirectory;
    private final long maxFileSizeBytes;

    @Autowired
    public FileStorageService(
            @Value("${app.storage.upload-dir:./storage/uploads}") String uploadDir,
            @Value("${app.storage.max-file-size-bytes:26214400}") long maxFileSizeBytes
    ) {
        this.storageDirectory = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public FileStorageService(Path storageDirectory, long maxFileSizeBytes) {
        this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(this.storageDirectory);
            log.info("Initialized secure document storage at: {}", this.storageDirectory);
        } catch (IOException e) {
            throw new FileStorageException("Could not initialize storage directory", e);
        }
    }

    /**
     * Performs comprehensive multi-layer validation on the uploaded file:
     * 1. Empty / null check
     * 2. File size threshold check
     * 3. Sanitized filename and .pdf extension check
     * 4. Declared content type check
     * 5. Header signature (magic bytes "%PDF-") check
     * 6. Apache PDFBox structural parsing & encryption check
     */
    public void validateFile(MultipartFile file, byte[] fileBytes) {
        if (file == null || file.isEmpty() || fileBytes == null || fileBytes.length == 0) {
            throw new InvalidFileException("Uploaded file is empty or missing");
        }

        if (fileBytes.length > maxFileSizeBytes) {
            throw new InvalidFileException("File size exceeds maximum permitted limit of " + (maxFileSizeBytes / (1024 * 1024)) + "MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidFileException("Original filename cannot be empty");
        }

        String cleanedFilename = StringUtils.cleanPath(originalFilename);
        if (cleanedFilename.contains("..")) {
            throw new InvalidFileException("Path traversal characters in filename are not permitted");
        }

        if (!cleanedFilename.toLowerCase().endsWith(".pdf")) {
            throw new InvalidFileException("Only PDF files are supported");
        }

        String contentType = file.getContentType();
        if (contentType != null && !contentType.equalsIgnoreCase("application/pdf") && !contentType.equalsIgnoreCase("application/x-pdf")) {
            throw new InvalidFileException("Declared content type must be application/pdf");
        }

        if (!hasPdfHeader(fileBytes)) {
            throw new InvalidFileException("Invalid PDF file: missing %PDF- header signature");
        }

        try (PDDocument doc = Loader.loadPDF(fileBytes)) {
            if (doc.isEncrypted()) {
                throw new InvalidFileException("Encrypted or password-protected PDF files are not supported");
            }
            if (doc.getNumberOfPages() <= 0) {
                throw new InvalidFileException("PDF file contains no readable pages");
            }
        } catch (IOException e) {
            throw new InvalidFileException("Malformed or unreadable PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts total page count using PDFBox 3.x.
     */
    public int extractPageCount(byte[] fileBytes) {
        try (PDDocument doc = Loader.loadPDF(fileBytes)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new InvalidFileException("Failed to read PDF page count: " + e.getMessage(), e);
        }
    }

    /**
     * Computes the SHA-256 digest of the uploaded file bytes.
     */
    public String calculateSha256(byte[] fileBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(fileBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Saves the file bytes to disk using a server-controlled UUID filename.
     * Prevents path traversal and never uses user-supplied filenames on disk.
     */
    public String storeFile(byte[] fileBytes) {
        String storageFileName = UUID.randomUUID() + ".pdf";
        Path destination = this.storageDirectory.resolve(storageFileName).normalize();

        if (!destination.startsWith(this.storageDirectory)) {
            throw new SecurityException("Storage path traversal attempted");
        }

        try {
            Files.write(destination, fileBytes, StandardOpenOption.CREATE_NEW);
            return storageFileName;
        } catch (IOException e) {
            log.error("Failed to store file on disk: {}", e.getMessage(), e);
            throw new FileStorageException("Failed to store document file", e);
        }
    }

    /**
     * Safely loads file bytes from the private storage directory.
     * Prevents path traversal and validates that the file exists.
     */
    public byte[] loadFile(String storageFileName) {
        if (storageFileName == null || storageFileName.isBlank()
                || storageFileName.contains("..")
                || storageFileName.contains("/")
                || storageFileName.contains("\\")) {
            throw new SecurityException("Invalid storage filename");
        }

        Path filePath = this.storageDirectory.resolve(storageFileName).normalize();
        if (!filePath.startsWith(this.storageDirectory)) {
            throw new SecurityException("Storage path traversal attempted");
        }

        if (!Files.exists(filePath)) {
            throw new FileStorageException("Stored document file not found on disk");
        }

        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Failed to read stored file {}: {}", storageFileName, e.getMessage(), e);
            throw new FileStorageException("Failed to read document file from storage", e);
        }
    }

    /**
     * Safely deletes a file from the private storage directory.
     * Validates that the filename contains no path traversal elements.
     */
    public void deleteFile(String storageFileName) {
        if (storageFileName == null || storageFileName.isBlank()
                || storageFileName.contains("..")
                || storageFileName.contains("/")
                || storageFileName.contains("\\")) {
            throw new SecurityException("Invalid storage filename");
        }

        Path filePath = this.storageDirectory.resolve(storageFileName).normalize();
        if (!filePath.startsWith(this.storageDirectory)) {
            throw new SecurityException("Storage path traversal attempted");
        }

        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (!deleted) {
                log.warn("Physical file {} was not found during deletion", storageFileName);
            }
        } catch (IOException e) {
            log.error("I/O error deleting file {}: {}", storageFileName, e.getMessage(), e);
            throw new FileStorageException("Could not delete document file from storage", e);
        }
    }

    /**
     * Best-effort cleanup for rolling back a written file when database persistence fails.
     */
    public void cleanupFileQuietly(String storageFileName) {
        if (storageFileName == null || storageFileName.isBlank()) {
            return;
        }
        try {
            Path filePath = this.storageDirectory.resolve(storageFileName).normalize();
            if (filePath.startsWith(this.storageDirectory)) {
                Files.deleteIfExists(filePath);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up file {} after failure: {}", storageFileName, e.getMessage());
        }
    }

    public Path getStorageDirectory() {
        return storageDirectory;
    }

    private boolean hasPdfHeader(byte[] bytes) {
        if (bytes.length < PDF_MAGIC_BYTES.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC_BYTES.length; i++) {
            if (bytes[i] != PDF_MAGIC_BYTES[i]) {
                return false;
            }
        }
        return true;
    }
}
