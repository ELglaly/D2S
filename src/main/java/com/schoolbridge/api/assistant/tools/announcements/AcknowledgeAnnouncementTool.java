package com.schoolbridge.api.assistant.tools.announcements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.announcements.Announcement;
import com.schoolbridge.api.announcements.AnnouncementRecipient;
import com.schoolbridge.api.announcements.repository.AnnouncementRecipientRepository;
import com.schoolbridge.api.announcements.repository.AnnouncementRepository;
import com.schoolbridge.api.announcements.service.AnnouncementService;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.action.AbstractActionTool;
import com.schoolbridge.api.assistant.tools.action.ActionSupport;
import com.schoolbridge.api.assistant.tools.support.Args;
import com.schoolbridge.api.assistant.tools.support.NameMatching;
import com.schoolbridge.api.assistant.tools.support.NameMatching.MatchResult;
import com.schoolbridge.api.assistant.tools.support.Schema;
import com.schoolbridge.api.common.security.PermissionsHelper;
import com.schoolbridge.api.identity.UserRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/** PARENT — acknowledge an announcement. Mirrors {@code POST /announcements/{id}/acknowledge}. */
@Component
public class AcknowledgeAnnouncementTool extends AbstractActionTool {

  private static final int MAX = 50;

  private final AnnouncementRecipientRepository recipients;
  private final AnnouncementRepository announcements;
  private final AnnouncementService service;
  private final PermissionsHelper perms;

  public AcknowledgeAnnouncementTool(
      ActionSupport actions,
      AnnouncementRecipientRepository recipients,
      AnnouncementRepository announcements,
      AnnouncementService service,
      PermissionsHelper perms) {
    super(actions);
    this.recipients = recipients;
    this.announcements = announcements;
    this.service = service;
    this.perms = perms;
  }

  @Override
  public String name() {
    return "acknowledge_announcement";
  }

  @Override
  public String description() {
    return "Acknowledge an announcement the parent has received.";
  }

  @Override
  public JsonNode inputSchema() {
    return Schema.builder()
        .str("announcementRef", "A distinctive phrase from the announcement", true)
        .build();
  }

  @Override
  public Set<UserRole> roles() {
    return Set.of(UserRole.PARENT);
  }

  @Override
  protected PrepResult prepare(JsonNode args, ToolContext ctx) {
    String ref = Args.str(args, "announcementRef");
    if (ref == null) {
      return reject(ToolResult.clarify(msg("assistant.resolve.announcement.missing")));
    }
    List<AnnouncementRecipient> rows =
        recipients
            .findAllByParentUserIdAndAcknowledgedAtIsNull(
                ctx.userId(), PageRequest.of(0, MAX, Sort.by(Sort.Direction.DESC, "createdAt")))
            .getContent();
    List<UUID> ids =
        rows.stream().map(AnnouncementRecipient::getAnnouncementId).distinct().toList();
    List<Announcement> candidates = announcements.findAllById(ids);
    MatchResult<Announcement> match = NameMatching.match(ref, candidates, Announcement::getBody);
    if (match.none()) {
      return reject(ToolResult.clarify(msg("assistant.resolve.announcement.none", ref)));
    }
    if (match.ambiguous()) {
      return reject(ToolResult.clarify(msg("assistant.resolve.announcement.ambiguous", ref, ref)));
    }
    UUID announcementId = match.first().getId();
    if (!perms.parentReceivedAnnouncement(announcementId)) {
      return deniedKey("assistant.denied.child");
    }

    ObjectNode resolved = newArgs();
    resolved.put("announcementId", announcementId.toString());

    Map<String, Object> impact = new LinkedHashMap<>();
    impact.put("action", name());
    impact.put("announcement", snippet(match.first().getBody()));

    return readyMsg(resolved, "assistant.action.acknowledge_announcement.summary", impact, 1);
  }

  @Override
  protected ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx) {
    UUID announcementId = uuid(resolvedArgs, "announcementId");
    service.acknowledge(announcementId, ctx.userId());
    return ToolResult.ok(Map.of("acknowledged", true));
  }

  private static String snippet(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= 40 ? body : body.substring(0, 40) + "…";
  }
}
