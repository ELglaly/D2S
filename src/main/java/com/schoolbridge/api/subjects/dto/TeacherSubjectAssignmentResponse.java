package com.schoolbridge.api.subjects.dto;

import java.time.Instant;
import java.util.UUID;

public record TeacherSubjectAssignmentResponse(
    UUID id, UUID schoolId, UUID teacherUserId, UUID classId, UUID subjectId, Instant createdAt) {}

