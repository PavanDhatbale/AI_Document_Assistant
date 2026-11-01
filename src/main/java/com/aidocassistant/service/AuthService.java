package com.aidocassistant.service;

import com.aidocassistant.dto.AuthResponse;
import com.aidocassistant.dto.LoginRequest;
import com.aidocassistant.dto.RegisterRequest;
import com.aidocassistant.dto.UserProfileResponse;
import com.aidocassistant.exception.DuplicateEmailException;
import com.aidocassistant.exception.OAuthAccountConflictException;
import com.aidocassistant.exception.ResourceNotFoundException;
import com.aidocassistant.model.AuthProvider;
import com.aidocassistant.model.User;
import com.aidocassistant.model.UserRole;
import com.aidocassistant.repository.UserRepository;
import com.aidocassistant.security.GoogleTokenPayload;
import com.aidocassistant.security.GoogleTokenVerifierService;
import com.aidocassistant.security.JwtTokenProvider;
import com.aidocassistant.security.SecurityUserPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Service orchestrating secure registration, credential verification, Google Identity authentication,
 * and user profile queries.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final GoogleTokenVerifierService googleTokenVerifierService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider) {
        this(userRepository, passwordEncoder, tokenProvider, null);
    }

    @Autowired
    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       GoogleTokenVerifierService googleTokenVerifierService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.googleTokenVerifierService = googleTokenVerifierService;
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
        user.setAuthProvider(AuthProvider.LOCAL);

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

        // If user registered with Google and has no local password
        if (user.getAuthProvider() == AuthProvider.GOOGLE && user.getPasswordHash() == null) {
            throw new BadCredentialsException("This account was created using Google Sign-in. Please sign in with Google.");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isEnabled()) {
            throw new BadCredentialsException("Account is disabled");
        }

        String token = tokenProvider.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        UserProfileResponse profile = mapToProfileResponse(user);

        return new AuthResponse(token, tokenProvider.getExpirationMs(), profile);
    }

    /**
     * Authenticates a user using Google Identity Services ID token.
     * Supports both Google Sign-in and Google Sign-up.
     * Enforces:
     * 1. Cryptographic token verification on backend.
     * 2. Assignment of least-privilege ROLE_USER for new accounts. Never creates ADMIN/STAFF.
     * 3. Prevents overwriting or auto-linking existing email/password accounts (Case 3).
     * 4. Issues the application's standard JWT token.
     */
    @Transactional
    public AuthResponse authenticateWithGoogle(String idTokenString) {
        if (googleTokenVerifierService == null) {
            throw new IllegalStateException("GoogleTokenVerifierService is not available");
        }

        GoogleTokenPayload payload = googleTokenVerifierService.verifyToken(idTokenString);
        String verifiedEmail = payload.email();

        Optional<User> existingUserOpt = userRepository.findByEmail(verifiedEmail);
        User user;

        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();

            // Case 3: Existing email/password account
            // Do not automatically link an existing password account to Google merely because email addresses match.
            if (user.getAuthProvider() == AuthProvider.LOCAL) {
                throw new OAuthAccountConflictException(
                        "An account with this email already exists with password authentication. Please sign in with your email and password."
                );
            }

            // Check if account is active
            if (!user.isEnabled()) {
                throw new BadCredentialsException("Account is disabled");
            }

            // Update display name if user has none and Google provides one
            if ((user.getDisplayName() == null || user.getDisplayName().isBlank()) && payload.name() != null) {
                user.setDisplayName(payload.name());
                user = userRepository.save(user);
            }
        } else {
            // Case 1: New Google user registration
            String displayName = payload.name();
            if (displayName == null || displayName.isBlank()) {
                displayName = verifiedEmail.split("@")[0];
            }

            // Secure unpredictable random hash satisfies not-null constraint while disabling local password login
            String unusablePasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
            user = new User(UUID.randomUUID(), verifiedEmail, unusablePasswordHash, displayName);
            user.setAuthProvider(AuthProvider.GOOGLE);
            // CRITICAL: Google authentication strictly assigns default least-privilege ROLE_USER (CUSTOMER role).
            user.setRole(UserRole.ROLE_USER);
            user.setEnabled(true);

            user = userRepository.save(user);
        }

        // Generate the standard application JWT
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
                user.getCreatedAt(),
                user.getAuthProvider() != null ? user.getAuthProvider().name() : AuthProvider.LOCAL.name()
        );
    }
}
