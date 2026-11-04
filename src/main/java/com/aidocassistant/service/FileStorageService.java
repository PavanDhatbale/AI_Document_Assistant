package com.aidocassistant.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Path;

/**
 * Legacy service name preserved for backward compatibility.
 * Extends LocalStorageService to implement the StorageService abstraction.
 */
public class FileStorageService extends LocalStorageService {

    @Autowired
    public FileStorageService(
            @Value("${app.storage.upload-dir:./storage/uploads}") String uploadDir,
            @Value("${app.storage.max-file-size-bytes:26214400}") long maxFileSizeBytes
    ) {
        super(uploadDir, maxFileSizeBytes);
    }

    public FileStorageService(Path storageDirectory, long maxFileSizeBytes) {
        super(storageDirectory, maxFileSizeBytes);
    }
}
