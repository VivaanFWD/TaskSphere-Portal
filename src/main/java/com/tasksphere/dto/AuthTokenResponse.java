package com.tasksphere.dto;

import java.util.Set;

public class AuthTokenResponse {
    private String token;
    private String tokenType;
    private String username;
    private String displayName;
    private boolean passwordChangeRequired;
    private Set<String> roles;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTokenType() { return tokenType; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public void setPasswordChangeRequired(boolean passwordChangeRequired) { this.passwordChangeRequired = passwordChangeRequired; }
    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }
}
