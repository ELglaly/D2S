package com.schoolbridge.api.assistant.tools.announcements;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.assistant.tools.support.ToolSupport;
import com.schoolbridge.api.common.security.authz.Permission;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * ADMIN â€” recipients + delivery status for an announcement. Mirrors {@code GET
 * /announcements/{id}/recipients}.
 */
@Component
public class GetAnnouncementRecipientsTool implements ReadTool {

  private static final int MAX = 200;

  private final ToolSupport support;
  private final AnnouncementService announcements;

  public GetAnnouncementRecipientsTool(ToolSupport support, AnnouncementService announcements) {
    this.support = support;
    this.announcements = announcements;
  }

  @Override
  public String name() {
    return "get_announcement_recipients";
  }

  @Override
  public String description() {
    return "List recipients and per-recipient delivery status for an announcement.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("announcementRef", "A distinctive phrase from the announcement body", true)
        .build();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ANNOUNCEMENT_MANAGE);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    Resolved<AnnouncementResponse> ann = support.announcement(Args.str(args, "announcementRef"));
    if (ann.clarified()) {
      return ann.result();
    }
    return ToolResult.ok(
        announcements.listRecipients(ann.value().id(), PageRequest.of(0, MAX)).getContent());
  }
}
