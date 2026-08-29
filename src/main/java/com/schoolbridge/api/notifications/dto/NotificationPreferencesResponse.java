package com.schoolbridge.api.notifications.dto;

import java.time.LocalTime;
import java.util.List;

/**
 * A user's complete notification configuration.
 *
 * <p>Every category appears, whether or not a row exists â€” the client should never have to know
 * which defaults are materialised and which are stored, and a missing entry would otherwise read as
 * "off". {@code effectiveQuietHours*} are what will actually be applied after inheritance from the
 * school is resolved, so the app can render the real window without fetching school settings.
 *
 * @param respectQuietHours whether the window applies to this user
 * @param quietHoursStart the user's own override, null when inheriting
 * @param quietHoursEnd the user's own override, null when inheriting
 * @param effectiveQuietHoursStart the window actually in force
 * @param effectiveQuietHoursEnd the window actually in force
 * @param preferences every category, defaults included
 */
public record NotificationPreferencesResponse(
    boolean respectQuietHours,
    LocalTime quietHoursStart,
    LocalTime quietHoursEnd,
    LocalTime effectiveQuietHoursStart,
    LocalTime effectiveQuietHoursEnd,
    List<CategoryPreference> preferences) {}

