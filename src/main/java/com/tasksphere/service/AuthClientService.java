package com.tasksphere.service;

import com.tasksphere.dto.AuthTokenResponse;
import com.tasksphere.dto.LoginRequest;
import com.tasksphere.dto.ProvisionUserRequest;
import com.tasksphere.dto.ProvisionUserResponse;
import com.tasksphere.dto.UserIdentityResponse;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;

@Service
public class AuthClientService {
    private final RestTemplate restTemplate;
    private final String authServiceBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String adminApiKey;

    public AuthClientService(RestTemplate restTemplate,
                            @Value("${app.auth-service-base-url:http://localhost:8081}") String authServiceBaseUrl,
                            @Value("${app.auth-client-id:tasksphere-app}") String clientId,
                            @Value("${app.auth-client-secret:local-only-client-secret-change-before-deploying}") String clientSecret,
                            @Value("${app.auth-admin-api-key:}") String adminApiKey) {
        this.restTemplate = restTemplate;
        this.authServiceBaseUrl = authServiceBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.adminApiKey = adminApiKey;
    }

    public AuthTokenResponse login(String username, String password) {
        HttpHeaders headers = clientHeaders();
        HttpEntity<LoginRequest> entity = new HttpEntity<>(new LoginRequest(username, password), headers);
        ResponseEntity<AuthTokenResponse> response = restTemplate.postForEntity(
            authServiceBaseUrl + "/api/v1/auth/login",
            entity,
            AuthTokenResponse.class
        );
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Authentication service rejected the credentials.");
        }
        return response.getBody();
    }

    public UserIdentityResponse me(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", bearerToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<UserIdentityResponse> response = restTemplate.exchange(
            authServiceBaseUrl + "/api/v1/auth/me",
            org.springframework.http.HttpMethod.GET,
            entity,
            UserIdentityResponse.class
        );
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Unable to fetch current user from auth service.");
        }
        return response.getBody();
    }

    public ProvisionUserResponse provisionUser(
        String username,
        String firstName,
        String lastName,
        String email,
        String initialPassword,
        String role
    ) {
        if (adminApiKey.isBlank()) {
            throw new IllegalStateException("Account provisioning is not configured. Set AUTH_ADMIN_API_KEY on the app server.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-API-Key", adminApiKey);
        ProvisionUserRequest body = new ProvisionUserRequest(
            username,
            firstName,
            lastName,
            email,
            initialPassword,
            Set.of(role)
        );
        ResponseEntity<ProvisionUserResponse> response;
        try {
            response = restTemplate.postForEntity(
                authServiceBaseUrl + "/api/v1/admin/users",
                new HttpEntity<>(body, headers),
                ProvisionUserResponse.class
            );
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 409) {
                throw ex;
            }
            response = restTemplate.exchange(
                authServiceBaseUrl + "/api/v1/admin/users/by-username/{username}",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                ProvisionUserResponse.class,
                username
            );
            ProvisionUserResponse existing = response.getBody();
            if (existing == null
                || !existing.username().equals(username)
                || !existing.email().equalsIgnoreCase(email)
                || existing.roles() == null
                || !existing.roles().contains(role)) {
                throw new IllegalStateException("Existing account does not match the requested identity and role.");
            }
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Authentication service did not confirm account provisioning.");
        }
        return response.getBody();
    }

    public ProvisionUserResponse getUserByUsername(String username) {
        requireAdminApiKey();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-API-Key", adminApiKey);
        ResponseEntity<ProvisionUserResponse> response = restTemplate.exchange(
            authServiceBaseUrl + "/api/v1/admin/users/by-username/{username}",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers),
            ProvisionUserResponse.class,
            username
        );
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Authentication service did not return the account.");
        }
        return response.getBody();
    }

    public ProvisionUserResponse updateUserStatus(UUID userId, String status) {
        requireAdminApiKey();
        if (!Set.of("ACTIVE", "FROZEN", "BLOCKED", "DEACTIVATED").contains(status)) {
            throw new IllegalArgumentException("Unsupported account status.");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-API-Key", adminApiKey);
        ResponseEntity<ProvisionUserResponse> response = restTemplate.exchange(
            authServiceBaseUrl + "/api/v1/admin/users/{id}/status",
            org.springframework.http.HttpMethod.PATCH,
            new HttpEntity<>(Map.of("status", status), headers),
            ProvisionUserResponse.class,
            userId
        );
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Authentication service did not confirm the status change.");
        }
        return response.getBody();
    }

    private void requireAdminApiKey() {
        if (adminApiKey.isBlank()) {
            throw new IllegalStateException("Account administration is not configured. Set AUTH_ADMIN_API_KEY on the app server.");
        }
    }

    public void changePassword(String bearerToken, String currentPassword, String newPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", bearerToken);
        MapRequest body = new MapRequest(currentPassword, newPassword, newPassword);
        ResponseEntity<Void> response = restTemplate.postForEntity(
            authServiceBaseUrl + "/api/v1/auth/password/change",
            new HttpEntity<>(body, headers),
            Void.class
        );
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Authentication service rejected the password change.");
        }
    }

    public void requestPasswordReset(String username) {
        requestRecovery("/api/v1/auth/password/forgot",
            Map.of("username", username, "purpose", "PASSWORD_RESET"));
    }

    public void resetPassword(String username, String otp, String newPassword) {
        requestRecovery("/api/v1/auth/password/reset",
            Map.of("username", username, "otp", otp, "newPassword", newPassword));
    }

    public void requestUserIdRecovery(String email) {
        requestRecovery("/api/v1/auth/user-id/recovery/request", Map.of("email", email));
    }

    public String verifyUserIdRecovery(String email, String otp) {
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(
            Map.of("email", email, "otp", otp), clientHeaders());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            authServiceBaseUrl + "/api/v1/auth/user-id/recovery/verify",
            org.springframework.http.HttpMethod.POST,
            entity,
            new org.springframework.core.ParameterizedTypeReference<>() {});
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null
            || !Boolean.TRUE.equals(response.getBody().get("verified"))) {
            throw new IllegalStateException("The verification code was not accepted.");
        }
        return Objects.toString(response.getBody().get("username"), "");
    }

    private void requestRecovery(String path, Map<String, String> body) {
        ResponseEntity<Map<String, String>> response = restTemplate.exchange(
            authServiceBaseUrl + path,
            org.springframework.http.HttpMethod.POST,
            new HttpEntity<>(body, clientHeaders()),
            new org.springframework.core.ParameterizedTypeReference<>() {});
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Authentication service rejected the recovery request.");
        }
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Client-ID", clientId);
        headers.set("X-Client-Secret", clientSecret);
        return headers;
    }

    private record MapRequest(String currentPassword, String newPassword, String confirmPassword) {}
}
