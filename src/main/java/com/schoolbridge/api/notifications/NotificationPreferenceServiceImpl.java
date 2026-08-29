package com.schoolbridge.api.notifications;

import com.schoolbridge.api.common.error.ValidationException;
import com.schoolbridge.api.common.time.QuietHoursCalculator;
import com.schoolbridge.api.integrations.NotificationChannel;
import com.schoolbridge.api.notifications.dto.CategoryPreference;
import com.schoolbridge.api.notifications.dto.NotificationPreferencesRequest;
import com.schoolbridge.api.notifications.dto.NotificationPreferencesResponse;
import com.schoolbridge.api.tenant.School;
import com.schoolbridge.api.tenant.SchoolRepository;
import com.schoolbridge.api.tenant.SchoolSettings;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** See {@link NotificationPreferenceService}. */
@Service
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

  private static final Logger log =
      LoggerFactory.getLogger(NotificationPreferenceServiceImpl.class);

  private final NotificationSettingsRepository settingsRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final SchoolRepository schools;

  public NotificationPreferenceServiceImpl(
      NotificationSettingsRepository settingsRepository,
      NotificationPreferenceRepository preferenceRepository,
      SchoolRepository schools) {
    this.settingsRepository = settingsRepository;
    this.preferenceRepository = preferenceRepository;
    this.schools = schools;
  }

  @Override
  @Transactional(readOnly = true)
  public NotificationDecision resolve(
      UUID schoolId, UUID userId, NotificationCategory category, Instant now) {
    Optional<NotificationPreference> preference =
        preferenceRepository.findByUserIdAndCategory(userId, category);

    if (!category.isMutable()) {
      // Attendance. The stored channel order is honoured because a parent who chose push should get
      // push -- but it is applied as a REORDERING, never a filter: any channel the user dropped is
      // appended back at the end. Dropping WhatsApp from an absence alert would be an opt-out
      // wearing a channel preference's clothes, and this category has none.
      List<NotificationChannel> ordered =
          preference.map(p -> reorderWithoutRemoving(p.getChannels())).orElse(defaultOrder());
      return NotificationDecision.sendNow(ordered);
    }

    List<NotificationChannel> channels =
        preference
            .map(NotificationPreference::getChannels)
            .filter(list -> !list.isEmpty())
            .orElse(defaultOrder());

    if (preference.isPresent() && !preference.get().isEnabled()) {
      return NotificationDecision.suppress(channels);
    }

    School school = schools.findById(schoolId).orElse(null);
    if (school == null) {
      // Fail open, deliberately. Failing closed here would silently mute a parent for a reason that
      // has nothing to do with what they asked for.
      log.warn("notification_preference_school_missing schoolId={} userId={}", schoolId, userId);
      return NotificationDecision.sendNow(channels);
    }

    Optional<NotificationSettings> settings = settingsRepository.findByUserId(userId);
    SchoolSettings schoolSettings = school.getSettings();
    boolean respectQuietHours =
        settings
            .map(NotificationSettings::isRespectQuietHours)
            // The school-wide flag is the default so that a deployment where nobody has touched
            // their preferences behaves exactly as it did before this feature existed.
            .orElseGet(schoolSettings::isAlertsRespectQuietHours);
    if (!respectQuietHours) {
      return NotificationDecision.sendNow(channels);
    }

    LocalTime start = effectiveStart(settings.orElse(null), schoolSettings);
    LocalTime end = effectiveEnd(settings.orElse(null), schoolSettings);
    ZoneId zone = zoneOf(school);
    if (!QuietHoursCalculator.isInQuietWindow(now, zone, start, end)) {
      return NotificationDecision.sendNow(channels);
    }
    return NotificationDecision.defer(
        QuietHoursCalculator.nextEndOfWindow(now, zone, start, end), channels);
  }

  @Override
  @Transactional(readOnly = true)
  public NotificationPreferencesResponse get(UUID schoolId, UUID userId) {
    NotificationSettings settings = settingsRepository.findByUserId(userId).orElse(null);
    SchoolSettings schoolSettings = requireSchool(schoolId).getSettings();
    Map<NotificationCategory, NotificationPreference> stored = storedByCategory(userId);
    return toResponse(settings, schoolSettings, stored);
  }

  @Override
  @Transactional
  public NotificationPreferencesResponse replace(
      UUID schoolId, UUID userId, NotificationPreferencesRequest request) {
    if ((request.quietHoursStart() == null) != (request.quietHoursEnd() == null)) {
      // Half a window has no meaning, and guessing the missing half would be inventing a preference
      // the user did not express.
      throw new ValidationException("error.notification.partial_quiet_hours");
    }
    for (CategoryPreference wanted : request.preferences()) {
      if (!wanted.category().isMutable() && !wanted.enabled()) {
        throw new ValidationException(
            "error.notification.category_not_mutable", wanted.category().name());
      }
    }

    NotificationSettings settings = settingsRepository.findByUserId(userId).orElse(null);
    if (settings == null) {
      settings =
          settingsRepository.save(
              new NotificationSettings(
                  schoolId,
                  userId,
                  request.respectQuietHours(),
                  request.quietHoursStart(),
                  request.quietHoursEnd()));
    } else {
      settings.update(
          request.respectQuietHours(), request.quietHoursStart(), request.quietHoursEnd());
    }

    Map<NotificationCategory, NotificationPreference> stored = storedByCategory(userId);
    for (CategoryPreference wanted : request.preferences()) {
      NotificationPreference existing = stored.get(wanted.category());
      if (existing == null) {
        stored.put(
            wanted.category(),
            preferenceRepository.save(
                new NotificationPreference(
                    schoolId, userId, wanted.category(), wanted.enabled(), wanted.channels())));
      } else {
        existing.update(wanted.enabled(), wanted.channels());
      }
    }

    log.info(
        "notification_preferences_updated userId={} school={} categories={}",
        userId,
        schoolId,
        request.preferences().size());
    return toResponse(settings, requireSchool(schoolId).getSettings(), stored);
  }

  private NotificationPreferencesResponse toResponse(
      NotificationSettings settings,
      SchoolSettings schoolSettings,
      Map<NotificationCategory, NotificationPreference> stored) {
    List<CategoryPreference> categories = new ArrayList<>();
    for (NotificationCategory category : NotificationCategory.values()) {
      NotificationPreference row = stored.get(category);
      categories.add(
          row == null
              ? new CategoryPreference(category, true, defaultOrder())
              : new CategoryPreference(category, row.isEnabled(), row.getChannels()));
    }
    boolean respect =
        settings == null
            ? schoolSettings.isAlertsRespectQuietHours()
            : settings.isRespectQuietHours();
    return new NotificationPreferencesResponse(
        respect,
        settings == null ? null : settings.getQuietHoursStart(),
        settings == null ? null : settings.getQuietHoursEnd(),
        effectiveStart(settings, schoolSettings),
        effectiveEnd(settings, schoolSettings),
        List.copyOf(categories));
  }

  private Map<NotificationCategory, NotificationPreference> storedByCategory(UUID userId) {
    Map<NotificationCategory, NotificationPreference> byCategory =
        new EnumMap<>(NotificationCategory.class);
    for (NotificationPreference row : preferenceRepository.findAllByUserId(userId)) {
      byCategory.put(row.getCategory(), row);
    }
    return byCategory;
  }

  private School requireSchool(UUID schoolId) {
    return schools
        .findById(schoolId)
        .orElseThrow(() -> new ValidationException("error.notification.school_missing"));
  }

  /**
   * The user's chosen channels first, then every channel they left out, appended in the default
   * order. Used only for non-mutable categories, where a preference may reorder but must not
   * remove.
   */
  private static List<NotificationChannel> reorderWithoutRemoving(
      List<NotificationChannel> preferred) {
    List<NotificationChannel> ordered = new ArrayList<>(preferred);
    for (NotificationChannel channel : NotificationChannel.DEFAULT_ORDER) {
      if (!ordered.contains(channel)) {
        ordered.add(channel);
      }
    }
    return List.copyOf(ordered);
  }

  private static List<NotificationChannel> defaultOrder() {
    return NotificationChannel.DEFAULT_ORDER;
  }

  private static LocalTime effectiveStart(
      NotificationSettings settings, SchoolSettings schoolSettings) {
    return settings == null || settings.getQuietHoursStart() == null
        ? schoolSettings.getQuietHoursStart()
        : settings.getQuietHoursStart();
  }

  private static LocalTime effectiveEnd(
      NotificationSettings settings, SchoolSettings schoolSettings) {
    return settings == null || settings.getQuietHoursEnd() == null
        ? schoolSettings.getQuietHoursEnd()
        : settings.getQuietHoursEnd();
  }

  private static ZoneId zoneOf(School school) {
    try {
      return ZoneId.of(school.getTimezone());
    } catch (RuntimeException ex) {
      // A malformed school timezone must not decide whether a parent hears about their child.
      log.warn(
          "notification_preference_invalid_timezone schoolId={} tz={}",
          school.getId(),
          school.getTimezone());
      return ZoneId.of("UTC");
    }
  }
}

