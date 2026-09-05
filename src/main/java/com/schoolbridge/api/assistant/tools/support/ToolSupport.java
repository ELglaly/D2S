package com.schoolbridge.api.assistant.tools.support;

import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.NameMatching.MatchResult;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.classes.dto.StudentResponse;
import com.schoolbridge.api.common.i18n.MessageResolver;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import com.schoolbridge.api.identity.dto.UserResponse;
import com.schoolbridge.api.subjects.dto.SubjectResponse;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Turns an LLM-supplied name into a uniquely-resolved entity, or a localized {@link ToolResult}
 * clarification when the name is missing, unknown, or ambiguous. Keeps UUIDs out of the model and
 * gives every tool one consistent resolution path.
 */
@Component
public class ToolSupport {

  /** Length of body snippet used when listing ambiguous announcement candidates. */
  private static final int SNIPPET = 40;

  private final AssistantResolvers resolvers;
  private final MessageResolver messages;
  private final PermissionsHelper perms;

  public ToolSupport(
      AssistantResolvers resolvers, MessageResolver messages, PermissionsHelper perms) {
    this.resolvers = resolvers;
    this.messages = messages;
    this.perms = perms;
  }

  /**
   * Class-scope guard shared by teacher/admin tools: an admin may touch any class; a teacher only a
   * class they teach. Returns {@code true} when the caller is in scope.
   */
  public boolean teachesOrAdmin(ToolContext ctx, UUID classId) {
    return ctx.isAdmin() || perms.teacherTeaches(classId);
  }

  public ToolResult clarify(String key, Object... args) {
    return ToolResult.clarify(messages.get(key, args));
  }

  public ToolResult denied(String key, Object... args) {
    return ToolResult.denied(messages.get(key, args));
  }

  public Resolved<ParentChildResponse> child(ToolContext ctx, String name) {
    if (name == null || name.isBlank()) {
      java.util.List<ParentChildResponse> all = resolvers.childrenList(ctx);
      if (all.size() == 1) {
        return new Resolved.Of<>(all.get(0));
      }
      return needs("assistant.resolve.child.missing");
    }
    return resolve(
        name, "child", () -> resolvers.children(ctx, name), ParentChildResponse::fullName);
  }

  public Resolved<SchoolClassResponse> clazz(ToolContext ctx, String name) {
    return resolve(name, "class", () -> resolvers.classes(ctx, name), SchoolClassResponse::name);
  }

  public Resolved<StudentResponse> student(ToolContext ctx, String name) {
    return resolve(name, "student", () -> resolvers.students(ctx, name), StudentResponse::fullName);
  }

  public Resolved<SubjectResponse> subject(ToolContext ctx, String name) {
    return resolve(name, "subject", () -> resolvers.subjects(ctx, name), SubjectResponse::name);
  }

  public Resolved<UserResponse> teacher(ToolContext ctx, String name) {
    return resolve(name, "teacher", () -> resolvers.teachers(ctx, name), UserResponse::name);
  }

  public Resolved<UserResponse> parent(ToolContext ctx, String name) {
    return resolve(name, "parent", () -> resolvers.parents(ctx, name), UserResponse::name);
  }

  public Resolved<HomeworkResponse> homework(UUID classId, String ref) {
    return resolve(
        ref, "homework", () -> resolvers.homework(classId, ref), HomeworkResponse::subject);
  }

  public Resolved<AnnouncementResponse> announcement(String ref) {
    return resolve(ref, "announcement", () -> resolvers.announcements(ref), a -> snippet(a.body()));
  }

  private <C> Resolved<C> resolve(
      String name, String label, Supplier<MatchResult<C>> matcher, Function<C, String> nameFn) {
    if (name == null || name.isBlank()) {
      return needs("assistant.resolve." + label + ".missing");
    }
    MatchResult<C> m = matcher.get();
    if (m.none()) {
      return needs("assistant.resolve." + label + ".none", name);
    }
    if (m.ambiguous()) {
      String candidates = m.matches().stream().map(nameFn).collect(Collectors.joining("ØŒ "));
      return needs("assistant.resolve." + label + ".ambiguous", name, candidates);
    }
    return new Resolved.Of<>(m.first());
  }

  private <C> Resolved<C> needs(String key, Object... args) {
    return new Resolved.NeedsClarification<>(ToolResult.clarify(messages.get(key, args)));
  }

  private static String snippet(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= SNIPPET ? body : body.substring(0, SNIPPET) + "â€¦";
  }
}
