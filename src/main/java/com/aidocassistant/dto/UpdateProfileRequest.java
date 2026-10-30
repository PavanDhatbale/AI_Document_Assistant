package com.aidocassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for updating editable user profile fields.
 * Explicitly excludes email, role, enabled status, and password to prevent unauthorized tampering.
 */
public class UpdateProfileRequest {

    @NotBlank(message = "Display name is required")
    @Size(min = 2, max = 100, message = "Display name must be between 2 and 100 characters")
    private String displayName;

    @Size(max = 30, message = "Phone number cannot exceed 30 characters")
    private String phoneNumber;

    public UpdateProfileRequest() {
    }

    public UpdateProfileRequest(String displayName, String phoneNumber) {
        this.displayName = displayName;
        this.phoneNumber = phoneNumber;
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
}
