package com.schoolbridge.api.subjects.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSubjectRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 50) String code,
    @Size(max = 1024) String description) {}
