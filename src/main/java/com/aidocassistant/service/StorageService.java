package com.aidocassistant.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Storage service abstraction for document binary persistence.
 * Implementations support local filesystem storage or cloud object storage (e.g., Supabase Storage).
 */
public interface StorageService {

    /**
     * Performs multi-layer file validation (magic bytes, size, PDFBox structural check).
     *
     * @param file      the uploaded multipart file
     * @param fileBytes raw bytes of the file
     */
    void validateFile(MultipartFile file, byte[] fileBytes);

    /**
     * Extracts total readable page count from PDF bytes.
     *
     * @param fileBytes raw PDF bytes
     * @return 1-based total page count
     */
    int extractPageCount(byte[] fileBytes);

    /**
     * Computes SHA-256 digest of file bytes for duplicate detection.
     *
     * @param fileBytes raw file bytes
     * @return hex-encoded SHA-256 string
     */
    String calculateSha256(byte[] fileBytes);

    /**
     * Stores file bytes under a server-generated key.
     *
     * @param fileBytes raw file bytes to store
     * @return server-assigned storage key / filename
     */
    String storeFile(byte[] fileBytes);

    /**
     * Stores file bytes scoped to an authenticated user identifier.
     *
     * @param userId    the owner user ID
     * @param fileBytes raw file bytes to store
     * @return server-assigned storage key / filename
     */
    String storeFile(UUID userId, byte[] fileBytes);

    /**
     * Loads raw file bytes using the storage key.
     *
     * @param storageKey the storage key / filename
     * @return raw file bytes
     */
    byte[] loadFile(String storageKey);

    /**
     * Permanently deletes the stored file.
     *
     * @param storageKey the storage key / filename
     */
    void deleteFile(String storageKey);

    /**
     * Quietly cleans up a file on transaction rollback without throwing exceptions.
     *
     * @param storageKey the storage key / filename
     */
    void cleanupFileQuietly(String storageKey);
}
