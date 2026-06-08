package com.schoolbridge.api.common.security;

import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.homework.HomeworkItemRepository;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import com.schoolbridge.api.subjects.TeacherSubjectAssignmentRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Instance-level permission helpers exposed as a Spring bean named {@code perms} so controllers can
 * write concise SpEL expressions such as:
 *
 * <pre>{@code @PreAuthorize("hasRole('SCHOOL_ADMIN') or @perms.teacherTeaches(#classId)")}</pre>
 *
 * <p>All methods return {@code false} (deny) when the security context does not contain the
 * expected principal type — this is the safe default.
 */
@Component("perms")
public class PermissionsHelper {

  private final SchoolClassRepository schoolClasses;
  private final TeacherSubjectAssignmentRepository teacherSubjectAssignments;
  private final ParentStudentLinkRepository parentLinks;
  private final AnnouncementRepository announcements;
  private final AnnouncementRecipientRepository announcementRecipients;
  private final HomeworkItemRepository homeworkItems;

  public PermissionsHelper(
      SchoolClassRepository schoolClasses,
      TeacherSubjectAssignmentRepository teacherSubjectAssignments,
      ParentStudentLinkRepository parentLinks,
      AnnouncementRepository announcements,
      AnnouncementRecipientRepository announcementRecipients,
      HomeworkItemRepository homeworkItems) {
    this.schoolClasses = schoolClasses;
    this.teacherSubjectAssignments = teacherSubjectAssignments;
    this.parentLinks = parentLinks;
    this.announcements = announcements;
    this.announcementRecipients = announcementRecipients;
    this.homeworkItems = homeworkItems;
  }

  /**
   * Returns {@code true} if the current principal is the homeroom teacher of the given class, or
   * teaches at least one subject in it. Used in SpEL: {@code @perms.teacherTeaches(#classId)}.
   */
  public boolean teacherTeaches(UUID classId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof StaffPrincipal staff)) {
      return false;
    }
    return schoolClasses.existsByIdAndTeacher(classId, staff.userId());
  }

  /**
   * Returns {@code true} if the current principal teaches the specific subject in the given class.
   * Used in SpEL: {@code @perms.teacherTeachesSubject(#classId, #subjectId)}.
   */
  public boolean teacherTeachesSubject(UUID classId, UUID subjectId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof StaffPrincipal staff)) {
      return false;
    }
    return teacherSubjectAssignments.existsByTeacherUserIdAndClassIdAndSubjectId(
        staff.userId(), classId, subjectId);
  }

  /**
   * Returns {@code true} if the current principal is a parent linked to the given student. Used in
   * SpEL: {@code @perms.parentLinkedTo(#studentId)}.
   */
  public boolean parentLinkedTo(UUID studentId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof ParentPrincipal parent)) {
      return false;
    }
    return parentLinks.existsByParentUserIdAndStudentId(parent.userId(), studentId);
  }

  /**
   * Returns {@code true} if the current principal is allowed to send an announcement with the
   * supplied scope:
   *
   * <ul>
   *   <li>SCHOOL_ADMIN: any scope.
   *   <li>TEACHER: only CLASS scope, only for a class they teach a subject in.
   * </ul>
   *
   * Used in SpEL by {@code AnnouncementController#create}. Returns {@code false} for
   * unauthenticated requests, parent principals, or null inputs.
   */
  public boolean canSendAnnouncementToScope(AnnouncementScope scopeType, String scopeValue) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || scopeType == null) {
      return false;
    }
    if (!(auth.getPrincipal() instanceof StaffPrincipal staff)) {
      return false;
    }
    if (staff.role() == UserRole.SCHOOL_ADMIN) {
      return true;
    }
    if (staff.role() == UserRole.TEACHER) {
      if (scopeType != AnnouncementScope.CLASS || scopeValue == null) {
        return false;
      }
      UUID classId;
      try {
        classId = UUID.fromString(scopeValue);
      } catch (IllegalArgumentException ex) {
        return false;
      }
      return teacherTeaches(classId);
    }
    return false;
  }

  /**
   * Returns {@code true} if the current parent principal has a recipient row for the given
   * announcement. Used in SpEL: {@code @perms.parentReceivedAnnouncement(#id)}.
   */
  public boolean parentReceivedAnnouncement(UUID announcementId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof ParentPrincipal parent)) {
      return false;
    }
    return announcementRecipients.existsByAnnouncementIdAndParentUserId(
        announcementId, parent.userId());
  }

  /**
   * Returns {@code true} when the current staff principal authored the given announcement. Used by
   * the "SCHOOL_ADMIN or original sender" rule on {@code GET /announcements/{id}}.
   */
  public boolean isAnnouncementSender(UUID announcementId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof StaffPrincipal staff)) {
      return false;
    }
    return announcements
        .findById(announcementId)
        .map(a -> a.getSenderId().equals(staff.userId()))
        .orElse(false);
  }

  /**
   * Returns {@code true} when the current teacher authored the given homework item. Used in SpEL by
   * publish/update/delete endpoints where the classId is not available at SpEL-evaluation time.
   * Used in SpEL: {@code @perms.isHomeworkAuthor(#id)}.
   */
  public boolean isHomeworkAuthor(UUID homeworkId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof StaffPrincipal staff)) {
      return false;
    }
    return homeworkItems
        .findById(homeworkId)
        .map(h -> h.getTeacherId().equals(staff.userId()))
        .orElse(false);
  }
}
