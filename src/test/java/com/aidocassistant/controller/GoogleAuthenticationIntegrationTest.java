package com.aidocassistant.controller;

import com.aidocassistant.dto.GoogleAuthRequest;
import com.aidocassistant.dto.LoginRequest;
import com.aidocassistant.model.AuthProvider;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.GoogleTokenPayload;
import com.aidocassistant.security.GoogleTokenVerifierService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class GoogleAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private GoogleTokenVerifierService googleTokenVerifierService;

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. New Google user registration creates user with least-privilege ROLE_USER (CUSTOMER role)")
    void testNewGoogleUserRegistration() throws Exception {
        String tokenString = "valid-new-google-user-token";
        when(googleTokenVerifierService.verifyToken(tokenString)).thenReturn(
                new GoogleTokenPayload("google-sub-100", "newuser@example.com", true, "New Google User", "https://img.com/avatar.png")
        );

        GoogleAuthRequest request = new GoogleAuthRequest(tokenString);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("newuser@example.com"))
                .andExpect(jsonPath("$.user.displayName").value("New Google User"))
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.user.authProvider").value("GOOGLE"));

        Optional<User> userOpt = userRepository.findByEmail("newuser@example.com");
        assertThat(userOpt).isPresent();
        User user = userOpt.get();
        assertThat(user.getRole()).isEqualTo(UserRole.ROLE_USER);
        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.getPasswordHash()).isNotNull();
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("2. Existing Google user login authenticates and generates application JWT")
    void testExistingGoogleUserLogin() throws Exception {
        User existingUser = new User(UUID.randomUUID(), "existing.google@example.com", passwordEncoder.encode(UUID.randomUUID().toString()), "Existing GUser");
        existingUser.setAuthProvider(AuthProvider.GOOGLE);
        existingUser.setRole(UserRole.ROLE_USER);
        existingUser.setEnabled(true);
        userRepository.save(existingUser);

        String tokenString = "valid-existing-google-token";
        when(googleTokenVerifierService.verifyToken(tokenString)).thenReturn(
                new GoogleTokenPayload("google-sub-200", "existing.google@example.com", true, "Existing GUser", null)
        );

        GoogleAuthRequest request = new GoogleAuthRequest(tokenString);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.email").value("existing.google@example.com"))
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.user.authProvider").value("GOOGLE"));
    }

    @Test
    @DisplayName("3. Rejects invalid Google ID token with 401 Unauthorized")
    void testRejectInvalidGoogleToken() throws Exception {
        String invalidToken = "completely-invalid-token";
        when(googleTokenVerifierService.verifyToken(invalidToken))
                .thenThrow(new BadCredentialsException("Invalid, expired, or untrusted Google ID token"));

        GoogleAuthRequest request = new GoogleAuthRequest(invalidToken);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("4. Rejects expired Google ID token with 401 Unauthorized")
    void testRejectExpiredGoogleToken() throws Exception {
        String expiredToken = "expired-token-string";
        when(googleTokenVerifierService.verifyToken(expiredToken))
                .thenThrow(new BadCredentialsException("Invalid, expired, or untrusted Google ID token"));

        GoogleAuthRequest request = new GoogleAuthRequest(expiredToken);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("5. Rejects wrong Google client ID / audience with 401 Unauthorized")
    void testRejectWrongAudience() throws Exception {
        String wrongAudToken = "token-for-other-client-id";
        when(googleTokenVerifierService.verifyToken(wrongAudToken))
                .thenThrow(new BadCredentialsException("Google token audience does not match configured client ID"));

        GoogleAuthRequest request = new GoogleAuthRequest(wrongAudToken);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("6. Rejects unverified email with 401 Unauthorized")
    void testRejectUnverifiedEmail() throws Exception {
        String unverifiedToken = "unverified-email-token";
        when(googleTokenVerifierService.verifyToken(unverifiedToken))
                .thenThrow(new BadCredentialsException("Google account email is not verified"));

        GoogleAuthRequest request = new GoogleAuthRequest(unverifiedToken);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("7. Duplicate account conflict: Google login for existing password account rejected with 409 Conflict")
    void testAccountConflictWithExistingPasswordAccount() throws Exception {
        String localPassword = "MyLocalSecretPassword123!";
        User localUser = new User(UUID.randomUUID(), "local.user@example.com", passwordEncoder.encode(localPassword), "Local User");
        localUser.setAuthProvider(AuthProvider.LOCAL);
        localUser.setRole(UserRole.ROLE_USER);
        userRepository.save(localUser);

        String tokenString = "google-token-matching-local-email";
        when(googleTokenVerifierService.verifyToken(tokenString)).thenReturn(
                new GoogleTokenPayload("google-sub-300", "local.user@example.com", true, "Local User", null)
        );

        GoogleAuthRequest request = new GoogleAuthRequest(tokenString);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        // Verify local account password and provider remain unchanged
        User persisted = userRepository.findByEmail("local.user@example.com").orElseThrow();
        assertThat(persisted.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
        assertThat(passwordEncoder.matches(localPassword, persisted.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("8. Existing email/password login still works normally")
    void testExistingPasswordLoginStillWorks() throws Exception {
        String rawPassword = "StandardUserPass123!";
        User localUser = new User(UUID.randomUUID(), "password.user@example.com", passwordEncoder.encode(rawPassword), "Standard User");
        localUser.setAuthProvider(AuthProvider.LOCAL);
        localUser.setRole(UserRole.ROLE_USER);
        userRepository.save(localUser);

        LoginRequest loginRequest = new LoginRequest("password.user@example.com", rawPassword);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.email").value("password.user@example.com"))
                .andExpect(jsonPath("$.user.authProvider").value("LOCAL"));
    }

    @Test
    @DisplayName("9. JWT generated from Google authentication successfully authorizes protected endpoints")
    void testGoogleJwtAuthorizesProtectedEndpoints() throws Exception {
        String tokenString = "google-jwt-test-token";
        when(googleTokenVerifierService.verifyToken(tokenString)).thenReturn(
                new GoogleTokenPayload("google-sub-400", "jwt.test@example.com", true, "JWT User", null)
        );

        GoogleAuthRequest request = new GoogleAuthRequest(tokenString);

        MvcResult result = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String jwtToken = objectMapper.readTree(responseBody).get("token").asText();

        // Access protected /api/auth/me using the issued JWT
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jwt.test@example.com"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.authProvider").value("GOOGLE"));
    }

    @Test
    @DisplayName("10. Google users strictly receive ROLE_USER (CUSTOMER role) and cannot elevate privileges")
    void testGoogleUsersStrictlyCustomerRole() throws Exception {
        String tokenString = "new-customer-token";
        when(googleTokenVerifierService.verifyToken(tokenString)).thenReturn(
                new GoogleTokenPayload("google-sub-500", "customer@example.com", true, "Customer User", null)
        );

        GoogleAuthRequest request = new GoogleAuthRequest(tokenString);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"));

        User created = userRepository.findByEmail("customer@example.com").orElseThrow();
        assertThat(created.getRole()).isEqualTo(UserRole.ROLE_USER);
        assertThat(created.getRole()).isNotEqualTo(UserRole.ROLE_ADMIN);
    }

    @Test
    @DisplayName("11. Existing ADMIN accounts preserve their privileges and cannot be overridden by Google auth")
    void testAdminPrivilegesPreserved() throws Exception {
        String adminPassword = "AdminSecurePassword999!";
        User adminUser = new User(UUID.randomUUID(), "admin@company.com", passwordEncoder.encode(adminPassword), "System Admin");
        adminUser.setAuthProvider(AuthProvider.LOCAL);
        adminUser.setRole(UserRole.ROLE_ADMIN);
        userRepository.save(adminUser);

        // Attempt Google login with matching admin email
        String tokenString = "google-token-for-admin-email";
        when(googleTokenVerifierService.verifyToken(tokenString)).thenReturn(
                new GoogleTokenPayload("google-sub-600", "admin@company.com", true, "Admin Impersonator", null)
        );

        GoogleAuthRequest request = new GoogleAuthRequest(tokenString);

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        // Admin account remains ROLE_ADMIN with local authentication
        User persistedAdmin = userRepository.findByEmail("admin@company.com").orElseThrow();
        assertThat(persistedAdmin.getRole()).isEqualTo(UserRole.ROLE_ADMIN);
        assertThat(persistedAdmin.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
    }
}
