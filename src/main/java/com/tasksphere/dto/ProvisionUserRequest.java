package com.tasksphere.dto;

import java.util.Set;

public record ProvisionUserRequest(
    String username,
    String firstName,
    String lastName,
    String email,
    String password,
    Set<String> roles
) {}
