package com.aidocassistant.service;

import com.aidocassistant.dto.AuthResponse;
import com.aidocassistant.dto.LoginRequest;
import com.aidocassistant.dto.RegisterRequest;
import com.aidocassistant.dto.UserProfileResponse;
import com.aidocassistant.exception.DuplicateEmailException;
import com.aidocassistant.exception.ResourceNotFoundException;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.JwtTokenProvider;
import com.aidocassistant.security.SecurityUserPrincipal;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service orchestrating secure registration, credential verification, and user profile queries.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException("Email is already registered");
        }

        String passwordHash = passwordEncoder.encode(request.getPassword());
        String displayName = request.getDisplayName().trim();
        String phoneNumber = request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null;

        User user = new User(UUID.randomUUID(), normalizedEmail, passwordHash, displayName, phoneNumber);
        // CRITICAL: Public registration strictly assigns least-privilege ROLE_USER. Client input cannot override role.
        user.setRole(UserRole.ROLE_USER);

        User savedUser = userRepository.save(user);

        String token = tokenProvider.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole().name());
        UserProfileResponse profile = mapToProfileResponse(savedUser);

        return new AuthResponse(token, tokenProvider.getExpirationMs(), profile);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        // Never reveal whether an email exists or if password was incorrect
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isEnabled()) {
            throw new BadCredentialsException("Account is disabled");
        }

        String token = tokenProvider.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        UserProfileResponse profile = mapToProfileResponse(user);

        return new AuthResponse(token, tokenProvider.getExpirationMs(), profile);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(SecurityUserPrincipal principal) {
        if (principal == null) {
            throw new BadCredentialsException("Authentication required");
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User profile not found"));

        return mapToProfileResponse(user);
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
