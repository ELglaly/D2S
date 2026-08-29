package com.schoolbridge.api.announcements.enums;

/**
 * Targeting scope of an announcement.
 *
 * <ul>
 *   <li>{@link #SCHOOL}: every parent in the school (scopeValue is null).
 *   <li>{@link #GRADE}: every parent whose linked students are enrolled in a class with this grade
 *       (scopeValue is the grade-level string, e.g. "Grade 3").
 *   <li>{@link #CLASS}: every parent whose linked students are enrolled in this class (scopeValue
 *       is the classId as a string).
 *   <li>{@link #CUSTOM}: an explicit list of studentIds in the request (scopeValue is null;
 *       materialized recipients are the source of truth).
 * </ul>
 */
public enum AnnouncementScope {
  SCHOOL,
  GRADE,
  CLASS,
  CUSTOM
}

