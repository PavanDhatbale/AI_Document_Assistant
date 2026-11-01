package com.aidocassistant.exception;

/**
 * Thrown when an OAuth2 authentication attempt (e.g. Google Sign-In) encounters
 * an existing account registered with local password credentials.
 */
public class OAuthAccountConflictException extends RuntimeException {

    public OAuthAccountConflictException(String message) {
        super(message);
    }
}
