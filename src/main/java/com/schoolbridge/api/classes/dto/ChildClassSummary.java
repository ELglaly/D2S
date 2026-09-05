package com.schoolbridge.api.classes.dto;

import java.util.UUID;

public record ChildClassSummary(
    UUID classId, String name, String gradeLevel, String academicYear) {}
