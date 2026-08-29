package com.schoolbridge.api.classes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateStudentRequest(
    @NotBlank @Size(max = 255) String fullName,
    @PastOrPresent LocalDate dateOfBirth,
    @Size(max = 100) String externalId) {}

