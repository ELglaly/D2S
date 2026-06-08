package com.schoolbridge.api.grades.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateGradeRequest(
    @NotNull UUID studentId,
    @NotNull UUID classId,
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 50) String period,
    BigDecimal score,
    @Size(max = 10) String gradeLabel,
    @Size(max = 2000) String notes) {}
