package com.schoolbridge.api.tenant;

/**
 * Supported school-facing languages. Lives here because {@link SchoolSettings} is its first
 * consumer; promote to a shared package when announcements/notifications start using it (M6+).
 */
public enum Language {
  AR,
  EN
}
