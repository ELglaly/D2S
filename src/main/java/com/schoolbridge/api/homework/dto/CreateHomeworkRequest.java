package com.schoolbridge.api.homework.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/homework}.
 *
 * <p>When {@code publish} is {@code true} the item is created as PUBLISHED and recipient rows are
 * materialized immediately. When {@code false} (default) the item is created as DRAFT.
 */
public record CreateHomeworkRequest(
    @NotNull UUID classId,
    @NotBlank @Size(max = 200) String subject,
    @NotBlank @Size(max = 4000) String description,
    @Size(max = 512) String attachmentKey,
    @NotNull @Future LocalDate dueDate,
    boolean requiresAck,
    boolean publish) {}
