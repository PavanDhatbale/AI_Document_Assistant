package com.aidocassistant.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String VALID_BASE64_256BIT_SECRET =
            "dGVzdC1zZWNyZXQta2V5LW1pbmltdW0tMjU2LWJpdHMtZXhhbXBsZQ=="; // 32 bytes decoded

    private static final String VALID_RAW_UTF8_32BYTE_SECRET =
            "this-is-a-32-byte-plain-text-secret-key-ok!"; // 43 chars >= 32 bytes

    private static final String SHORT_SECRET = "short-secret"; // 12 bytes

    @Test
    @DisplayName("1. Successful initialization with valid Base64 256-bit secret")
    void testValidBase64Secret() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        JwtTokenProvider provider = new JwtTokenProvider(env, VALID_BASE64_256BIT_SECRET, 3600000L);
        provider.init();

        UUID userId = UUID.randomUUID();
        String token = provider.generateToken(userId, "test@example.com", "ROLE_USER");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(provider.getEmailFromToken(token)).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("2. Successful initialization with valid raw UTF-8 >= 32 byte secret")
    void testValidRawUtf8Secret() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtTokenProvider provider = new JwtTokenProvider(env, VALID_RAW_UTF8_32BYTE_SECRET, 3600000L);
        provider.init();

        UUID userId = UUID.randomUUID();
        String token = provider.generateToken(userId, "prod.user@example.com", "ROLE_USER");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserIdFromToken(token)).isEqualTo(userId);
        assertThat(provider.getEmailFromToken(token)).isEqualTo("prod.user@example.com");
    }

    @Test
    @DisplayName("3. Production startup fails if JWT secret is absent or empty")
    void testProductionFailsWhenSecretMissing() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtTokenProvider provider = new JwtTokenProvider(env, "", 3600000L);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT signing secret (app.jwt.secret / JWT_SECRET) is missing or empty")
                .hasMessageContaining("Production startup aborted");
    }

    @Test
    @DisplayName("4. Production startup fails if known insecure default placeholder is used")
    void testProductionFailsWithInsecurePlaceholder() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtTokenProvider provider = new JwtTokenProvider(
                env, JwtTokenProvider.INSECURE_DEV_FALLBACK_SECRET, 3600000L);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Known insecure default/test JWT secret cannot be used in production");
    }

    @Test
    @DisplayName("5. Development profile uses fallback secret when secret is unconfigured")
    void testDevelopmentFallbackWhenSecretEmpty() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        JwtTokenProvider provider = new JwtTokenProvider(env, "", 3600000L);
        provider.init();

        UUID userId = UUID.randomUUID();
        String token = provider.generateToken(userId, "dev.user@example.com", "ROLE_USER");

        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.getUserIdFromToken(token)).isEqualTo(userId);
    }

    @Test
    @DisplayName("6. Startup fails in any environment if secret is shorter than 256 bits (32 bytes)")
    void testStartupFailsWhenSecretTooShort() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        JwtTokenProvider provider = new JwtTokenProvider(env, SHORT_SECRET, 3600000L);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret must be at least 256 bits (32 bytes)")
                .hasMessageNotContaining(SHORT_SECRET); // Ensures secret is not printed in exception
    }

    @Test
    @DisplayName("7. Non-dev non-prod unknown environment rejects missing secret")
    void testUnknownEnvironmentRejectsMissingSecret() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("staging");

        JwtTokenProvider provider = new JwtTokenProvider(env, "", 3600000L);

        assertThatThrownBy(provider::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT signing secret (app.jwt.secret / JWT_SECRET) is missing or empty");
    }

    @Test
    @DisplayName("8. Token validation rejects tampered and invalid tokens")
    void testInvalidTokensRejected() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        JwtTokenProvider provider = new JwtTokenProvider(env, VALID_BASE64_256BIT_SECRET, 3600000L);
        provider.init();

        assertThat(provider.validateToken(null)).isFalse();
        assertThat(provider.validateToken("")).isFalse();
        assertThat(provider.validateToken("   ")).isFalse();
        assertThat(provider.validateToken("not.a.valid.jwt")).isFalse();

        String token = provider.generateToken(UUID.randomUUID(), "user@example.com", "ROLE_USER");
        String tampered = token.substring(0, token.length() - 5) + "abcde";
        assertThat(provider.validateToken(tampered)).isFalse();
    }
}
