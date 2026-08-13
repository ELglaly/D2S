package com.schoolbridge.api.notifications.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;

/**
 * Replaces a user's whole preference set. A whole-set PUT rather than per-field PATCH so two
 * concurrent edits from two devices cannot interleave into a state the user never chose.
 *
 * <p>{@code quietHoursStart}/{@code quietHoursEnd} may both be null, meaning "inherit the school's
 * window"; supplying one without the other is rejected, since half a window has no meaning.
 * Categories omitted from {@code preferences} keep their stored value rather than being reset.
 *
 * @param respectQuietHours whether the window applies to this user at all
 * @param quietHoursStart local start of the user's own window, or null to inherit
 * @param quietHoursEnd local end of the user's own window, or null to inherit
 * @param preferences per-category opt-out and channel order
 */
public record NotificationPreferencesRequest(
    boolean respectQuietHours,
    LocalTime quietHoursStart,
    LocalTime quietHoursEnd,
    @NotNull @Valid List<CategoryPreference> preferences) {}
