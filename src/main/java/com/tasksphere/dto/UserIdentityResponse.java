package com.tasksphere.dto;

import java.util.Set;

public record UserIdentityResponse(
    String username,
    String firstName,
    String lastName,
    String email,
    String status,
    boolean passwordChangeRequired,
    Set<String> roles
) {}
