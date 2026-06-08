package com.schoolbridge.api.announcements.enums;

/**
 * Per-recipient delivery state. M6 only writes QUEUED; later states are updated by the WhatsApp
 * webhook consumer (M7) and the parent acknowledgement flow.
 */
public enum DeliveryStatus {
  QUEUED,
  SENT,
  DELIVERED,
  READ,
  FAILED
}
