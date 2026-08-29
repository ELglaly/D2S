package com.schoolbridge.api.subjects.dto;

import java.util.UUID;

public record StudentSubjectResponse(
    UUID subjectId, String name, String code, UUID classId, UUID teacherUserId) {}

