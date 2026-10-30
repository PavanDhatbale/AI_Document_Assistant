package com.aidocassistant.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Public profile details returned to authenticated clients.
 * Never exposes password hashes or security secrets.
 */
public class UserProfileResponse {

    private UUID id;
    private String email;
    private String displayName;
    private String phoneNumber;
    private String role;
    private Instant createdAt;

    public UserProfileResponse() {
    }

    public UserProfileResponse(UUID id, String email, String displayName, String phoneNumber, String role, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
        this.role = role;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
