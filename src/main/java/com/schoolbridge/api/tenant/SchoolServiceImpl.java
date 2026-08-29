package com.schoolbridge.api.tenant;

import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.error.ValidationException;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import com.schoolbridge.api.tenant.dto.CreateSchoolRequest;
import com.schoolbridge.api.tenant.dto.SchoolMapper;
import com.schoolbridge.api.tenant.dto.SchoolResponse;
import com.schoolbridge.api.tenant.dto.SchoolSettingsRequest;
import com.schoolbridge.api.tenant.dto.SchoolSettingsResponse;
import com.schoolbridge.api.tenant.events.SchoolCreatedEvent;
import com.schoolbridge.api.tenant.events.SchoolReactivatedEvent;
import com.schoolbridge.api.tenant.events.SchoolSettingsUpdatedEvent;
import com.schoolbridge.api.tenant.events.SchoolSuspendedEvent;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchoolServiceImpl implements SchoolService {

  private static final String AGGREGATE_TYPE = "School";

  private final SchoolRepository repository;
  private final SchoolMapper mapper;
  private final AuditService auditService;
  private final OutboxEventRecorder outbox;
  private final ApplicationEventPublisher events;

  public SchoolServiceImpl(
      SchoolRepository repository,
      SchoolMapper mapper,
      AuditService auditService,
      OutboxEventRecorder outbox,
      ApplicationEventPublisher events) {
    this.repository = repository;
    this.mapper = mapper;
    this.auditService = auditService;
    this.outbox = outbox;
    this.events = events;
  }

  @Override
  @Transactional
  public SchoolResponse create(CreateSchoolRequest request) {
    validateTimezone(request.timezone());
    validateLocale(request.locale());

    SchoolSettings settings =
        request.settings() == null
            ? SchoolSettings.defaults()
            : mapper.toSettings(request.settings());
    School school =
        new School(
            request.name().trim(),
            request.country(),
            request.timezone(),
            request.locale(),
            request.subscriptionTier(),
            settings);
    School saved = repository.save(school);

    auditService.record(
        saved.getId(),
        null, // actor: platform admin / super-admin; populated once principal carries userId
        "school.created",
        AGGREGATE_TYPE,
        saved.getId(),
        Map.of("name", saved.getName(), "tier", saved.getSubscriptionTier().name()));
    outbox.record(
        saved.getId(),
        AGGREGATE_TYPE,
        saved.getId(),
        "school.created",
        Map.of("schoolId", saved.getId(), "name", saved.getName()));
    events.publishEvent(new SchoolCreatedEvent(saved.getId()));
    return mapper.toResponse(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public SchoolResponse findById(UUID id) {
    return mapper.toResponse(requireSchool(id));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<SchoolResponse> list(SchoolStatus statusFilter, Pageable pageable) {
    Page<School> page =
        statusFilter == null
            ? repository.findAll(pageable)
            : repository.findByStatus(statusFilter, pageable);
    return page.map(mapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public SchoolSettingsResponse getSettings(UUID id) {
    return mapper.toSettingsResponse(requireSchool(id).getSettings());
  }

  @Override
  @Transactional
  public SchoolSettingsResponse updateSettings(UUID id, SchoolSettingsRequest request) {
    School school = requireSchool(id);
    school.replaceSettings(mapper.toSettings(request));
    auditService.record(id, null, "school.settings.updated", AGGREGATE_TYPE, id, Map.of());
    outbox.record(id, AGGREGATE_TYPE, id, "school.settings.updated", Map.of("schoolId", id));
    events.publishEvent(new SchoolSettingsUpdatedEvent(id));
    return mapper.toSettingsResponse(school.getSettings());
  }

  @Override
  @Transactional
  public void suspend(UUID id) {
    School school = requireSchool(id);
    if (school.getStatus() == SchoolStatus.SUSPENDED) {
      throw new ConflictException("error.school.already_suspended");
    }
    school.suspend();
    auditService.record(id, null, "school.suspended", AGGREGATE_TYPE, id, Map.of());
    outbox.record(id, AGGREGATE_TYPE, id, "school.suspended", Map.of("schoolId", id));
    events.publishEvent(new SchoolSuspendedEvent(id));
  }

  @Override
  @Transactional
  public void reactivate(UUID id) {
    School school = requireSchool(id);
    if (school.getStatus() == SchoolStatus.ACTIVE) {
      throw new ConflictException("error.school.already_active");
    }
    school.reactivate();
    auditService.record(id, null, "school.reactivated", AGGREGATE_TYPE, id, Map.of());
    outbox.record(id, AGGREGATE_TYPE, id, "school.reactivated", Map.of("schoolId", id));
    events.publishEvent(new SchoolReactivatedEvent(id));
  }

  private School requireSchool(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("error.school.not_found", id));
  }

  private static void validateTimezone(String timezone) {
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException ex) {
      throw new ValidationException("error.school.invalid_timezone", timezone);
    }
  }

  private static void validateLocale(String localeTag) {
    Locale parsed = Locale.forLanguageTag(localeTag);
    if (parsed.toLanguageTag().isEmpty() || !parsed.toLanguageTag().equalsIgnoreCase(localeTag)) {
      throw new ValidationException("error.school.invalid_locale", localeTag);
    }
  }
}

