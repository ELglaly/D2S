package com.schoolbridge.api.assistant.tools.homework;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.dto.ParentHomeworkFeedEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** PARENT â€” a child's homework feed. Mirrors {@code GET /homework?childId=}. */
@Component
public class GetChildHomeworkTool implements ReadTool {

  private static final int MAX = 50;

  private final ToolSupport support;
  private final HomeworkService homework;

  public GetChildHomeworkTool(ToolSupport support, HomeworkService homework) {
    this.support = support;
    this.homework = homework;
  }

  @Override
  public String name() {
    return "get_child_homework";
  }

  @Override
  public String description() {
    return "List a child's homework items and acknowledgment state, optionally filtered by due date.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("childName", "Child's first or full name", false)
        .date("dueOn", "Only items due on this date YYYY-MM-DD", false)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.HOMEWORK_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<ParentChildResponse> child = support.child(ctx, Args.str(args, "childName"));
    if (child.clarified()) {
      return child.result();
    }
    UUID childId = child.value().studentId();
    List<ParentHomeworkFeedEntry> feed =
        homework.parentFeed(ctx.userId(), childId, PageRequest.of(0, MAX)).getContent();
    LocalDate dueOn = Args.date(args, "dueOn");
    if (dueOn != null) {
      feed = feed.stream().filter(e -> dueOn.equals(e.dueDate())).toList();
    }
    return ToolResult.ok(feed);
  }
}
