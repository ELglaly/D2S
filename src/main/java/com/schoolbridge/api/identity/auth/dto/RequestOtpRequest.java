package com.schoolbridge.api.identity.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RequestOtpRequest(
    @NotBlank @Size(max = 32) @Pattern(regexp = "^\\+[1-9]\\d{6,14}$") String phone) {}
