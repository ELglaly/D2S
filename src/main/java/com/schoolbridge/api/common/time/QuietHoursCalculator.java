package com.schoolbridge.api.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Pure helpers for a quiet-hours window. Handles both same-day windows ({@code 13:00 → 15:00}) and
 * the more common wrap-around windows ({@code 21:00 → 07:00}). All decisions are made in the
 * school-local timezone so DST transitions and Egypt's UTC+2/+3 swaps are correct out of the box.
 *
 * <p>A zero-length window ({@code start == end}) is treated as "never in window".
 *
 * <p>Lives in {@code common} rather than {@code attendance} (where it was written for M8) because
 * three modules now evaluate the same window: attendance alerts, homework reminders, and the
 * per-user preferences resolver.
 */
public final class QuietHoursCalculator {

  private QuietHoursCalculator() {}

  /** Returns true if {@code now} falls inside the quiet window. */
  public static boolean isInQuietWindow(Instant now, ZoneId zone, LocalTime start, LocalTime end) {
    if (start.equals(end)) {
      return false;
    }
    LocalTime localNow = ZonedDateTime.ofInstant(now, zone).toLocalTime();
    if (start.isBefore(end)) {
      // Same-day window.
      return !localNow.isBefore(start) && localNow.isBefore(end);
    }
    // Wrap-around: in window if either after start (today) or before end (tomorrow morning).
    return !localNow.isBefore(start) || localNow.isBefore(end);
  }

  /**
   * Returns the next instant when the quiet window closes, assuming {@code now} is inside the
   * window. If {@code now} is outside the window, returns {@code now} unchanged (defensive — the
   * caller should not have invoked this path).
   */
  public static Instant nextEndOfWindow(Instant now, ZoneId zone, LocalTime start, LocalTime end) {
    if (!isInQuietWindow(now, zone, start, end)) {
      return now;
    }
    ZonedDateTime nowLocal = ZonedDateTime.ofInstant(now, zone);
    LocalDate today = nowLocal.toLocalDate();
    LocalTime localNow = nowLocal.toLocalTime();
    LocalDate endDate;
    if (start.isBefore(end)) {
      // Same-day window: end is today.
      endDate = today;
    } else if (localNow.isBefore(end)) {
      // Wrap-around, currently in the tail-end-on-the-next-day half: end is today.
      endDate = today;
    } else {
      // Wrap-around, currently in the after-start-tonight half: end is tomorrow.
      endDate = today.plusDays(1);
    }
    return ZonedDateTime.of(endDate, end, zone).toInstant();
  }
}
