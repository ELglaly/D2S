package com.schoolbridge.api.assistant.tools.homework;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.SchoolClassResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.HomeworkStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * TEACHER/ADMIN — homework items, filterable by class/status/due range. Mirrors {@code GET
 * /homework}.
 */
@Component
public class ListHomeworkTool implements ReadTool {

  private static final int MAX = 100;

  private final ToolSupport support;
  private final HomeworkService homework;

  public ListHomeworkTool(ToolSupport support, HomeworkService homework) {
    this.support = support;
    this.homework = homework;
  }

  @Override
  public String name() {
    return "list_homework";
  }

  @Override
  public String description() {
    return "List homework items. Teachers must supply a class; admins may omit it to list "
        + "school-wide. Optionally filter by status and due-date range.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class name (required for teachers)", false)
        .enumStr("status", "Homework status", false, List.of("DRAFT", "PUBLISHED", "ARCHIVED"))
        .date("dueFrom", "Earliest due date YYYY-MM-DD", false)
        .date("dueTo", "Latest due date YYYY-MM-DD", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.HOMEWORK_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    String classRef = Args.str(args, "classRef");
    UUID classId = null;
    if (classRef != null) {
      Resolved<SchoolClassResponse> clazz = support.clazz(ctx, classRef);
      if (clazz.clarified()) {
        return clazz.result();
      }
      classId = clazz.value().id();
      if (!support.teachesOrAdmin(ctx, classId)) {
        return support.denied("assistant.denied.class");
      }
    } else if (ctx.isTeacher()) {
      return support.clarify("assistant.homework.class_required");
    }
    HomeworkStatus status = parseStatus(Args.str(args, "status"));
    LocalDate dueFrom = Args.date(args, "dueFrom");
    LocalDate dueTo = Args.date(args, "dueTo");
    return ToolResult.ok(
        homework.list(classId, status, dueFrom, dueTo, PageRequest.of(0, MAX)).getContent());
  }

  private HomeworkStatus parseStatus(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return HomeworkStatus.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
