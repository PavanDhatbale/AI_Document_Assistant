package com.aidocassistant.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload containing the Google ID token obtained from
 * Google Identity Services on the client.
 */
public class GoogleAuthRequest {

    @NotBlank(message = "Google ID token is required")
    private String idToken;

    public GoogleAuthRequest() {
    }

    public GoogleAuthRequest(String idToken) {
        this.idToken = idToken;
    }

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }
}
