package com.schoolbridge.api.tenant.dto;

import com.schoolbridge.api.tenant.SchoolStatus;
import com.schoolbridge.api.tenant.SubscriptionTier;
import java.time.Instant;
import java.util.UUID;

public record SchoolResponse(
    UUID id,
    String name,
    String country,
    String timezone,
    String locale,
    SubscriptionTier subscriptionTier,
    SchoolStatus status,
    SchoolSettingsResponse settings,
    Instant createdAt,
    Instant updatedAt) {}

