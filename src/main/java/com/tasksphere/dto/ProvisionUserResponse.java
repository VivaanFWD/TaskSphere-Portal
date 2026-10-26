package com.tasksphere.dto;

import java.util.Set;
import java.util.UUID;

public record ProvisionUserResponse(
    UUID id,
    String username,
    String firstName,
    String lastName,
    String email,
    String status,
    boolean passwordChangeRequired,
    Set<String> roles
) {}
