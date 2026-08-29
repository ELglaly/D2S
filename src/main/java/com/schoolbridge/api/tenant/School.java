package com.schoolbridge.api.tenant;

import com.schoolbridge.api.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * The tenant root aggregate. Every other tenant-scoped row carries this aggregate's id as {@code
 * school_id}. {@link SchoolSettings} is embedded â€” same table â€” because every read of a school will
 * want its settings too.
 */
@Entity
@Table(name = "schools")
public class School extends BaseEntity {

  @Column(nullable = false, length = 120)
  private String name;

  @Column(nullable = false, length = 2)
  private String country;

  @Column(nullable = false, length = 64)
  private String timezone;

  @Column(nullable = false, length = 35)
  private String locale;

  @Enumerated(EnumType.STRING)
  @Column(name = "subscription_tier", nullable = false, length = 20)
  private SubscriptionTier subscriptionTier;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SchoolStatus status;

  @Embedded private SchoolSettings settings;

  protected School() {}

  public School(
      String name,
      String country,
      String timezone,
      String locale,
      SubscriptionTier subscriptionTier,
      SchoolSettings settings) {
    this.name = name;
    this.country = country;
    this.timezone = timezone;
    this.locale = locale;
    this.subscriptionTier = subscriptionTier;
    this.status = SchoolStatus.ACTIVE;
    this.settings = settings;
  }

  /** Marks the school suspended; idempotency is enforced by the service, not the entity. */
  public void suspend() {
    this.status = SchoolStatus.SUSPENDED;
  }

  public void reactivate() {
    this.status = SchoolStatus.ACTIVE;
  }

  public void replaceSettings(SchoolSettings newSettings) {
    this.settings = newSettings;
  }

  public String getName() {
    return name;
  }

  public String getCountry() {
    return country;
  }

  public String getTimezone() {
    return timezone;
  }

  public String getLocale() {
    return locale;
  }

  public SubscriptionTier getSubscriptionTier() {
    return subscriptionTier;
  }

  public SchoolStatus getStatus() {
    return status;
  }

  public SchoolSettings getSettings() {
    return settings;
  }
}

