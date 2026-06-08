package com.schoolbridge.api.tenant.events;

import java.util.UUID;

public record SchoolSettingsUpdatedEvent(UUID schoolId) {}
