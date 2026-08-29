package com.schoolbridge.api.common.security;

import com.schoolbridge.api.common.security.authz.EffectivePermissionService;
import com.schoolbridge.api.identity.auth.principal.ParentPrincipal;
import com.schoolbridge.api.identity.auth.principal.StaffPrincipal;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Server-side row-level authorization policies.
 *
 * <p>These policies deliberately use the authenticated principal and database relationships. Role
 * names are never supplied by callers or used as business predicates; administrative capability is
 * determined from the same server-side permission catalog as {@link
 * com.schoolbridge.api.common.security.authz.RequirePermission}.
 */
@Component
public class AuthorizationPolicy {

  private final PermissionsHelper relationships;
  private final EffectivePermissionService permissions;

  public AuthorizationPolicy(
      PermissionsHelper relationships, EffectivePermissionService permissions) {
    this.relationships = relationships;
    this.permissions = permissions;
  }

  public void requireClassAccess(UUID classId) {
    if (has("CLASS_MANAGE") || relationships.teacherTeaches(classId)) return;
    deny();
  }

  public void requireClassRead(UUID classId) {
    if (has("CLASS_READ") || relationships.teacherTeaches(classId)) return;
    deny();
  }

  public void requireStudentAccess(UUID studentId) {
    if (has("STUDENT_MANAGE") || relationships.parentLinkedTo(studentId)) return;
    deny();
  }

  public void requireStudentRead(UUID studentId) {
    if (has("STUDENT_READ") || has("GRADE_READ") || relationships.parentLinkedTo(studentId)) return;
    deny();
  }

  public void requireHomeworkAccess(UUID homeworkId) {
    if (has("HOMEWORK_UPDATE") || relationships.isHomeworkAuthor(homeworkId)) return;
    deny();
  }

  public void requireHomeworkRead(UUID homeworkId) {
    if (has("HOMEWORK_READ") || relationships.isHomeworkAuthor(homeworkId)
        || relationships.parentCanReadAttachment(homeworkId)) return;
    deny();
  }

  public void requireAnnouncementAccess(UUID announcementId) {
    if (has("ANNOUNCEMENT_MANAGE") || relationships.isAnnouncementSender(announcementId)
        || relationships.parentReceivedAnnouncement(announcementId)) return;
    deny();
  }

  public void requireAnnouncementRecipient(UUID announcementId) {
    if (relationships.parentReceivedAnnouncement(announcementId)) return;
    deny();
  }

  public void requireAnnouncementScope(
      com.schoolbridge.api.announcements.enums.AnnouncementScope scope, UUID classId) {
    if (has("ANNOUNCEMENT_MANAGE")) return;
    if (has("ANNOUNCEMENT_SEND")
        && scope == com.schoolbridge.api.announcements.enums.AnnouncementScope.CLASS
        && classId != null
        && relationships.teacherTeaches(classId)) return;
    deny();
  }

  public void requireAttachmentAccess(UUID attachmentId) {
    if (has("ATTACHMENT_READ") || has("ATTACHMENT_DELETE")
        || relationships.isAttachmentUploader(attachmentId)
        || relationships.parentCanReadAttachment(attachmentId)) return;
    deny();
  }

  public boolean isTrustedStaff() {
    Authentication auth = current();
    return auth != null && auth.getPrincipal() instanceof StaffPrincipal;
  }

  public boolean isTrustedParent() {
    Authentication auth = current();
    return auth != null && auth.getPrincipal() instanceof ParentPrincipal;
  }

  private boolean has(String permission) {
    Authentication auth = current();
    if (auth == null) return false;
    if (auth.getPrincipal() instanceof StaffPrincipal staff) {
      Set<String> granted = permissions.permissionsForRole(staff.role());
      return granted.contains(permission);
    }
    return false;
  }

  private Authentication current() {
    return SecurityContextHolder.getContext().getAuthentication();
  }

  private void deny() {
    throw new AccessDeniedException("error.forbidden");
  }
}





