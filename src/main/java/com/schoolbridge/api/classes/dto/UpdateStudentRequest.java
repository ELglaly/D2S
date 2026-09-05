package com.schoolbridge.api.classes.dto;

import com.schoolbridge.api.classes.StudentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateStudentRequest(
    @NotBlank @Size(max = 255) String fullName,
    @PastOrPresent LocalDate dateOfBirth,
    @Size(max = 100) String externalId,
    @NotNull StudentStatus status) {}
