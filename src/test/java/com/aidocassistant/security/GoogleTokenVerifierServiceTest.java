package com.aidocassistant.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleTokenVerifierServiceTest {

    private static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";

    @Mock
    private GoogleIdTokenVerifier mockVerifier;

    private GoogleTokenVerifierService service;

    @BeforeEach
    void setUp() {
        service = new GoogleTokenVerifierService(CLIENT_ID, mockVerifier);
    }

    @Test
    @DisplayName("Successfully verifies valid Google token with all required claims")
    void testVerifyValidToken() throws Exception {
        GoogleIdToken mockToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-12345");
        payload.setEmail("alice@example.com");
        payload.setEmailVerified(true);
        payload.set("name", "Alice Wonder");
        payload.set("picture", "https://example.com/avatar.png");
        payload.setIssuer("https://accounts.google.com");
        payload.setAudience(Collections.singletonList(CLIENT_ID));

        when(mockToken.getPayload()).thenReturn(payload);
        when(mockVerifier.verify("valid-token")).thenReturn(mockToken);

        GoogleTokenPayload result = service.verifyToken("valid-token");

        assertThat(result).isNotNull();
        assertThat(result.subject()).isEqualTo("google-sub-12345");
        assertThat(result.email()).isEqualTo("alice@example.com");
        assertThat(result.emailVerified()).isTrue();
        assertThat(result.name()).isEqualTo("Alice Wonder");
        assertThat(result.pictureUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    @DisplayName("Rejects null or blank Google token string")
    void testRejectNullOrBlankToken() {
        assertThatThrownBy(() -> service.verifyToken(null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Google ID token is required");

        assertThatThrownBy(() -> service.verifyToken("   "))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Google ID token is required");
    }

    @Test
    @DisplayName("Rejects verification when Google Client ID is missing or unconfigured")
    void testRejectWhenClientIdMissing() {
        GoogleTokenVerifierService unconfiguredService = new GoogleTokenVerifierService("", null);

        assertThatThrownBy(() -> unconfiguredService.verifyToken("some-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Google authentication is not configured on the server");
    }

    @Test
    @DisplayName("Rejects invalid or expired token when verifier returns null")
    void testRejectExpiredOrInvalidToken() throws Exception {
        when(mockVerifier.verify("expired-token")).thenReturn(null);

        assertThatThrownBy(() -> service.verifyToken("expired-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid, expired, or untrusted Google ID token");
    }

    @Test
    @DisplayName("Rejects token with invalid issuer")
    void testRejectInvalidIssuer() throws Exception {
        GoogleIdToken mockToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setIssuer("https://malicious-issuer.com");
        payload.setAudience(Collections.singletonList(CLIENT_ID));
        payload.setEmail("test@example.com");
        payload.setEmailVerified(true);

        when(mockToken.getPayload()).thenReturn(payload);
        when(mockVerifier.verify("bad-issuer-token")).thenReturn(mockToken);

        assertThatThrownBy(() -> service.verifyToken("bad-issuer-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid Google token issuer");
    }

    @Test
    @DisplayName("Rejects token when audience does not match configured client ID")
    void testRejectAudienceMismatch() throws Exception {
        GoogleIdToken mockToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setIssuer("https://accounts.google.com");
        payload.setAudience(Collections.singletonList("other-client-id.apps.googleusercontent.com"));
        payload.setEmail("test@example.com");
        payload.setEmailVerified(true);

        when(mockToken.getPayload()).thenReturn(payload);
        when(mockVerifier.verify("wrong-aud-token")).thenReturn(mockToken);

        assertThatThrownBy(() -> service.verifyToken("wrong-aud-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Google token audience does not match configured client ID");
    }

    @Test
    @DisplayName("Rejects token when email is not verified by Google")
    void testRejectUnverifiedEmail() throws Exception {
        GoogleIdToken mockToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setIssuer("https://accounts.google.com");
        payload.setAudience(Collections.singletonList(CLIENT_ID));
        payload.setEmail("unverified@example.com");
        payload.setEmailVerified(false); // Unverified

        when(mockToken.getPayload()).thenReturn(payload);
        when(mockVerifier.verify("unverified-email-token")).thenReturn(mockToken);

        assertThatThrownBy(() -> service.verifyToken("unverified-email-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Google account email is not verified");
    }

    @Test
    @DisplayName("Rejects token when email claim is missing")
    void testRejectMissingEmailClaim() throws Exception {
        GoogleIdToken mockToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setIssuer("https://accounts.google.com");
        payload.setAudience(Collections.singletonList(CLIENT_ID));
        payload.setEmail(null);
        payload.setEmailVerified(true);

        when(mockToken.getPayload()).thenReturn(payload);
        when(mockVerifier.verify("no-email-token")).thenReturn(mockToken);

        assertThatThrownBy(() -> service.verifyToken("no-email-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("does not contain a verified email address");
    }

    @Test
    @DisplayName("Handles cryptographic GeneralSecurityException properly")
    void testHandleCryptographicException() throws Exception {
        when(mockVerifier.verify("crypto-fail-token"))
                .thenThrow(new GeneralSecurityException("Signature verification failed"));

        assertThatThrownBy(() -> service.verifyToken("crypto-fail-token"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Failed to verify Google ID token signature");
    }
}
