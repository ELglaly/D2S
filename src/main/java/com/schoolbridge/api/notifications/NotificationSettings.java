package com.schoolbridge.api.notifications;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One row per user, holding only the quiet-hours window.
 *
 * <p>Separate from {@link NotificationPreference} because the two have different grains: a person
 * means one thing by "do not message me at night", while opt-out and channel order are naturally
 * per-category. Storing the window on every category row would let the three copies disagree.
 *
 * <p>A null {@code quietHoursStart}/{@code quietHoursEnd} means "inherit the school window" from
 * {@code SchoolSettings}, so a school that retimes its window does not leave every user pinned to
 * the old one. The absence of a row entirely means defaults â€” never "muted".
 */
@Entity
@Table(name = "notification_settings")
public class NotificationSettings extends TenantEntity {

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "respect_quiet_hours", nullable = false)
  private boolean respectQuietHours;

  @Column(name = "quiet_hours_start")
  private LocalTime quietHoursStart;

  @Column(name = "quiet_hours_end")
  private LocalTime quietHoursEnd;

  protected NotificationSettings() {}

  public NotificationSettings(
      UUID schoolId,
      UUID userId,
      boolean respectQuietHours,
      LocalTime quietHoursStart,
      LocalTime quietHoursEnd) {
    super(schoolId);
    this.userId = userId;
    this.respectQuietHours = respectQuietHours;
    this.quietHoursStart = quietHoursStart;
    this.quietHoursEnd = quietHoursEnd;
  }

  /** Replaces the window wholesale. Nulls restore inheritance from the school setting. */
  public void update(boolean respectQuietHours, LocalTime start, LocalTime end) {
    this.respectQuietHours = respectQuietHours;
    this.quietHoursStart = start;
    this.quietHoursEnd = end;
  }

  public UUID getUserId() {
    return userId;
  }

  public boolean isRespectQuietHours() {
    return respectQuietHours;
  }

  public LocalTime getQuietHoursStart() {
    return quietHoursStart;
  }

  public LocalTime getQuietHoursEnd() {
    return quietHoursEnd;
  }
}
