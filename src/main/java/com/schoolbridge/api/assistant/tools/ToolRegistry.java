package com.schoolbridge.api.assistant.tools;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Holds every {@link Tool} bean and exposes the permission-filtered subset for a request. A tool is
 * visible to a caller iff {@link ToolAuthorizer#canUseTool} allows it â€” i.e. the caller's role
 * holds one of the tool's permissions in the DB-backed {@code role_permissions} grants. The
 * registry itself performs no role or permission logic; it delegates to the authorizer and adds the
 * v1 read-only boundary and deterministic ordering.
 *
 * <p>Shipped v1 is read-only. Action tools are removed from the registry irrespective of any
 * environment property. The by-name sort keeps the serialized tool catalog byte-identical across
 * requests (for a fixed grant set), a prerequisite for the provider caching the system+tool prefix.
 */
@Component
public class ToolRegistry {

  private final List<Tool> tools;
  private final ToolAuthorizer authorizer;

  public ToolRegistry(List<Tool> tools, ToolAuthorizer authorizer) {
    this.tools = tools.stream().filter(tool -> tool.kind() == ToolKind.READ).toList();
    this.authorizer = authorizer;
  }

  /**
   * All read tools the caller is authorized for, in deterministic by-name order. Resolves the
   * caller's grants once and reuses them across the filter.
   */
  public List<Tool> toolsFor(ToolContext ctx) {
    Set<String> granted = authorizer.grantsFor(ctx);
    return tools.stream()
        .filter(t -> authorizer.canUseTool(t, granted))
        .sorted(Comparator.comparing(Tool::name))
        .toList();
  }

  /** Find an authorized read tool by name. */
  public Optional<Tool> find(String name, ToolContext ctx) {
    return toolsFor(ctx).stream().filter(t -> t.name().equals(name)).findFirst();
  }

  /** Every registered v1 tool, regardless of authorization (used by the oracle test). */
  public List<Tool> all() {
    return tools;
  }
}
