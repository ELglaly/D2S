package com.schoolbridge.api.subjects.dto;

import com.schoolbridge.api.subjects.SubjectStatus;
import java.time.Instant;
import java.util.UUID;

public record SubjectResponse(
    UUID id,
    UUID schoolId,
    String name,
    String code,
    String description,
    SubjectStatus status,
    Instant createdAt,
    Instant updatedAt) {}
