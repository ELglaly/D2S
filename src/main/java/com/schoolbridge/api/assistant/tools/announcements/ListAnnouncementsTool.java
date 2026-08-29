package com.schoolbridge.api.assistant.tools.announcements;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** ADMIN â€” list recent school announcements. Mirrors {@code GET /announcements}. */
@Component
public class ListAnnouncementsTool implements ReadTool {

  private static final int MAX = 50;

  private final AnnouncementService announcements;

  public ListAnnouncementsTool(AnnouncementService announcements) {
    this.announcements = announcements;
  }

  @Override
  public String name() {
    return "list_announcements";
  }

  @Override
  public String description() {
    return "List the most recent announcements posted in the school.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder().build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ANNOUNCEMENT_MANAGE);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    return ToolResult.ok(announcements.list(null, PageRequest.of(0, MAX)).getContent());
  }
}

