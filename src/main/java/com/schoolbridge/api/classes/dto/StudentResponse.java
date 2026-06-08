package com.schoolbridge.api.classes.dto;

import com.schoolbridge.api.classes.StudentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StudentResponse(
    UUID id,
    UUID schoolId,
    String fullName,
    LocalDate dateOfBirth,
    String externalId,
    StudentStatus status,
    Instant createdAt,
    Instant updatedAt) {}
