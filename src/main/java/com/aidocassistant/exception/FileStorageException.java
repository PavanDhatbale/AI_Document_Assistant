package com.aidocassistant.exception;

/**
 * Thrown when an unrecoverable filesystem or I/O error occurs during document storage or deletion.
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
