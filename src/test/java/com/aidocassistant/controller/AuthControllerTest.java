package com.aidocassistant.controller;

import com.aidocassistant.dto.LoginRequest;
import com.aidocassistant.dto.RegisterRequest;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("1. Successful registration returns 201 Created and valid JWT token")
    void testSuccessfulRegistration() throws Exception {
        RegisterRequest request = new RegisterRequest("john.doe@example.com", "SecurePassword123!", "John Doe", "1234567890");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("john.doe@example.com"))
                .andExpect(jsonPath("$.user.displayName").value("John Doe"))
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("2. Password is stored as a BCrypt hash, never plaintext")
    void testPasswordStoredAsBcryptHash() throws Exception {
        String rawPassword = "StrongSecretPassword99!";
        RegisterRequest request = new RegisterRequest("hash.test@example.com", rawPassword, "Hash User");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        Optional<User> userOpt = userRepository.findByEmail("hash.test@example.com");
        assertThat(userOpt).isPresent();
        User user = userOpt.get();

        assertThat(user.getPasswordHash()).isNotEqualTo(rawPassword);
        assertThat(user.getPasswordHash()).startsWith("$2a$");
        assertThat(passwordEncoder.matches(rawPassword, user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("3. Duplicate email registration is rejected with 409 Conflict")
    void testDuplicateEmailRegistrationRejected() throws Exception {
        RegisterRequest request1 = new RegisterRequest("duplicate@example.com", "Password123!", "First User");
        RegisterRequest request2 = new RegisterRequest("DUPLICATE@example.com", "AnotherPassword456!", "Second User");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    @DisplayName("4. Client-supplied privileged role cannot be assigned through public registration")
    void testClientSuppliedRoleIgnored() throws Exception {
        String payloadWithAdminRole = """
                {
                    "email": "hacker@example.com",
                    "password": "HackPassword123!",
                    "displayName": "Attacker",
                    "role": "ROLE_ADMIN"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithAdminRole))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("ROLE_USER"));

        User savedUser = userRepository.findByEmail("hacker@example.com").orElseThrow();
        assertThat(savedUser.getRole()).isEqualTo(UserRole.ROLE_USER);
    }

    @Test
    @DisplayName("5. Successful login issues a valid JWT")
    void testSuccessfulLogin() throws Exception {
        RegisterRequest register = new RegisterRequest("login.user@example.com", "CorrectPassword123!", "Login User");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest login = new LoginRequest("login.user@example.com", "CorrectPassword123!");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.email").value("login.user@example.com"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(responseBody);
        String token = jsonNode.get("token").asText();

        assertThat(tokenProvider.validateToken(token)).isTrue();
        assertThat(tokenProvider.getEmailFromToken(token)).isEqualTo("login.user@example.com");
    }

    @Test
    @DisplayName("6. Wrong password is rejected with safe 401 response and generic message")
    void testWrongPasswordRejected() throws Exception {
        RegisterRequest register = new RegisterRequest("wrongpass@example.com", "CorrectPassword123!", "User");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest wrongLogin = new LoginRequest("wrongpass@example.com", "WrongPasswordGuess!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongLogin)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    @DisplayName("7. /api/auth/me succeeds with valid token")
    void testGetCurrentUserProfileSuccess() throws Exception {
        RegisterRequest register = new RegisterRequest("me@example.com", "ValidPassword123!", "Me User", "5551234");
        MvcResult regResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn();

        String token = objectMapper.readTree(regResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("me@example.com"))
                .andExpect(jsonPath("$.displayName").value("Me User"))
                .andExpect(jsonPath("$.phoneNumber").value("5551234"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("8. /api/auth/me rejects missing token with 401 Unauthorized")
    void testGetCurrentUserProfileMissingToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("9. Invalid or tampered tokens are rejected with 401")
    void testInvalidTokenRejected() throws Exception {
        String forgedToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.invalidPayload.forgedSignature";

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("10. Protected endpoints are not accessible anonymously")
    void testProtectedEndpointsNotAccessibleAnonymously() throws Exception {
        // Any unauthenticated access to non-whitelisted paths is blocked
        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }
}
