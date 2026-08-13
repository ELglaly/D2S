package com.schoolbridge.api.notifications;

import com.schoolbridge.api.common.tenancy.TenantEntity;
import com.schoolbridge.api.integrations.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per (user, category): whether the user wants the category at all, and in which channel
 * order they want it.
 *
 * <p>The {@code channels} list is ordered and the order <em>is</em> the preference — {@code [PUSH,
 * WHATSAPP, SMS]} means try push first and fall through — so it is stored as a JSONB array rather
 * than normalised into a set, which would lose exactly the information that matters.
 *
 * <p>An empty channel list is not the way to opt out; {@code enabled = false} is. The distinction
 * matters for {@link NotificationCategory#ATTENDANCE}, where neither is honoured.
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference extends TenantEntity {

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, updatable = false, length = 20)
  private NotificationCategory category;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "channels", nullable = false, columnDefinition = "jsonb")
  private List<NotificationChannel> channels;

  protected NotificationPreference() {}

  public NotificationPreference(
      UUID schoolId,
      UUID userId,
      NotificationCategory category,
      boolean enabled,
      List<NotificationChannel> channels) {
    super(schoolId);
    this.userId = userId;
    this.category = category;
    this.enabled = enabled;
    this.channels = List.copyOf(channels);
  }

  /**
   * Replaces both fields at once; callers hand in a new list rather than mutating the stored one.
   */
  public void update(boolean enabled, List<NotificationChannel> channels) {
    this.enabled = enabled;
    this.channels = List.copyOf(channels);
  }

  public UUID getUserId() {
    return userId;
  }

  public NotificationCategory getCategory() {
    return category;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public List<NotificationChannel> getChannels() {
    return channels == null ? List.of() : List.copyOf(channels);
  }
}
