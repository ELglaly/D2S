package com.schoolbridge.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.ConflictException;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.error.ValidationException;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import com.schoolbridge.api.tenant.dto.CreateSchoolRequest;
import com.schoolbridge.api.tenant.dto.SchoolMapper;
import com.schoolbridge.api.tenant.events.SchoolCreatedEvent;
import com.schoolbridge.api.tenant.events.SchoolReactivatedEvent;
import com.schoolbridge.api.tenant.events.SchoolSuspendedEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class SchoolServiceTest {

  @Mock SchoolRepository repository;
  @Mock AuditService auditService;
  @Mock OutboxEventRecorder outbox;
  @Mock ApplicationEventPublisher events;

  SchoolMapper mapper = new SchoolMapper();

  SchoolServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new SchoolServiceImpl(repository, mapper, auditService, outbox, events);
  }

  @Test
  void create_validRequest_persistsAndEmitsEvents() {
    CreateSchoolRequest request =
        new CreateSchoolRequest(
            "Cairo Demo School", "EG", "Africa/Cairo", "ar-EG", SubscriptionTier.STANDARD, null);
    when(repository.save(any(School.class)))
        .thenAnswer(
            inv -> {
              School s = inv.getArgument(0);
              assignId(s, UUID.randomUUID());
              return s;
            });

    var response = service.create(request);

    assertThat(response.name()).isEqualTo("Cairo Demo School");
    assertThat(response.status()).isEqualTo(SchoolStatus.ACTIVE);
    assertThat(response.settings().defaultLanguage()).isEqualTo(Language.EN);
    verify(auditService).record(any(), eq(null), eq("school.created"), eq("School"), any(), any());
    verify(outbox).record(any(), eq("School"), any(), eq("school.created"), any());
    verify(events).publishEvent(any(SchoolCreatedEvent.class));
  }

  @Test
  void create_invalidTimezone_throwsValidation() {
    CreateSchoolRequest request =
        new CreateSchoolRequest("X", "EG", "Not/A/Zone", "ar-EG", SubscriptionTier.BASIC, null);

    assertThatThrownBy(() -> service.create(request))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("error.school.invalid_timezone");
    verify(repository, never()).save(any());
  }

  @Test
  void create_invalidLocale_throwsValidation() {
    CreateSchoolRequest request =
        new CreateSchoolRequest(
            "X", "EG", "Africa/Cairo", "definitely-not-a-locale!", SubscriptionTier.BASIC, null);

    assertThatThrownBy(() -> service.create(request))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("error.school.invalid_locale");
  }

  @Test
  void findById_missing_throwsNotFound() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.findById(id))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("error.school.not_found");
  }

  @Test
  void suspend_activeSchool_transitionsAndEmits() {
    School school = activeSchool();
    when(repository.findById(school.getId())).thenReturn(Optional.of(school));

    service.suspend(school.getId());

    assertThat(school.getStatus()).isEqualTo(SchoolStatus.SUSPENDED);
    verify(events, times(1)).publishEvent(any(SchoolSuspendedEvent.class));
  }

  @Test
  void suspend_alreadySuspended_throwsConflict() {
    School school = activeSchool();
    school.suspend();
    when(repository.findById(school.getId())).thenReturn(Optional.of(school));

    assertThatThrownBy(() -> service.suspend(school.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("already_suspended");
    verify(events, never()).publishEvent(any(SchoolSuspendedEvent.class));
  }

  @Test
  void reactivate_suspendedSchool_transitionsAndEmits() {
    School school = activeSchool();
    school.suspend();
    when(repository.findById(school.getId())).thenReturn(Optional.of(school));

    service.reactivate(school.getId());

    assertThat(school.getStatus()).isEqualTo(SchoolStatus.ACTIVE);
    verify(events).publishEvent(any(SchoolReactivatedEvent.class));
  }

  @Test
  void reactivate_alreadyActive_throwsConflict() {
    School school = activeSchool();
    when(repository.findById(school.getId())).thenReturn(Optional.of(school));

    assertThatThrownBy(() -> service.reactivate(school.getId()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("already_active");
  }

  private static School activeSchool() {
    School school =
        new School(
            "Test",
            "EG",
            "Africa/Cairo",
            "en-US",
            SubscriptionTier.BASIC,
            SchoolSettings.defaults());
    assignId(school, UUID.randomUUID());
    return school;
  }

  /** Sets {@code BaseEntity.id} via reflection — JPA would do this on persist. */
  private static void assignId(School school, UUID id) {
    try {
      var field = school.getClass().getSuperclass().getDeclaredField("id");
      field.setAccessible(true);
      field.set(school, id);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
