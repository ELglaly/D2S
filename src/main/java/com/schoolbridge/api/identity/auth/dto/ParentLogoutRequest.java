package com.schoolbridge.api.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ParentLogoutRequest(@NotBlank String token) {}
