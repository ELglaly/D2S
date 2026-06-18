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
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import com.schoolbridge.api.identity.UserRole;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** TEACHER/ADMIN — recipients + delivery state of a homework item. */
@Component
public class GetHomeworkRecipientsTool implements ReadTool {

  private final ToolSupport support;
  private final HomeworkService homework;

  public GetHomeworkRecipientsTool(ToolSupport support, HomeworkService homework) {
    this.support = support;
    this.homework = homework;
  }

  @Override
  public String name() {
    return "get_homework_recipients";
  }

  @Override
  public String description() {
    return "List the parent recipients and delivery state for a homework item in a class.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("classRef", "Class the homework belongs to", true)
        .str("homeworkRef", "Homework subject/title", true)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.TEACHER, UserRole.SCHOOL_ADMIN);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<SchoolClassResponse> clazz = support.clazz(ctx, Args.str(args, "classRef"));
    if (clazz.clarified()) {
      return clazz.result();
    }
    UUID classId = clazz.value().id();
    if (!support.teachesOrAdmin(ctx, classId)) {
      return support.denied("assistant.denied.class");
    }
    Resolved<HomeworkResponse> hw = support.homework(classId, Args.str(args, "homeworkRef"));
    if (hw.clarified()) {
      return hw.result();
    }
    return ToolResult.ok(homework.listRecipients(hw.value().id()));
  }
}
