package com.schoolbridge.api.common.outbox;

/** Lifecycle of a transactional-outbox event. */
public enum OutboxStatus {
  PENDING,
  PUBLISHED,
  FAILED
}
