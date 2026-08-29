package com.schoolbridge.api.notifications.dto;

import com.schoolbridge.api.integrations.NotificationChannel;
import com.schoolbridge.api.notifications.NotificationCategory;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * One category's preference, in both directions.
 *
 * <p>{@code channels} is ordered and the order is the preference. It is {@code @NotEmpty} because
 * an empty list is not how a user opts out â€” {@code enabled = false} is â€” and silently accepting an
 * empty list would produce a preference that can never deliver anything while still reading as
 * "on".
 *
 * @param category which kind of message
 * @param enabled false opts the user out entirely; ignored for non-mutable categories
 * @param channels ordered channels to try, first success wins
 */
public record CategoryPreference(
    @NotNull NotificationCategory category,
    boolean enabled,
    @NotEmpty List<NotificationChannel> channels) {}

