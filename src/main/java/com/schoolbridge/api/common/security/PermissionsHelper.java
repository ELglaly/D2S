package com.schoolbridge.api.common.security;

import com.schoolbridge.api.announcements.enums.AnnouncementScope;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.attachments.AttachmentRepository;
import com.schoolbridge.api.classes.repository.ParentStudentLinkRepository;
import com.schoolbridge.api.classes.repository.SchoolClassRepository;
import com.schoolbridge.api.common.security.authz.EffectivePermissionService;
import com.schoolbridge.api.homework.HomeworkItemRepository;
import com.schoolbridge.api.homework.HomeworkRecipientRepository;
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
 * <p>Resource ownership predicates are reusable service policies and use the trusted principal plus
 * domain relationships; they never trust client-supplied roles.
 *
 * <p>All methods return {@code false} (deny) when the security context does not contain the
 * expected principal type Ã¢â‚¬â€ this is the safe default.
 */
@Component("perms")
public class PermissionsHelper {

  private final SchoolClassRepository schoolClasses;
  private final TeacherSubjectAssignmentRepository teacherSubjectAssignments;
  private final ParentStudentLinkRepository parentLinks;
  private final AnnouncementRepository announcements;
  private final AnnouncementRecipientRepository announcementRecipients;
  private final HomeworkItemRepository homeworkItems;
  private final HomeworkRecipientRepository homeworkRecipients;
  private final AttachmentRepository attachments;
  private final EffectivePermissionService effectivePermissions;

  public PermissionsHelper(
      SchoolClassRepository schoolClasses,
      TeacherSubjectAssignmentRepository teacherSubjectAssignments,
      ParentStudentLinkRepository parentLinks,
      AnnouncementRepository announcements,
      AnnouncementRecipientRepository announcementRecipients,
      HomeworkItemRepository homeworkItems,
      HomeworkRecipientRepository homeworkRecipients,
      AttachmentRepository attachments,
      EffectivePermissionService effectivePermissions) {
    this.attachments = attachments;
    this.schoolClasses = schoolClasses;
    this.teacherSubjectAssignments = teacherSubjectAssignments;
    this.parentLinks = parentLinks;
    this.announcements = announcements;
    this.announcementRecipients = announcementRecipients;
    this.homeworkItems = homeworkItems;
    this.homeworkRecipients = homeworkRecipients;
    this.effectivePermissions = effectivePermissions;
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
    if (auth == null
        || scopeType == null
        || !(auth.getPrincipal() instanceof StaffPrincipal staff)) {
      return false;
    }
    var granted = effectivePermissions.permissionsForRole(staff.role());
    if (granted.contains("ANNOUNCEMENT_MANAGE")) return true;
    if (!granted.contains("ANNOUNCEMENT_SEND")
        || scopeType != AnnouncementScope.CLASS
        || scopeValue == null) return false;
    try {
      return teacherTeaches(UUID.fromString(scopeValue));
    } catch (IllegalArgumentException ex) {
      return false;
    }
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

  /**
   * Returns {@code true} when the current parent principal is a recipient of some homework item or
   * announcement that carries this attachment. Used in SpEL: {@code
   * @perms.parentCanReadAttachment(#id)}.
   *
   * <p>The {@code ATTACHMENT_READ} grant on the PARENT role is deliberately coarse Ã¢â‚¬â€ it says a
   * parent may open attachments, not <em>which</em> ones. This is the part that answers "which",
   * and without it any parent in a school could download any other family's uploaded photo by id.
   * Note it grants nothing for an attachment that is not yet attached to anything, which is the
   * correct answer for one still being uploaded by a teacher.
   */
  /**
   * Returns {@code true} when the current staff principal uploaded the given attachment. Used in
   * SpEL: {@code @perms.isAttachmentUploader(#id)}.
   *
   * <p>Completing someone else's upload is not a read, it is the step that decides whether an
   * object becomes downloadable, so a teacher may only finish their own.
   */
  public boolean isAttachmentUploader(UUID attachmentId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof StaffPrincipal staff)) {
      return false;
    }
    return attachments
        .findById(attachmentId)
        .map(a -> a.getUploaderUserId().equals(staff.userId()))
        .orElse(false);
  }

  public boolean parentCanReadAttachment(UUID attachmentId) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || attachmentId == null) {
      return false;
    }
    if (!(auth.getPrincipal() instanceof ParentPrincipal parent)) {
      return false;
    }
    String reference = attachmentId.toString();
    return homeworkRecipients.existsForParentAndAttachment(parent.userId(), reference)
        || announcementRecipients.existsForParentAndAttachment(parent.userId(), reference);
  }
}
