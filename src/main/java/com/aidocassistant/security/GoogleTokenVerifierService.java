package com.aidocassistant.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Service verifying Google Identity Services (GIS) ID tokens using Google's official token verifier.
 * Strictly enforces signature validation, audience check, issuer check, expiration, and email_verified claim.
 */
@Service
public class GoogleTokenVerifierService {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifierService.class);

    private static final List<String> VALID_ISSUERS = Arrays.asList(
            "https://accounts.google.com",
            "accounts.google.com"
    );

    private final String googleClientId;
    private final GoogleIdTokenVerifier verifier;

    @Autowired
    public GoogleTokenVerifierService(@Value("${app.google.client-id:}") String googleClientId) {
        this.googleClientId = googleClientId != null ? googleClientId.trim() : "";
        if (!this.googleClientId.isEmpty()) {
            this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(this.googleClientId))
                    .setIssuers(VALID_ISSUERS)
                    .build();
        } else {
            this.verifier = null;
        }
    }

    /**
     * Package-private or test constructor allowing injection of a mock or custom GoogleIdTokenVerifier.
     */
    public GoogleTokenVerifierService(String googleClientId, GoogleIdTokenVerifier verifier) {
        this.googleClientId = googleClientId != null ? googleClientId.trim() : "";
        this.verifier = verifier;
    }

    /**
     * Verifies the provided Google ID token string and returns server-validated claims.
     * Throws BadCredentialsException if the token is invalid, expired, wrong audience, or unverified email.
     */
    public GoogleTokenPayload verifyToken(String idTokenString) {
        if (idTokenString == null || idTokenString.trim().isEmpty()) {
            throw new BadCredentialsException("Google ID token is required");
        }

        if (this.verifier == null || this.googleClientId.isEmpty()) {
            log.error("Google authentication rejected: app.google.client-id / GOOGLE_CLIENT_ID is not configured");
            throw new BadCredentialsException("Google authentication is not configured on the server");
        }

        try {
            GoogleIdToken idToken = verifier.verify(idTokenString.trim());
            if (idToken == null) {
                throw new BadCredentialsException("Invalid, expired, or untrusted Google ID token");
            }

            GoogleIdToken.Payload payload = idToken.getPayload();

            // 1. Verify issuer
            if (payload.getIssuer() == null || !VALID_ISSUERS.contains(payload.getIssuer())) {
                throw new BadCredentialsException("Invalid Google token issuer: " + payload.getIssuer());
            }

            // 2. Verify audience matches configured client ID
            List<String> audienceList = payload.getAudienceAsList();
            if (audienceList == null || !audienceList.contains(this.googleClientId)) {
                throw new BadCredentialsException("Google token audience does not match configured client ID");
            }

            // 3. Verify email_verified claim
            Boolean emailVerified = payload.getEmailVerified();
            if (!Boolean.TRUE.equals(emailVerified)) {
                throw new BadCredentialsException("Google account email is not verified");
            }

            // 4. Verify email claim is present
            String email = payload.getEmail();
            if (email == null || email.trim().isEmpty()) {
                throw new BadCredentialsException("Google ID token does not contain a verified email address");
            }

            String name = (String) payload.get("name");
            String picture = (String) payload.get("picture");
            String subject = payload.getSubject();

            return new GoogleTokenPayload(
                    subject,
                    email.trim().toLowerCase(),
                    true,
                    name != null ? name.trim() : null,
                    picture
            );

        } catch (GeneralSecurityException | IOException e) {
            log.error("Cryptographic verification failure for Google ID token", e);
            throw new BadCredentialsException("Failed to verify Google ID token signature: " + e.getMessage());
        }
    }

    public String getGoogleClientId() {
        return googleClientId;
    }
}
