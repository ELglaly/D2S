package com.schoolbridge.api.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
    @NotBlank String ticketId, @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {}
