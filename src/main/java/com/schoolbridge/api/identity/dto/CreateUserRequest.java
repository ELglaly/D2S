package com.schoolbridge.api.identity.dto;

import com.schoolbridge.api.identity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotNull UserRole role,
    @NotBlank @Size(max = 255) String name,
    @Email @Size(max = 255) String email,
    @Size(max = 30) String phone,
    @Size(min = 8, max = 128) String password) {}

