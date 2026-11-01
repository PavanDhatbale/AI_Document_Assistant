package com.aidocassistant.security;

/**
 * Immutable value object holding server-verified claims extracted from a valid Google ID token.
 */
public record GoogleTokenPayload(
        String subject,
        String email,
        boolean emailVerified,
        String name,
        String pictureUrl
) {
}
