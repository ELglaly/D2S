package com.schoolbridge.api.assistant.tools.announcements;

import com.fasterxml.jackson.databind.JsonNode;
import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.assistant.dto.UnacknowledgedAnnouncementView;
import com.schoolbridge.api.assistant.tools.ReadTool;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** PARENT â€” announcements the parent has received but not yet acknowledged. */
@Component
public class GetUnacknowledgedAnnouncementsTool implements ReadTool {

  private static final int MAX = 50;

  private final AnnouncementRecipientRepository recipients;
  private final AnnouncementRepository announcements;

  public GetUnacknowledgedAnnouncementsTool(
      AnnouncementRecipientRepository recipients, AnnouncementRepository announcements) {
    this.recipients = recipients;
    this.announcements = announcements;
  }

  @Override
  public String name() {
    return "get_unacknowledged_announcements";
  }

  @Override
  public String description() {
    return "List announcements the parent has received but not yet acknowledged.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.empty();
  }

  @Override
  public Set<Permission> permissions() {
    return Set.of(Permission.ANNOUNCEMENT_READ);
  }

  @Override
  public ToolResult execute(JsonNode args, ToolContext ctx) {
    List<AnnouncementRecipient> rows =
        recipients
            .findAllByParentUserIdAndAcknowledgedAtIsNull(
                ctx.userId(), PageRequest.of(0, MAX, Sort.by(Sort.Direction.DESC, "createdAt")))
            .getContent();
    List<UUID> ids =
        rows.stream().map(AnnouncementRecipient::getAnnouncementId).distinct().toList();
    Map<UUID, Announcement> byId =
        announcements.findAllById(ids).stream()
            .collect(Collectors.toMap(Announcement::getId, Function.identity()));
    List<UnacknowledgedAnnouncementView> views =
        rows.stream()
            .map(
                r -> {
                  Announcement a = byId.get(r.getAnnouncementId());
                  return new UnacknowledgedAnnouncementView(
                      r.getAnnouncementId(),
                      r.getStudentId(),
                      a == null ? null : a.getBody(),
                      a != null && a.isRequiresAck(),
                      r.getCreatedAt());
                })
            .toList();
    return ToolResult.ok(views);
  }
}

