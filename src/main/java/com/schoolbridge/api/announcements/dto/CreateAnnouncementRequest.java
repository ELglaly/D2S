package com.schoolbridge.api.announcements.dto;

import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.enums.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/announcements}.
 *
 * <p>Scope semantics:
 *
 * <ul>
 *   <li>{@link AnnouncementScope#SCHOOL}: {@code classId}, {@code gradeLevel}, and {@code
 *       recipientStudentIds} must all be absent.
 *   <li>{@link AnnouncementScope#GRADE}: {@code gradeLevel} is required.
 *   <li>{@link AnnouncementScope#CLASS}: {@code classId} is required.
 *   <li>{@link AnnouncementScope#CUSTOM}: {@code recipientStudentIds} is required and non-empty.
 * </ul>
 *
 * Service-layer validation rejects any other combination with {@code
 * error.announcement.invalid_scope} (HTTP 422).
 */
public record CreateAnnouncementRequest(
    @NotNull AnnouncementScope scopeType,
    UUID classId,
    @Size(max = 100) String gradeLevel,
    List<UUID> recipientStudentIds,
    @NotNull Language language,
    @NotBlank @Size(max = 4000) String body,
    @Size(max = 512) String attachmentKey,
    boolean requiresAck,
    Instant scheduledFor) {}
