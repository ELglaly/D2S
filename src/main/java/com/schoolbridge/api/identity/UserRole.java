package com.schoolbridge.api.identity;

/** Tenant-scoped roles. {@code SUPER_ADMIN} lives in {@link PlatformAdmin} instead. */
public enum UserRole {
  SUPER_ADMIN,
  SCHOOL_ADMIN,
  TEACHER,
  PARENT
}

