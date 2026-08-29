package com.schoolbridge.api.homework.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Request body for {@code PATCH /api/v1/homework/{id}}. All fields are required on update. */
public record UpdateHomeworkRequest(
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 4000) String description,
    @Size(max = 512) String attachmentKey,
    @NotNull @Future LocalDate dueDate) {}

