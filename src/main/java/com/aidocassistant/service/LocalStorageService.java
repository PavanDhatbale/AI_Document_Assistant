package com.aidocassistant.service;

import com.aidocassistant.exception.FileStorageException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Local filesystem implementation of StorageService.
 * Stores files in a private directory outside the public web root.
 */
public class LocalStorageService extends AbstractStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path storageDirectory;

    public LocalStorageService(String uploadDir, long maxFileSizeBytes) {
        super(maxFileSizeBytes);
        this.storageDirectory = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    public LocalStorageService(Path storageDirectory, long maxFileSizeBytes) {
        super(maxFileSizeBytes);
        this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
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

    @Override
    public String storeFile(UUID userId, byte[] fileBytes) {
        // For local filesystem storage, maintain flat UUID naming for compatibility with local test expectations
        String storageFileName = UUID.randomUUID() + ".pdf";
        Path destination = this.storageDirectory.resolve(storageFileName).normalize();

        if (!destination.startsWith(this.storageDirectory)) {
            throw new SecurityException("Storage path traversal attempted");
        }

        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.write(destination, fileBytes, StandardOpenOption.CREATE_NEW);
            return storageFileName;
        } catch (IOException e) {
            log.error("Failed to store file on disk: {}", e.getMessage(), e);
            throw new FileStorageException("Failed to store document file", e);
        }
    }

    @Override
    public byte[] loadFile(String storageFileName) {
        validateStorageFileName(storageFileName);

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

    @Override
    public void deleteFile(String storageFileName) {
        validateStorageFileName(storageFileName);

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

    @Override
    public void cleanupFileQuietly(String storageFileName) {
        if (storageFileName == null || storageFileName.isBlank()) {
            return;
        }
        try {
            validateStorageFileName(storageFileName);
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

    /**
     * Checks if a document file physically exists in the local storage directory.
     */
    public boolean exists(String storageFileName) {
        if (storageFileName == null || storageFileName.isBlank() || storageFileName.contains("..")) {
            return false;
        }
        try {
            Path filePath = this.storageDirectory.resolve(storageFileName).normalize();
            return filePath.startsWith(this.storageDirectory) && Files.exists(filePath);
        } catch (Exception e) {
            return false;
        }
    }

    private void validateStorageFileName(String storageFileName) {
        if (storageFileName == null || storageFileName.isBlank() || storageFileName.contains("..")) {
            throw new SecurityException("Invalid storage filename: path traversal attempted or blank");
        }
    }
}
