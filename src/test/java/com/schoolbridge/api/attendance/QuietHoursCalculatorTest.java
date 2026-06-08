package com.schoolbridge.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the quiet-hours helper. Covers both wrap-around windows ({@code 21:00 → 07:00})
 * and same-day windows ({@code 13:00 → 15:00}), plus the zero-length degenerate case.
 */
class QuietHoursCalculatorTest {

  private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

  @Test
  void wrapAround_atNightAfterStart_isInWindow_endsAtTomorrowMorning() {
    Instant night = localCairo(2026, 5, 31, 22, 30);
    boolean inWindow =
        QuietHoursCalculator.isInQuietWindow(night, CAIRO, LocalTime.of(21, 0), LocalTime.of(7, 0));
    Instant end =
        QuietHoursCalculator.nextEndOfWindow(night, CAIRO, LocalTime.of(21, 0), LocalTime.of(7, 0));
    assertThat(inWindow).isTrue();
    assertThat(end).isEqualTo(localCairo(2026, 6, 1, 7, 0));
  }

  @Test
  void wrapAround_earlyMorningBeforeEnd_isInWindow_endsToday() {
    Instant earlyMorning = localCairo(2026, 6, 1, 5, 15);
    boolean inWindow =
        QuietHoursCalculator.isInQuietWindow(
            earlyMorning, CAIRO, LocalTime.of(21, 0), LocalTime.of(7, 0));
    Instant end =
        QuietHoursCalculator.nextEndOfWindow(
            earlyMorning, CAIRO, LocalTime.of(21, 0), LocalTime.of(7, 0));
    assertThat(inWindow).isTrue();
    assertThat(end).isEqualTo(localCairo(2026, 6, 1, 7, 0));
  }

  @Test
  void wrapAround_midAfternoon_isNotInWindow() {
    Instant noon = localCairo(2026, 5, 31, 12, 0);
    assertThat(
            QuietHoursCalculator.isInQuietWindow(
                noon, CAIRO, LocalTime.of(21, 0), LocalTime.of(7, 0)))
        .isFalse();
  }

  @Test
  void sameDay_insideWindow_endsAtTodayClose() {
    Instant lunch = localCairo(2026, 5, 31, 14, 0);
    boolean inWindow =
        QuietHoursCalculator.isInQuietWindow(
            lunch, CAIRO, LocalTime.of(13, 0), LocalTime.of(15, 0));
    Instant end =
        QuietHoursCalculator.nextEndOfWindow(
            lunch, CAIRO, LocalTime.of(13, 0), LocalTime.of(15, 0));
    assertThat(inWindow).isTrue();
    assertThat(end).isEqualTo(localCairo(2026, 5, 31, 15, 0));
  }

  @Test
  void sameDay_outsideWindow_returnsFalse() {
    Instant morning = localCairo(2026, 5, 31, 9, 0);
    assertThat(
            QuietHoursCalculator.isInQuietWindow(
                morning, CAIRO, LocalTime.of(13, 0), LocalTime.of(15, 0)))
        .isFalse();
  }

  @Test
  void zeroLengthWindow_neverApplies() {
    Instant any = localCairo(2026, 5, 31, 22, 0);
    assertThat(
            QuietHoursCalculator.isInQuietWindow(
                any, CAIRO, LocalTime.of(21, 0), LocalTime.of(21, 0)))
        .isFalse();
  }

  private static Instant localCairo(int y, int m, int d, int h, int min) {
    return ZonedDateTime.of(LocalDate.of(y, m, d), LocalTime.of(h, min), CAIRO).toInstant();
  }
}
