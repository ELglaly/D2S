package com.schoolbridge.api.attendance;

/**
 * Per-recipient lifecycle for an attendance alert fan-out. {@code DEFERRED} carries quiet-hours
 * holds released by {@code AttendanceSweeper}; on first dispatch attempt the row moves to {@code
 * SENT} or {@code FAILED}.
 */
public enum AttendanceAlertStatus {
  PENDING,
  DEFERRED,
  SENT,
  FAILED
}
