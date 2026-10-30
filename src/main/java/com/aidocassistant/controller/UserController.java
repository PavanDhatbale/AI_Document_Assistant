package com.aidocassistant.controller;

import com.aidocassistant.dto.ChangePasswordRequest;
import com.aidocassistant.dto.SimpleMessageResponse;
import com.aidocassistant.dto.UpdateProfileRequest;
import com.aidocassistant.dto.UserProfileResponse;
import com.aidocassistant.security.SecurityUserPrincipal;
import com.aidocassistant.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller providing user profile management and password modification.
 * All endpoints resolve the caller strictly from the server-validated security principal.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Retrieves the profile of the authenticated user.
     */
    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(@AuthenticationPrincipal SecurityUserPrincipal principal) {
        UserProfileResponse response = userService.getProfile(principal);
        return ResponseEntity.ok(response);
    }

    /**
     * Updates editable profile fields for the authenticated user.
     */
    @PutMapping("/profile")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @AuthenticationPrincipal SecurityUserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserProfileResponse response = userService.updateProfile(principal, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Securely updates the authenticated user's password.
     */
    @PutMapping("/password")
    public ResponseEntity<SimpleMessageResponse> changePassword(
            @AuthenticationPrincipal SecurityUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        SimpleMessageResponse response = userService.changePassword(principal, request);
        return ResponseEntity.ok(response);
    }
}
