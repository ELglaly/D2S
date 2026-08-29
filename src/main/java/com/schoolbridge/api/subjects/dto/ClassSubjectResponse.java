package com.schoolbridge.api.subjects.dto;

import java.time.Instant;
import java.util.UUID;

public record ClassSubjectResponse(
    UUID id,
    UUID schoolId,
    UUID classId,
    UUID subjectId,
    String subjectName,
    String subjectCode,
    Instant createdAt) {}

