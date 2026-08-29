package com.schoolbridge.api.attendance;

import com.schoolbridge.api.attendance.dto.AttendanceHistoryEntry;
import com.schoolbridge.api.attendance.dto.AttendanceMapper;
import com.schoolbridge.api.attendance.dto.AttendanceRecordResponse;
import com.schoolbridge.api.attendance.dto.AttendanceRosterEntry;
import com.schoolbridge.api.attendance.dto.MarkAllPresentRequest;
import com.schoolbridge.api.attendance.dto.MarkAllPresentResponse;
import com.schoolbridge.api.attendance.dto.MarkAttendanceRequest;
import com.schoolbridge.api.attendance.dto.ParentResponseRequest;
import com.schoolbridge.api.classes.entity.Enrollment;
import com.schoolbridge.api.classes.repository.EnrollmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.common.audit.AuditService;
import com.schoolbridge.api.common.error.NotFoundException;
import com.schoolbridge.api.common.error.ValidationException;
import com.schoolbridge.api.common.outbox.OutboxEventRecorder;
import com.schoolbridge.api.common.tenancy.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core attendance write/read path. Three responsibilities, in order of NFR-P2 importance:
 *
 * <ol>
 *   <li>Persist the attendance row inside a single short transaction so the HTTP response returns
 *       fast and the SLA clock starts.
 *   <li>Write a status-specific outbox event ({@code attendance.absent_alert} / {@code
 *       attendance.late_alert} / {@code attendance.excused_alert}) in the same transaction so the
 *       relay can pick it up and the M8 consumer can fan out to parents. Transitions back to
 *       PRESENT write {@code attendance.marked} only (no alert).
 *   <li>Write an audit log entry (unconditional â€” audit â‰  outbox).
 * </ol>
 *
 * <p>Same-status re-marks are no-ops: the original row is returned and no new outbox event is
 * written, so an accidental teacher re-submit cannot re-fire alerts. Bulk mark-all-present writes a
 * single aggregated {@code attendance.bulk_marked} event with the list of transitions per OQ4.
 *
 * <p>All outbox payload maps use {@link HashMap} per {@code feedback-outbox-audit-mapof-npe} â€”
 * {@code traceId} from MDC is nullable and {@code Map.of} would NPE.
 */
@Service
public class AttendanceServiceImpl implements AttendanceService {

  static final String AGGREGATE_TYPE = "AttendanceRecord";
  static final String EVENT_MARKED = "attendance.marked";
  static final String EVENT_BULK_MARKED = "attendance.bulk_marked";
  static final String EVENT_ABSENT_ALERT = "attendance.absent_alert";
  static final String EVENT_LATE_ALERT = "attendance.late_alert";
  static final String EVENT_EXCUSED_ALERT = "attendance.excused_alert";

  private final AttendanceRecordRepository records;
  private final EnrollmentRepository enrollments;
  private final ParentStudentLinkRepository parentLinks;
  private final AttendanceMapper mapper;
  private final OutboxEventRecorder outbox;
  private final AuditService auditService;

  public AttendanceServiceImpl(
      AttendanceRecordRepository records,
      EnrollmentRepository enrollments,
      ParentStudentLinkRepository parentLinks,
      AttendanceMapper mapper,
      OutboxEventRecorder outbox,
      AuditService auditService) {
    this.records = records;
    this.enrollments = enrollments;
    this.parentLinks = parentLinks;
    this.mapper = mapper;
    this.outbox = outbox;
    this.auditService = auditService;
  }

  @Override
  @Transactional(readOnly = true)
  public AttendanceRecordResponse findById(UUID id) {
    return mapper.toResponse(
        records
            .findById(id)
            .orElseThrow(() -> new NotFoundException("error.attendance.record_not_found", id)));
  }

  @Override
  @Transactional
  public AttendanceRecordResponse mark(UUID markedByUserId, MarkAttendanceRequest request) {
    UUID schoolId = TenantContext.require();
    Instant now = Instant.now();
    AttendanceRecord existing =
        records
            .findByStudentIdAndClassIdAndDate(
                request.studentId(), request.classId(), request.date())
            .orElse(null);

    if (existing != null && existing.getStatus() == request.status()) {
      // Same-status replay â€” idempotent no-op. OQ5.
      return mapper.toResponse(existing);
    }

    AttendanceStatus previousStatus = existing == null ? null : existing.getStatus();
    AttendanceRecord saved;
    if (existing == null) {
      saved =
          records.save(
              new AttendanceRecord(
                  schoolId,
                  request.studentId(),
                  request.classId(),
                  request.date(),
                  request.status(),
                  markedByUserId,
                  now));
    } else {
      existing.retransition(request.status(), markedByUserId, now);
      saved = existing;
    }

    writeOutboxForTransition(schoolId, saved, previousStatus, now);
    writeAudit(schoolId, markedByUserId, saved, previousStatus);
    return mapper.toResponse(saved);
  }

