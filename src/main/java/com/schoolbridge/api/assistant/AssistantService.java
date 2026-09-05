package com.schoolbridge.api.assistant;

import com.schoolbridge.api.assistant.dto.AskRequest;
import com.schoolbridge.api.assistant.dto.AssistantAnswer;
import com.schoolbridge.api.assistant.tools.ToolContext;

/** Orchestrates a single natural-language request: read answer or (Phase 3) action proposal. */
public interface AssistantService {

  AssistantAnswer ask(AskRequest request, ToolContext ctx);
}
