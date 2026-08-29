package com.schoolbridge.api.subjects.dto;

import com.schoolbridge.api.subjects.SubjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateSubjectRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 50) String code,
    @Size(max = 1024) String description,
    @NotNull SubjectStatus status) {}

