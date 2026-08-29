package com.schoolbridge.api.homework;

public enum HomeworkDeliveryStatus {
  PENDING,
  DEFERRED,
  /**
   * The parent opted out of homework reminders. Terminal, and deliberately distinct from FAILED:
   * nothing went wrong and nothing should be retried, so a delivery report must not read it as a
   * miss.
   */
  SUPPRESSED,
  SENT,
  FAILED
}

