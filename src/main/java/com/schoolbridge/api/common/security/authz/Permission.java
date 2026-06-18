package com.schoolbridge.api.common.security.authz;

/**
 * Compile-time catalog of fine-grained permissions. Each value mirrors a row in the {@code
 * permissions} table (by {@link #name()}); the {@code role_permissions} table maps these to the
 * fixed {@link com.schoolbridge.api.identity.UserRole} values at runtime.
 *
 * <p>Use in {@link RequirePermission} on controller/service methods. {@code MANAGE_ROLES} and
 * {@code MANAGE_PERMISSIONS} gate the authz-management endpoints themselves and are seeded to
 * SUPER_ADMIN only — no other role may alter role→permission mappings.
 */
public enum Permission {
  // grades
  GRADE_CREATE,
  GRADE_READ,
  GRADE_UPDATE,
  GRADE_DELETE,

  // homework
  HOMEWORK_CREATE,
  HOMEWORK_PUBLISH,
  HOMEWORK_READ,
  HOMEWORK_UPDATE,
  HOMEWORK_DELETE,
  HOMEWORK_ACK,

  // attendance
  ATTENDANCE_RECORD,
  ATTENDANCE_READ,

  // classes / subjects / enrollment / students
  CLASS_MANAGE,
  CLASS_READ,
  SUBJECT_MANAGE,
  SUBJECT_READ,
  ENROLLMENT_MANAGE,
  STUDENT_MANAGE,
  STUDENT_READ,
  PARENT_LINK_MANAGE,

  // announcements
  ANNOUNCEMENT_SEND,
  ANNOUNCEMENT_READ,
  ANNOUNCEMENT_MANAGE,

  // assistant
  ASSISTANT_SETTINGS_MANAGE,
  DOCUMENT_MANAGE,

  // platform administration
  USER_READ,
  USER_MANAGE,
  SCHOOL_MANAGE,
  WHATSAPP_DIAGNOSTICS,
  MANAGE_ROLES,
  MANAGE_PERMISSIONS
}
