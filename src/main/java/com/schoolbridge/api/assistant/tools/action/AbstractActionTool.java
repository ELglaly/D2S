package com.schoolbridge.api.assistant.tools.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schoolbridge.api.assistant.confirm.PendingAction;
import com.schoolbridge.api.assistant.dto.ActionPreview;
import com.schoolbridge.api.assistant.tools.ActionTool;
import com.schoolbridge.api.assistant.tools.PreviewOutcome;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.assistant.tools.ToolResult;
import com.schoolbridge.api.assistant.tools.support.Resolved;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Skeleton for action tools. Owns the whole confirm-then-execute machinery â€” bulk cap, token issue,
 * Redis store, single-use consume, user/expiry re-checks â€” so each concrete tool implements only:
 *
 * <ul>
 *   <li>{@link #prepare} â€” validate scope, resolve namesâ†’ids, compute the bilingual impact (no
 *       mutation), and
 *   <li>{@link #doExecute} â€” re-guard then call the backing service with the resolved args.
 * </ul>
 */
public abstract class AbstractActionTool implements ActionTool {

  /** Confirmation previews always ship both languages; these render each from the bundle. */
  protected static final Locale AR = Locale.forLanguageTag("ar");

  protected static final Locale EN = Locale.ENGLISH;

  protected final ActionSupport actions;

  protected AbstractActionTool(ActionSupport actions) {
    this.actions = actions;
  }

  @Override
  public final PreviewOutcome preview(JsonNode args, ToolContext ctx) {
    JsonNode safeArgs = args == null ? actions.mapper().createObjectNode() : args;
    PrepResult result = prepare(safeArgs, ctx);
    if (result instanceof PrepResult.Reject reject) {
      return new PreviewOutcome.Rejected(reject.result());
    }
    Preparation prep = ((PrepResult.Ready) result).preparation();
    if (prep.impactCount() > actions.properties().getActions().getMaxBulkImpact()) {
      return new PreviewOutcome.Rejected(
          ToolResult.denied(actions.messages().get("assistant.action.too_large")));
    }
    String token = actions.tokens().generate();
    Instant now = Instant.now();
    Instant expiresAt = now.plus(actions.properties().getActions().getConfirmationTtl());
    PendingAction pending =
        new PendingAction(
            token,
            ctx.userId(),
            ctx.schoolId(),
            name(),
            prep.resolvedArgs(),
            prep.impact(),
            destructive(),
            now,
            expiresAt);
    actions.store().put(pending, actions.properties().getActions().getConfirmationTtl());
    return new PreviewOutcome.Prepared(
        new ActionPreview(
            token, prep.summaryAr(), prep.summaryEn(), prep.impact(), destructive(), expiresAt));
  }

  @Override
  public final ToolResult execute(String token, ToolContext ctx) {
    Optional<PendingAction> consumed = actions.store().consume(token);
    if (consumed.isEmpty()) {
      return ToolResult.error(actions.messages().get("assistant.action.invalid"));
    }
    PendingAction action = consumed.get();
    if (!action.userId().equals(ctx.userId()) || !name().equals(action.toolName())) {
      return ToolResult.error(actions.messages().get("assistant.action.invalid"));
    }
    if (action.expiresAt().isBefore(Instant.now())) {
      return ToolResult.error(actions.messages().get("assistant.action.expired"));
    }
    return doExecute(action.resolvedArgs(), ctx.withIdempotencyKey("assistant:action:" + token));
  }

  /** Validate + resolve + compute impact. NEVER mutates. */
  protected abstract PrepResult prepare(JsonNode args, ToolContext ctx);

  /** Re-guard then mutate via the existing service, reading only the resolved args. */
  protected abstract ToolResult doExecute(JsonNode resolvedArgs, ToolContext ctx);

  // --- helpers for subclasses ----------------------------------------------

  protected ObjectNode newArgs() {
    return actions.mapper().createObjectNode();
  }

  protected String msg(String key, Object... args) {
    return actions.messages().get(key, args);
  }

  /** Resolve a key in an explicit locale (used to build both ar + en preview summaries). */
  protected String msgIn(Locale locale, String key, Object... args) {
    return actions.messages().get(locale, key, args);
  }

  /**
   * Build a {@code Ready} preparation whose ar + en summaries are the same {@code summaryKey}
   * rendered in each language with the same positional {@code args}. Use the explicit {@link
   * #ready} overload when an argument is itself locale-dependent (e.g. a translated clause).
   */
  protected PrepResult readyMsg(
      JsonNode resolvedArgs,
      String summaryKey,
      Map<String, Object> impact,
      int impactCount,
      Object... args) {
    return ready(
        resolvedArgs,
        msgIn(AR, summaryKey, args),
        msgIn(EN, summaryKey, args),
        impact,
        impactCount);
  }

  protected static java.util.UUID uuid(JsonNode node, String field) {
    return java.util.UUID.fromString(node.get(field).asText());
  }

  protected static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  protected static java.time.LocalDate localDate(JsonNode node, String field) {
    String v = text(node, field);
    return v == null ? null : java.time.LocalDate.parse(v);
  }

  protected PrepResult reject(ToolResult result) {
    return new PrepResult.Reject(result);
  }

  protected PrepResult deniedKey(String key, Object... args) {
    return reject(ToolResult.denied(actions.messages().get(key, args)));
  }

  /** Propagate a name-resolution clarification (missing/none/ambiguous) straight to the model. */
  protected PrepResult clarify(Resolved<?> resolved) {
    return reject(resolved.result());
  }

  protected PrepResult ready(
      JsonNode resolvedArgs,
      String summaryAr,
      String summaryEn,
      Map<String, Object> impact,
      int impactCount) {
    return new PrepResult.Ready(
        new Preparation(resolvedArgs, summaryAr, summaryEn, impact, impactCount));
  }

  // --- result types --------------------------------------------------------

  protected sealed interface PrepResult {
    record Ready(Preparation preparation) implements PrepResult {}

    record Reject(ToolResult result) implements PrepResult {}
  }

  protected record Preparation(
      JsonNode resolvedArgs,
      String summaryAr,
      String summaryEn,
      Map<String, Object> impact,
      int impactCount) {}
}

