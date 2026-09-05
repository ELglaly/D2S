package com.schoolbridge.api.assistant.tools;

/**
 * Coarse intent bucket used to gate the tool catalog by the user's query, so a request advertises
 * only the tools likely relevant instead of the whole role catalog (the dominant input-token cost).
 * Derived from a tool's package so no per-tool wiring is needed; {@link #GENERAL} is the catch-all
 * and is always offered.
 */
public enum ToolDomain {
  ATTENDANCE,
  GRADES,
  HOMEWORK,
  CLASSES,
  SUBJECTS,
  ANNOUNCEMENTS,
  PARENTS,
  STUDENTS,
  STAFF,
  GENERAL;

  /**
   * Maps a tool's leaf package (â€¦{@code .tools.attendance}) to its domain; unknown â†’ GENERAL.
   */
  public static ToolDomain fromPackage(Class<?> type) {
    String pkg = type.getPackageName();
    int dot = pkg.lastIndexOf('.');
    String leaf = dot < 0 ? pkg : pkg.substring(dot + 1);
    return switch (leaf) {
      case "attendance" -> ATTENDANCE;
      case "grades" -> GRADES;
      case "homework" -> HOMEWORK;
      case "classes" -> CLASSES;
      case "subjects" -> SUBJECTS;
      case "announcements" -> ANNOUNCEMENTS;
      case "parents" -> PARENTS;
      case "student" -> STUDENTS;
      case "staff" -> STAFF;
      default -> GENERAL;
    };
  }
}
