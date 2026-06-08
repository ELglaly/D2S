package com.schoolbridge.api.classes.dto;

import java.time.Instant;
import java.util.UUID;

public record EnrollmentResponse(
    UUID id, UUID schoolId, UUID studentId, UUID classId, Instant createdAt) {}
