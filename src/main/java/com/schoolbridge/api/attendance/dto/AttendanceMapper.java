package com.schoolbridge.api.attendance.dto;

import com.schoolbridge.api.attendance.AttendanceRecord;
import org.springframework.stereotype.Component;

/** Explicit hand-written mapper for the M8 aggregates. */
@Component
public class AttendanceMapper {

  public AttendanceRecordResponse toResponse(AttendanceRecord r) {
    return new AttendanceRecordResponse(
        r.getId(),
        r.getSchoolId(),
        r.getStudentId(),
        r.getClassId(),
        r.getDate(),
        r.getStatus(),
        r.getMarkedByUserId(),
        r.getMarkedAt(),
        r.getParentRespondedAt() != null,
        r.getParentRespondedAt(),
        r.getAlertSentAt());
  }

  public AttendanceHistoryEntry toHistoryEntry(AttendanceRecord r) {
    return new AttendanceHistoryEntry(
        r.getId(),
        r.getStudentId(),
        r.getClassId(),
        r.getDate(),
        r.getStatus(),
        r.getMarkedAt(),
        r.getParentRespondedAt() != null,
        r.getParentRespondedAt());
  }
}

