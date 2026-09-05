package com.schoolbridge.api.classes.dto;

import java.time.Instant;
import java.util.UUID;

public record SchoolClassResponse(
    UUID id,
    UUID schoolId,
    String name,
    String gradeLevel,
    String academicYear,
    UUID homeroomTeacherId,
    Instant createdAt,
    Instant updatedAt) {}