  @Override
  @Transactional
  public MarkAllPresentResponse markAllPresent(UUID markedByUserId, MarkAllPresentRequest request) {
    UUID schoolId = TenantContext.require();
    Instant now = Instant.now();

    List<Enrollment> enrolled = enrollments.findAllByClassId(request.classId());
    Set<UUID> enrolledStudentIds = new HashSet<>(enrolled.size());
    for (Enrollment e : enrolled) {
      enrolledStudentIds.add(e.getStudentId());
    }

    List<AttendanceRecord> existing =
        records.findAllByClassIdAndDate(request.classId(), request.date());
    Map<UUID, AttendanceRecord> existingByStudentId = new HashMap<>(existing.size());
    for (AttendanceRecord r : existing) {
      existingByStudentId.put(r.getStudentId(), r);
    }

    int transitioned = 0;
    int skipped = 0;
    List<Map<String, Object>> transitions = new ArrayList<>();

    for (UUID studentId : enrolledStudentIds) {
      AttendanceRecord row = existingByStudentId.get(studentId);
      if (row != null && row.getStatus() == AttendanceStatus.PRESENT) {
        skipped++;
        continue;
      }
      AttendanceStatus previousStatus = row == null ? null : row.getStatus();
      if (row == null) {
        row =
            records.save(
                new AttendanceRecord(
                    schoolId,
                    studentId,
                    request.classId(),
                    request.date(),
                    AttendanceStatus.PRESENT,
                    markedByUserId,
                    now));
      } else {
        row.retransition(AttendanceStatus.PRESENT, markedByUserId, now);
      }
      transitioned++;
      Map<String, Object> entry = new HashMap<>();
      entry.put("studentId", studentId);
      entry.put("recordId", row.getId());
      entry.put("previousStatus", previousStatus == null ? null : previousStatus.name());
      entry.put("newStatus", AttendanceStatus.PRESENT.name());
      transitions.add(entry);

      auditService.record(
          schoolId,
          markedByUserId,
          "attendance.marked",
          AGGREGATE_TYPE,
          row.getId(),
          Map.of(
              "status",
              AttendanceStatus.PRESENT.name(),
              "previousStatus",
              previousStatus == null ? "null" : previousStatus.name()));
    }

    if (transitioned > 0) {
      Map<String, Object> payload = new HashMap<>();
      payload.put("schoolId", schoolId);
      payload.put("classId", request.classId());
      payload.put("date", request.date().toString());
      payload.put("markedByUserId", markedByUserId);
      payload.put("markedAt", now.toString());
      payload.put("transitionedCount", transitioned);
      payload.put("transitions", transitions);
      payload.put("traceId", MDC.get("traceId"));
      outbox.record(schoolId, AGGREGATE_TYPE, request.classId(), EVENT_BULK_MARKED, payload);
    }

    return new MarkAllPresentResponse(request.classId(), request.date(), transitioned, skipped);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AttendanceRosterEntry> roster(UUID classId, LocalDate date) {
    return records.findRosterForClassAndDate(classId, date);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AttendanceHistoryEntry> history(
      UUID studentId, LocalDate fromDate, LocalDate toDate) {
    if (fromDate.isAfter(toDate)) {
      throw new ValidationException("error.attendance.invalid_date_range");
    }
    return records.findStudentHistory(studentId, fromDate, toDate).stream()
        .map(mapper::toHistoryEntry)
        .toList();
  }

  @Override
  @Transactional
  public AttendanceRecordResponse recordParentResponse(
      UUID recordId, UUID parentUserId, ParentResponseRequest request) {
    AttendanceRecord record =
        records
            .findById(recordId)
            .orElseThrow(
                () -> new NotFoundException("error.attendance.record_not_found", recordId));

    // Anti-enumeration: a parent who is not linked to this record's student must see 404,
    // not 403. The @PreAuthorize check on the controller is belt-and-suspenders.
    if (!parentLinks.existsByParentUserIdAndStudentId(parentUserId, record.getStudentId())) {
      throw new NotFoundException("error.attendance.record_not_found", recordId);
    }

    if (record.getStatus() == AttendanceStatus.PRESENT) {
      throw new ValidationException("error.attendance.parent_response_not_allowed");
    }

    Instant now = Instant.now();
    record.recordParentResponse(request.response(), now);

    auditService.record(
        record.getSchoolId(),
        parentUserId,
        "attendance.parent_responded",
        AGGREGATE_TYPE,
        record.getId(),
        Map.of("status", record.getStatus().name()));

    return mapper.toResponse(record);
  }

  /**
   * Writes the right outbox event for the transition. Non-PRESENT statuses each fire a
   * status-specific alert event (OQ2). Transitions to PRESENT (or non-PRESENT-but-no-alert future
   * statuses) fall through to {@link #EVENT_MARKED}.
   */
  private void writeOutboxForTransition(
      UUID schoolId, AttendanceRecord saved, AttendanceStatus previousStatus, Instant markedAt) {
    String eventType = eventTypeFor(saved.getStatus());
    Map<String, Object> payload = new HashMap<>();
    payload.put("schoolId", schoolId);
    payload.put("recordId", saved.getId());
    payload.put("studentId", saved.getStudentId());
    payload.put("classId", saved.getClassId());
    payload.put("date", saved.getDate().toString());
    payload.put("status", saved.getStatus().name());
    payload.put("previousStatus", previousStatus == null ? null : previousStatus.name());
    payload.put("markedByUserId", saved.getMarkedByUserId());
    payload.put("markedAt", markedAt.toString());
    payload.put("traceId", MDC.get("traceId"));
    outbox.record(schoolId, AGGREGATE_TYPE, saved.getId(), eventType, payload);
  }

  private void writeAudit(
      UUID schoolId, UUID markedByUserId, AttendanceRecord saved, AttendanceStatus previousStatus) {
    auditService.record(
        schoolId,
        markedByUserId,
        "attendance.marked",
        AGGREGATE_TYPE,
        saved.getId(),
        Map.of(
            "status",
            saved.getStatus().name(),
            "previousStatus",
            previousStatus == null ? "null" : previousStatus.name()));
  }

  private static String eventTypeFor(AttendanceStatus status) {
    return switch (status) {
      case ABSENT -> EVENT_ABSENT_ALERT;
      case LATE -> EVENT_LATE_ALERT;
      case EXCUSED -> EVENT_EXCUSED_ALERT;
      case PRESENT -> EVENT_MARKED;
    };
  }
}

