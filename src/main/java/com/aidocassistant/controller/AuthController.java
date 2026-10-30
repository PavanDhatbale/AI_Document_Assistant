package com.aidocassistant.controller;

import com.aidocassistant.dto.AuthResponse;
import com.aidocassistant.dto.ChangePasswordRequest;
import com.aidocassistant.dto.LoginRequest;
import com.aidocassistant.dto.RegisterRequest;
import com.aidocassistant.dto.SimpleMessageResponse;
import com.aidocassistant.dto.UpdateProfileRequest;
import com.aidocassistant.dto.UserProfileResponse;
import com.aidocassistant.security.SecurityUserPrincipal;
import com.aidocassistant.service.AuthService;
import com.aidocassistant.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing user registration, login, and profile lookup.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(@AuthenticationPrincipal SecurityUserPrincipal principal) {
        UserProfileResponse profile = authService.getCurrentUserProfile(principal);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/profile")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal SecurityUserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserProfileResponse profile = userService.updateProfile(principal, request);
        return ResponseEntity.ok(profile);
    }

    @PutMapping("/password")
    public ResponseEntity<SimpleMessageResponse> changePassword(
            @AuthenticationPrincipal SecurityUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        SimpleMessageResponse response = userService.changePassword(principal, request);
        return ResponseEntity.ok(response);
    }
}
