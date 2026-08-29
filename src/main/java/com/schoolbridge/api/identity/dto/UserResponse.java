package com.schoolbridge.api.identity.dto;

import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.UserStatus;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(
    UUID id,
    UUID schoolId,
    UserRole role,
    String name,
    String email,
    UserStatus status,
    Instant createdAt,
    Instant updatedAt) {}

