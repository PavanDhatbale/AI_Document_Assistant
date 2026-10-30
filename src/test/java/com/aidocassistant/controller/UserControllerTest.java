package com.aidocassistant.controller;

import com.aidocassistant.dto.ChangePasswordRequest;
import com.aidocassistant.dto.LoginRequest;
import com.aidocassistant.dto.RegisterRequest;
import com.aidocassistant.dto.UpdateProfileRequest;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.JwtTokenProvider;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class UserControllerTest {

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

    private String registerAndGetToken(String email, String password, String displayName, String phoneNumber) throws Exception {
        RegisterRequest request = new RegisterRequest(email, password, displayName, phoneNumber);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    @DisplayName("1. Authenticated user can fetch their own profile via /api/users/profile")
    void testGetOwnProfileSuccess() throws Exception {
        String token = registerAndGetToken("alice@example.com", "Password123!", "Alice Wonder", "1234567890");

        mockMvc.perform(get("/api/users/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice Wonder"))
                .andExpect(jsonPath("$.phoneNumber").value("1234567890"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("2. Unauthenticated request to /api/users/profile is rejected with 401")
    void testGetProfileUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/users/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("3. Authenticated user can update their display name and phone number")
    void testUpdateProfileSuccess() throws Exception {
        String token = registerAndGetToken("bob@example.com", "Password123!", "Bob Old", "1111111");

        UpdateProfileRequest update = new UpdateProfileRequest("Bob Updated", "9999999");

        mockMvc.perform(put("/api/users/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"))
                .andExpect(jsonPath("$.displayName").value("Bob Updated"))
                .andExpect(jsonPath("$.phoneNumber").value("9999999"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));

        // Verify database persistence
        User persisted = userRepository.findByEmail("bob@example.com").orElseThrow();
        assertThat(persisted.getDisplayName()).isEqualTo("Bob Updated");
        assertThat(persisted.getPhoneNumber()).isEqualTo("9999999");
    }

    @Test
    @DisplayName("4. Attempt to change privileged fields (role, email, enabled) via profile update is rejected/ignored")
    void testPrivilegedFieldsCannotBeUpdated() throws Exception {
        String token = registerAndGetToken("charlie@example.com", "Password123!", "Charlie Original", "222222");

        // Payload attempting to tamper with role, email, and enabled status
        String tamperedJson = """
                {
                  "displayName": "Charlie Renamed",
                  "phoneNumber": "333333",
                  "role": "ROLE_ADMIN",
                  "email": "hacker@evil.com",
                  "enabled": false
                }
                """;

        mockMvc.perform(put("/api/users/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Charlie Renamed"))
                .andExpect(jsonPath("$.email").value("charlie@example.com"))
                .andExpect(jsonPath("$.role").value("ROLE_USER"));

        User persisted = userRepository.findByEmail("charlie@example.com").orElseThrow();
        assertThat(persisted.getDisplayName()).isEqualTo("Charlie Renamed");
        assertThat(persisted.getRole()).isEqualTo(UserRole.ROLE_USER);
        assertThat(persisted.getEmail()).isEqualTo("charlie@example.com");
        assertThat(persisted.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("5. Profile update validation errors are rejected with 400 Bad Request")
    void testProfileUpdateValidationErrors() throws Exception {
        String token = registerAndGetToken("dan@example.com", "Password123!", "Dan Original", null);

        // Blank display name
        UpdateProfileRequest invalidBlank = new UpdateProfileRequest("", "123456");
        mockMvc.perform(put("/api/users/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidBlank)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.displayName").exists());

        // Display name too short (< 2 chars)
        UpdateProfileRequest invalidShort = new UpdateProfileRequest("A", "123456");
        mockMvc.perform(put("/api/users/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidShort)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("6. Successful password change updates password hash and allows login with new password only")
    void testSuccessfulPasswordChange() throws Exception {
        String token = registerAndGetToken("eve@example.com", "OldPassword123!", "Eve User", null);

        ChangePasswordRequest changeReq = new ChangePasswordRequest("OldPassword123!", "NewPassword456!", "NewPassword456!");

        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changeReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password changed successfully"));

        // Verify password hash in DB
        User eve = userRepository.findByEmail("eve@example.com").orElseThrow();
        assertThat(passwordEncoder.matches("NewPassword456!", eve.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("OldPassword123!", eve.getPasswordHash())).isFalse();

        // Old password no longer logs in
        LoginRequest oldLogin = new LoginRequest("eve@example.com", "OldPassword123!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oldLogin)))
                .andExpect(status().isUnauthorized());

        // New password authenticates successfully
        LoginRequest newLogin = new LoginRequest("eve@example.com", "NewPassword456!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newLogin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString());
    }

    @Test
    @DisplayName("7. Incorrect current password is rejected with 400 Bad Request")
    void testIncorrectCurrentPasswordRejected() throws Exception {
        String token = registerAndGetToken("frank@example.com", "CorrectPassword123!", "Frank", null);

        ChangePasswordRequest wrongReq = new ChangePasswordRequest("WrongCurrentPass!", "BrandNewPassword999!", "BrandNewPassword999!");

        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    @DisplayName("8. Mismatched new password confirmation is rejected with 400 Bad Request")
    void testMismatchedPasswordConfirmationRejected() throws Exception {
        String token = registerAndGetToken("grace@example.com", "GracePassword123!", "Grace", null);

        ChangePasswordRequest mismatchReq = new ChangePasswordRequest("GracePassword123!", "ValidNewPass123!", "NonMatchingPass999!");

        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mismatchReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("New password and confirm password do not match"));
    }

    @Test
    @DisplayName("9. New password identical to current password is rejected")
    void testNewPasswordSameAsCurrentRejected() throws Exception {
        String token = registerAndGetToken("heidi@example.com", "SamePassword123!", "Heidi", null);

        ChangePasswordRequest sameReq = new ChangePasswordRequest("SamePassword123!", "SamePassword123!", "SamePassword123!");

        mockMvc.perform(put("/api/users/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("New password cannot be the same as the current password"));
    }

    @Test
    @DisplayName("10. One user cannot modify another user's profile")
    void testUserCannotModifyOtherUserProfile() throws Exception {
        String tokenA = registerAndGetToken("userA@example.com", "PasswordUserA123!", "User A", "1111");
        String tokenB = registerAndGetToken("userB@example.com", "PasswordUserB123!", "User B", "2222");

        // User A updates profile
        UpdateProfileRequest updateA = new UpdateProfileRequest("User A Modified", "9999");
        mockMvc.perform(put("/api/users/profile")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateA)))
                .andExpect(status().isOk());

        // Verify User B's profile was NOT affected
        mockMvc.perform(get("/api/users/profile")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("User B"))
                .andExpect(jsonPath("$.phoneNumber").value("2222"));
    }
}
