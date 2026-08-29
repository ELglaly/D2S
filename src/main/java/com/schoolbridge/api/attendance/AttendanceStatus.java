package com.schoolbridge.api.attendance;

/**
 * Daily attendance state recorded by a teacher or admin. {@code PRESENT} is the no-alert default;
 * the three non-PRESENT statuses each fan out a parent alert through M7's dispatcher.
 */
public enum AttendanceStatus {
  PRESENT,
  ABSENT,
  LATE,
  EXCUSED
}

