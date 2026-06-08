package com.schoolbridge.api.classes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdateSchoolClassRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 100) String gradeLevel,
    @NotBlank @Size(max = 20) String academicYear,
    UUID homeroomTeacherId) {}
