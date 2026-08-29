package com.schoolbridge.api.announcements.enums;

/** Lifecycle status of an announcement. M6 only emits SENT or SCHEDULED on create. */
public enum AnnouncementStatus {
  DRAFT,
  SCHEDULED,
  SENDING,
  SENT,
  RECALLED
}

