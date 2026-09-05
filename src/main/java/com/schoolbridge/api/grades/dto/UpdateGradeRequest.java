package com.schoolbridge.api.grades.dto;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record UpdateGradeRequest(
    BigDecimal score, @Size(max = 10) String gradeLabel, @Size(max = 2000) String notes) {}
