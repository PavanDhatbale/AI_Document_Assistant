package com.aidocassistant.service;

import com.aidocassistant.dto.ChangePasswordRequest;
import com.aidocassistant.dto.SimpleMessageResponse;
import com.aidocassistant.dto.UpdateProfileRequest;
import com.aidocassistant.dto.UserProfileResponse;
import com.aidocassistant.exception.ResourceNotFoundException;
import com.aidocassistant.model.User;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.SecurityUserPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service orchestrating user profile retrieval, profile updates, and secure password changes.
 * All operations resolve identity strictly from the server-validated security principal.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Retrieves the profile of the currently authenticated user.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(SecurityUserPrincipal principal) {
        User user = resolveAuthenticatedUser(principal);
        return mapToProfileResponse(user);
    }

    /**
     * Updates non-sensitive profile fields (displayName and phoneNumber).
     * Sensitive fields (email, role, enabled, password) remain strictly immutable.
     */
    @Transactional
    public UserProfileResponse updateProfile(SecurityUserPrincipal principal, UpdateProfileRequest request) {
        User user = resolveAuthenticatedUser(principal);

        user.setDisplayName(request.getDisplayName().trim());

        String phone = request.getPhoneNumber();
        if (phone != null && !phone.trim().isEmpty()) {
            user.setPhoneNumber(phone.trim());
        } else {
            user.setPhoneNumber(null);
        }

        User updatedUser = userRepository.save(user);
        return mapToProfileResponse(updatedUser);
    }

    /**
     * Securely updates the user's password after verifying the existing credentials.
     * Enforces matching confirmation, distinction from current password, and BCrypt encryption.
     *
     * Note: Existing active JWT tokens remain valid until expiration because this platform uses
     * stateless JWTs without a distributed revocation blacklist.
     */
    @Transactional
    public SimpleMessageResponse changePassword(SecurityUserPrincipal principal, ChangePasswordRequest request) {
        User user = resolveAuthenticatedUser(principal);

        // 1. Verify that new password matches confirmation
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("New password and confirm password do not match");
        }

        // 2. Verify current password against persisted BCrypt hash
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        // 3. Ensure new password is not identical to current password
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password cannot be the same as the current password");
        }

        // 4. BCrypt encode and persist
        String newPasswordHash = passwordEncoder.encode(request.getNewPassword());
        user.setPasswordHash(newPasswordHash);
        userRepository.save(user);

        return new SimpleMessageResponse("Password changed successfully");
    }

    private User resolveAuthenticatedUser(SecurityUserPrincipal principal) {
        if (principal == null || principal.getId() == null) {
            throw new AccessDeniedException("Authentication required");
        }
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));
    }

    private UserProfileResponse mapToProfileResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPhoneNumber(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }
}
