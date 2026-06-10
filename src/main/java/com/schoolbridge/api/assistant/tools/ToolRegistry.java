package com.schoolbridge.api.assistant.tools;

import com.schoolbridge.api.identity.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Holds every {@link Tool} bean and exposes the role-filtered subset for a request. A tool is
 * visible to a principal iff its {@link Tool#roles()} contains the caller's role — the same set the
 * Appendix authorization-oracle test asserts against the backing endpoints.
 *
 * <p>Action tools are additionally suppressed when {@code schoolbridge.assistant.actions.enabled}
 * is false, so reads can ship before writes are switched on.
 */
@Component
public class ToolRegistry {

  private final List<Tool> tools;
  private final boolean actionsEnabled;

  public ToolRegistry(
      List<Tool> tools,
      @org.springframework.beans.factory.annotation.Value(
              "${schoolbridge.assistant.actions.enabled:false}")
          boolean actionsEnabled) {
    this.tools = List.copyOf(tools);
    this.actionsEnabled = actionsEnabled;
  }

  /** All tools available to the caller's role (action tools only when actions are enabled). */
  public List<Tool> toolsFor(ToolContext ctx) {
    return tools.stream()
        .filter(t -> t.roles().contains(ctx.role()))
        .filter(t -> actionsEnabled || t.kind() == ToolKind.READ)
        .toList();
  }

  /** Find a role-visible tool by name (respects the actions kill-switch). */
  public Optional<Tool> find(String name, ToolContext ctx) {
    return toolsFor(ctx).stream().filter(t -> t.name().equals(name)).findFirst();
  }

  /** Every registered tool, regardless of role or kill-switch (used by the oracle test). */
  public List<Tool> all() {
    return tools;
  }

  /** Tools registered for a role, ignoring the actions kill-switch (oracle test helper). */
  public List<Tool> withRole(UserRole role) {
    return tools.stream().filter(t -> t.roles().contains(role)).toList();
  }
}
