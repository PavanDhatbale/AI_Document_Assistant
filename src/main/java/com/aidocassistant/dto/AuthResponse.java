package com.aidocassistant.dto;

/**
 * Authentication response containing JWT token and basic profile info.
 */
public class AuthResponse {

    private String token;
    private String tokenType = "Bearer";
    private long expiresIn;
    private UserProfileResponse user;

    public AuthResponse() {
    }

    public AuthResponse(String token, long expiresIn, UserProfileResponse user) {
        this.token = token;
        this.tokenType = "Bearer";
        this.expiresIn = expiresIn;
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserProfileResponse getUser() {
        return user;
    }

    public void setUser(UserProfileResponse user) {
        this.user = user;
    }
}
