package com.aidocassistant.exception;

/**
 * Thrown when an uploaded file fails validation (e.g. empty, invalid type, corrupt PDF).
 */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }

    public InvalidFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
