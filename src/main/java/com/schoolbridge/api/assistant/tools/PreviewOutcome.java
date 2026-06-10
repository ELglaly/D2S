package com.schoolbridge.api.assistant.tools;

import com.schoolbridge.api.assistant.dto.ActionPreview;

/**
 * Result of {@code ActionTool.preview(...)}: either a stored, ready-to-confirm {@link
 * ActionPreview} (the orchestrator halts and asks the user), or a {@link ToolResult} rejection —
 * denied or clarify — that is fed back to the model so it can explain or ask for more detail.
 */
public sealed interface PreviewOutcome {

  /** Scope passed, impact computed, single-use token stored. Halt and confirm. */
  record Prepared(ActionPreview preview) implements PreviewOutcome {}

  /** Denied or needs-clarification; nothing was stored and nothing will mutate. */
  record Rejected(ToolResult result) implements PreviewOutcome {}
}
