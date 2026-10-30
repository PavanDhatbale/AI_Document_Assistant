package com.aidocassistant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * Generates, signs, and validates HMAC-SHA256 JWT tokens using JJWT 0.12.x.
 * Hardened to prevent hardcoded secret leakage or usage of insecure default
 * keys in production environments.
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final int MIN_SECRET_BYTES = 32; // 256 bits

    public static final String INSECURE_DEV_FALLBACK_SECRET =
            "your_base64_encoded_256_bit_secret_key_change_me_in_production";

    private static final Set<String> INSECURE_PLACEHOLDERS = Set.of(
            INSECURE_DEV_FALLBACK_SECRET,
            "dGVzdC1zZWNyZXQta2V5LW1pbmltdW0tMjU2LWJpdHMtZXhhbXBsZQ==",
            "secret",
            "changeme",
            "change_me_in_production",
            "your_jwt_secret_here"
    );

    @Autowired(required = false)
    private Environment environment;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    private SecretKey signingKey;

    public JwtTokenProvider() {
    }

    public JwtTokenProvider(Environment environment, String jwtSecret, long jwtExpirationMs) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.jwtExpirationMs = jwtExpirationMs;
    }

    @PostConstruct
    public void init() {
        boolean isProd = isProductionEnvironment();
        boolean isDevOrTest = isDevOrTestEnvironment();

        String secretToUse = this.jwtSecret != null ? this.jwtSecret.trim() : "";

        // Check if absent or empty
        if (secretToUse.isEmpty()) {
            if (isProd || !isDevOrTest) {
                throw new IllegalStateException(
                        "JWT signing secret (app.jwt.secret / JWT_SECRET) is missing or empty. " +
                        "Production startup aborted. Please configure a secure 256-bit key via environment variable JWT_SECRET."
                );
            } else {
                log.warn("CRITICAL SECURITY NOTICE: app.jwt.secret is not set. " +
                         "Using insecure development fallback. NEVER run production with this configuration!");
                secretToUse = INSECURE_DEV_FALLBACK_SECRET;
            }
        }

        // Check if known insecure placeholder is used in production
        if (isProd && INSECURE_PLACEHOLDERS.contains(secretToUse)) {
            throw new IllegalStateException(
                    "Known insecure default/test JWT secret cannot be used in production. " +
                    "Please configure a unique, unpredictable 256-bit key via environment variable JWT_SECRET."
            );
        } else if (INSECURE_PLACEHOLDERS.contains(secretToUse)) {
            log.warn("SECURITY NOTICE: Using known development/test JWT secret. " +
                     "Ensure a strong, unpredictable key is configured before deploying to production.");
        }

        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretToUse);
            if (keyBytes.length < MIN_SECRET_BYTES) {
                keyBytes = secretToUse.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception ex) {
            keyBytes = secretToUse.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT secret must be at least 256 bits (32 bytes). Current length is " + keyBytes.length + " bytes."
            );
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public boolean isProductionEnvironment() {
        if (environment == null) {
            return false;
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) {
            activeProfiles = environment.getDefaultProfiles();
        }
        if (activeProfiles != null) {
            for (String profile : activeProfiles) {
                if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isDevOrTestEnvironment() {
        if (environment == null) {
            return true; // Safe default for standalone unit testing
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) {
            activeProfiles = environment.getDefaultProfiles();
        }
        if (activeProfiles != null) {
            for (String profile : activeProfiles) {
                if ("dev".equalsIgnoreCase(profile) ||
                    "development".equalsIgnoreCase(profile) ||
                    "test".equalsIgnoreCase(profile) ||
                    "auth-test".equalsIgnoreCase(profile) ||
                    "smoke-test".equalsIgnoreCase(profile) ||
                    "local".equalsIgnoreCase(profile)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String generateToken(UUID userId, String email, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(signingKey)
                .compact();
    }

    public boolean validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        try {
            Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public UUID getUserIdFromToken(String token) {
        Claims claims = getClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    public String getEmailFromToken(String token) {
        Claims claims = getClaims(token);
        return claims.get("email", String.class);
    }

    public long getExpirationMs() {
        return jwtExpirationMs;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
