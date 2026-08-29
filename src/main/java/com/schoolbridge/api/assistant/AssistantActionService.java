package com.schoolbridge.api.assistant;

import com.schoolbridge.api.assistant.dto.ConfirmActionRequest;
import com.schoolbridge.api.assistant.dto.ConfirmResult;
import com.schoolbridge.api.assistant.tools.ToolContext;

/** Phase B of the confirm-then-execute protocol: confirm or cancel a previewed action by token. */
public interface AssistantActionService {

  ConfirmResult confirm(String token, ConfirmActionRequest body, ToolContext ctx);

  ConfirmResult cancel(String token, ToolContext ctx);
}

