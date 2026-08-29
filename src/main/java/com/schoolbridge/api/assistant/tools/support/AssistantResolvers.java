package com.schoolbridge.api.assistant.tools.support;

import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.support.NameMatching.MatchResult;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.classes.service.ParentChildrenService;
import com.schoolbridge.api.classes.service.SchoolClassService;
import com.schoolbridge.api.classes.service.StudentService;
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import com.schoolbridge.api.identity.UserRole;
import com.schoolbridge.api.identity.UserService;
import com.schoolbridge.api.identity.dto.UserResponse;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import com.schoolbridge.api.subjects.service.SubjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Builds the tenant-scoped candidate set for each kind of name reference and matches the supplied
 * name against it. Candidate sets honour the caller's visibility (a teacher resolves a class name
 * only against the classes they teach; an admin against the whole school).
 */
@Component
public class AssistantResolvers {

  /** Upper bound on candidates pulled for name matching; well above realistic per-school counts. */
  static final int MAX_CANDIDATES = 500;

  private final ParentChildrenService parentChildren;
  private final SchoolClassService classes;
  private final StudentService students;
  private final SubjectService subjects;
  private final UserService users;
  private final HomeworkService homework;
  private final AnnouncementService announcements;

  public AssistantResolvers(
      ParentChildrenService parentChildren,
      SchoolClassService classes,
      StudentService students,
      SubjectService subjects,
      UserService users,
      HomeworkService homework,
      AnnouncementService announcements) {
    this.parentChildren = parentChildren;
    this.classes = classes;
    this.students = students;
    this.subjects = subjects;
    this.users = users;
    this.homework = homework;
    this.announcements = announcements;
  }

  public MatchResult<ParentChildResponse> children(ToolContext ctx, String name) {
    return NameMatching.match(
        name, parentChildren.listChildren(ctx.userId()), ParentChildResponse::fullName);
  }

  /** All of the caller's linked children (no name filter). */
  public List<ParentChildResponse> childrenList(ToolContext ctx) {
    return parentChildren.listChildren(ctx.userId());
  }

  public MatchResult<SchoolClassResponse> classes(ToolContext ctx, String name) {
    List<SchoolClassResponse> candidates =
        ctx.isAdmin()
            ? classes.list(ctx.schoolId(), page()).getContent()
            : classes.listMyClasses(ctx.userId(), page()).getContent();
    return NameMatching.match(name, candidates, SchoolClassResponse::name);
  }

  public MatchResult<StudentResponse> students(ToolContext ctx, String name) {
    return NameMatching.match(
        name, students.list(ctx.schoolId(), page()).getContent(), StudentResponse::fullName);
  }

  public MatchResult<SubjectResponse> subjects(ToolContext ctx, String name) {
    return NameMatching.match(
        name, subjects.list(ctx.schoolId(), page()).getContent(), SubjectResponse::name);
  }

  public MatchResult<UserResponse> teachers(ToolContext ctx, String name) {
    return usersByRole(ctx, name, UserRole.TEACHER);
  }

  public MatchResult<UserResponse> parents(ToolContext ctx, String name) {
    return usersByRole(ctx, name, UserRole.PARENT);
  }

  /** Homework within a class, matched by subject. */
  public MatchResult<HomeworkResponse> homework(UUID classId, String ref) {
    List<HomeworkResponse> candidates =
        homework.list(classId, null, null, null, page()).getContent();
    return NameMatching.match(ref, candidates, HomeworkResponse::subject);
  }

  /** School announcements, matched by body text (admin scope). */
  public MatchResult<AnnouncementResponse> announcements(String ref) {
    List<AnnouncementResponse> candidates = announcements.list(null, page()).getContent();
    return NameMatching.match(ref, candidates, AnnouncementResponse::body);
  }

  private MatchResult<UserResponse> usersByRole(ToolContext ctx, String name, UserRole role) {
    List<UserResponse> candidates =
        users.list(ctx.schoolId(), page()).getContent().stream()
            .filter(u -> u.role() == role)
            .toList();
    return NameMatching.match(name, candidates, UserResponse::name);
  }

  private static PageRequest page() {
    return PageRequest.of(0, MAX_CANDIDATES);
  }
}

