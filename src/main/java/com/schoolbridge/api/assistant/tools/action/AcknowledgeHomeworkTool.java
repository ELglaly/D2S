package com.schoolbridge.api.assistant.tools.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.NameMatching;
import com.schoolbridge.api.assistant.tools.support.NameMatching.MatchResult;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.classes.dto.ParentChildResponse;
import com.schoolbridge.api.homework.HomeworkService;
import com.schoolbridge.api.homework.dto.ParentHomeworkFeedEntry;
import com.schoolbridge.api.identity.UserRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** PARENT — acknowledge a homework item. Mirrors {@code POST /homework/{id}/acknowledge}. */
@Component
public class AcknowledgeHomeworkTool extends AbstractActionTool {

  private static final int MAX = 100;

  private final HomeworkService homework;

  public AcknowledgeHomeworkTool(ActionSupport actions, HomeworkService homework) {
    super(actions);
    this.homework = homework;
  }

  @Override
  public String name() {
    return "acknowledge_homework";
  }

  @Override
  public String description() {
    return "Mark a child's homework item as seen/acknowledged.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("childName", "Child's first or full name", false)
        .str("homeworkRef", "Homework subject/title", true)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.PARENT);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    Resolved<ParentChildResponse> child = actions.tools().child(ctx, Args.str(args, "childName"));
    if (child.clarified()) {
      return clarify(child);
    }
    String ref = Args.str(args, "homeworkRef");
    if (ref == null) {
      return reject(ToolResult.clarify(msg("assistant.resolve.homework.missing")));
    }
    MatchResult<ParentHomeworkFeedEntry> match =
        NameMatching.match(
            ref,
            homework
                .parentFeed(ctx.userId(), child.value().studentId(), PageRequest.of(0, MAX))
                .getContent(),
            ParentHomeworkFeedEntry::subject);
    if (match.none()) {
      return reject(ToolResult.clarify(msg("assistant.resolve.homework.none", ref)));
    }
    if (match.ambiguous()) {
      return reject(ToolResult.clarify(msg("assistant.resolve.homework.ambiguous", ref, ref)));
    }
    ParentHomeworkFeedEntry entry = match.first();

    ObjectNode resolved = newArgs();
    resolved.put("homeworkId", entry.homeworkId().toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("child", child.value().fullName());
    impact.put("homework", entry.subject());

    String en =
        "I'll acknowledge \""
            + entry.subject()
            + "\" for "
            + child.value().fullName()
            + ". Confirm?";
    String ar =
        "سأؤكّد اطّلاعك على «" + entry.subject() + "» لـ " + child.value().fullName() + ". أؤكّد؟";
    return ready(resolved, ar, en, impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID homeworkId = uuid(resolvedArgs, "homeworkId");
    homework.acknowledge(homeworkId, ctx.userId());
    return ToolResult.ok(Map.of("acknowledged", true));
  }
}
