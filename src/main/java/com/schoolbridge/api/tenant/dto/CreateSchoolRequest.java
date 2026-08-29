package com.schoolbridge.api.tenant.dto;

import com.schoolbridge.api.tenant.SubscriptionTier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload to provision a new school tenant. {@code settings} is optional; defaults apply when
 * omitted.
 */
public record CreateSchoolRequest(
    @NotBlank @Size(min = 2, max = 120) String name,
    @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String country,
    @NotBlank @Size(max = 64) String timezone,
    @NotBlank @Size(max = 35) String locale,
    @NotNull SubscriptionTier subscriptionTier,
    @Valid SchoolSettingsRequest settings) {}

