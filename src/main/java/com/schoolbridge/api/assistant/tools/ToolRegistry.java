package com.schoolbridge.api.assistant.tools;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds every {@link Tool} bean and exposes the permission-filtered subset for a request. A tool is
 * visible to a caller iff {@link ToolAuthorizer#canUseTool} allows it â€” i.e. the caller's role
 * holds one of the tool's permissions in the DB-backed {@code role_permissions} grants. The
 * registry itself performs no role or permission logic; it delegates to the authorizer and only
 * adds the actions kill-switch and deterministic ordering.
 *
 * <p>Action tools are additionally suppressed when {@code schoolbridge.assistant.actions.enabled}
 * is false, so reads can ship before writes are switched on. The by-name sort keeps the serialized
 * tool catalog byte-identical across requests (for a fixed grant set), a prerequisite for the
 * provider caching the system+tool prefix.
 */
@Component
public class ToolRegistry {

  private final List<Tool> tools;
  private final ToolAuthorizer authorizer;
  private final boolean actionsEnabled;

  public ToolRegistry(
      List<Tool> tools,
      ToolAuthorizer authorizer,
      @Value("${schoolbridge.assistant.actions.enabled:false}") boolean actionsEnabled) {
    this.tools = List.copyOf(tools);
    this.authorizer = authorizer;
    this.actionsEnabled = actionsEnabled;
  }

  /**
   * All tools the caller is authorized for (action tools only when actions are enabled), in a
   * deterministic by-name order. Resolves the caller's grants once and reuses them across the
   * filter.
   */
  public List<Tool> toolsFor(ToolContext ctx) {
    Set<String> granted = authorizer.grantsFor(ctx);
    return tools.stream()
        .filter(t -> authorizer.canUseTool(t, granted))
        .filter(t -> actionsEnabled || t.kind() == ToolKind.READ)
        .sorted(Comparator.comparing(Tool::name))
        .toList();
  }

  /** Find an authorized tool by name (respects the actions kill-switch). */
  public Optional<Tool> find(String name, ToolContext ctx) {
    return toolsFor(ctx).stream().filter(t -> t.name().equals(name)).findFirst();
  }

  /**
   * Every registered tool, regardless of authorization or kill-switch (used by the oracle test).
   */
  public List<Tool> all() {
    return tools;
  }
}

