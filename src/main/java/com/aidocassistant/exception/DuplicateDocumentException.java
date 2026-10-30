package com.aidocassistant.exception;

/**
 * Thrown when a user attempts to upload a document whose content hash already exists for that user.
 */
public class DuplicateDocumentException extends RuntimeException {

    public DuplicateDocumentException(String message) {
        super(message);
    }
}
